package com.renexamc.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.renexamc.lib.Connectivity
import com.renexamc.lib.CrashGuard
import com.renexamc.lib.Diagnostics
import com.renexamc.lib.ErrorLog
import com.renexamc.lib.FreezeWatchdog
import com.renexamc.lib.KeepAlive
import com.renexamc.lib.MemoryGuard
import com.renexamc.lib.NetworkWatcher
import com.renexamc.lib.SecurityGuard
import com.renexamc.lib.SiteVault
import java.io.ByteArrayInputStream

/**
 * Loads the RenexaMC web app inside a WebView.
 *
 * The heavy lifting (decryption, real-reachability checks, auto-recovery on
 * network return, error logging) lives in the RenexaMC library module
 * (module ":lib", packaged as RenexaMC.aar) - this Activity just wires it to
 * the WebView.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var networkWatcher: NetworkWatcher
    private lateinit var freezeWatchdog: FreezeWatchdog
    private lateinit var memoryGuard: MemoryGuard
    private var adsManager: AdsManager? = null
    private var recoveringFromFreeze = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val HOST = "appassets.androidplatform.net"
    private val APP_URL = "https://appassets.androidplatform.net/app/index.html"
    private val OFFLINE_URL = "https://appassets.androidplatform.net/assets/offline.html"

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult
        val data = result.data
        val results: Array<Uri>? = if (result.resultCode == RESULT_OK && data != null) {
            val clipData = data.clipData
            if (clipData != null) {
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        } else null
        callback.onReceiveValue(results)
    }

    // Serves the decrypted site (and its manifest) from memory under /app/.
    private val appHandler = object : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            return try {
                when (path) {
                    "", "index.html" -> WebResourceResponse(
                        "text/html", "utf-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(SiteVault.decryptAsset(this@MainActivity, "app.dat"))
                    )
                    "manifest.json" -> WebResourceResponse(
                        "application/json", "utf-8", assets.open("manifest.json")
                    )
                    else -> null
                }
            } catch (e: Exception) {
                ErrorLog.add("asset handler: ${e.message}")
                null
            }
        }
    }

    inner class RetryBridge {
        @JavascriptInterface
        fun retry() {
            runOnUiThread { loadApp() }
        }

        @JavascriptInterface
        fun isRewardedAdReady(): Boolean = adsManager?.isReady() == true

        @JavascriptInterface
        fun showRewardedAd(kind: String) {
            runOnUiThread { adsManager?.show(if (kind == "gold") "gold" else "coins") }
        }

        @JavascriptInterface
        fun openOffline() {
            // Opens the bundled site without an internet connection.
            runOnUiThread { webView.loadUrl(APP_URL) }
        }

        @JavascriptInterface
        fun getErrorReport(): String = Diagnostics.buildReport(this@MainActivity)

        @JavascriptInterface
        fun getVersionCode(): Int {
            return try {
                packageManager.getPackageInfo(packageName, 0).let {
                    if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
                }
            } catch (e: Exception) {
                0
            }
        }

        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            runOnUiThread { postSystemNotification(title, message) }
        }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    ErrorLog.add("openExternalUrl failed: $url (${e.message})")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Integrity check FIRST, before any UI or the WebView: if this is not the
        // genuine, unmodified RenexaMC build, the app is killed right here.
        SecurityGuard.enforce(this)

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        // Keep the app alive in the background (foreground service + battery exemption).
        KeepAlive.start(this)
        KeepAlive.requestBatteryExemption(this)

        CrashGuard.install(this)
        if (CrashGuard.hadPreviousCrash(this)) {
            // Last run ended in a hard crash - the WebView's cached state
            // (cookies/localStorage aside, this is just the HTTP/render
            // cache) might be what caused it, so start clean this once.
            ErrorLog.add("Recovered from a previous native crash - clearing WebView cache.")
            WebView(this).clearCache(true)
            CrashGuard.clearPreviousCrash(this)
        }

        memoryGuard = MemoryGuard(this, onCritical = {
            ErrorLog.add("Memory critical - proactively trimming WebView cache.")
            try { webView.clearCache(false) } catch (e: Exception) {}
            System.gc()
        })

        freezeWatchdog = FreezeWatchdog(this, onFrozen = { recoverFromFreeze() })
        if (freezeWatchdog.hadPreviousFreeze()) {
            ErrorLog.add("Recovered from a previous freeze - clearing WebView cache.")
            WebView(this).clearCache(true)
            freezeWatchdog.clearPreviousFreeze()
        }

        webView = WebView(this)
        webView.setBackgroundColor(0xFF0b0f19.toInt())
        setContentView(webView)

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .addPathHandler("/app/", appHandler)
            .build()

        // Auto-recover the moment real internet comes back, without
        // waiting for the user to tap "retry".
        networkWatcher = NetworkWatcher(this) {
            runOnUiThread {
                if (webView.url == null || webView.url == OFFLINE_URL) {
                    loadApp()
                }
            }
        }

        configureWebView()
        loadApp()
    }

    private fun configureWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // The site also uses a few http:// image sources (e.g. skin avatars).
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)

        webView.addJavascriptInterface(RetryBridge(), "AndroidBridge")

        // Real rewarded ads (AdMob). The result goes back to the site's ad viewer.
        adsManager = AdsManager(this) { kind, outcome ->
            val safeKind = if (kind == "gold") "gold" else "coins"
            val safeOutcome = when (outcome) { "earned", "closed", "not_ready" -> outcome else -> "error" }
            runOnUiThread {
                webView.evaluateJavascript("window.AdViewer && window.AdViewer.onNativeResult('$safeKind','$safeOutcome');", null)
            }
        }

        // Keep the site's real colors even when Android's battery saver
        // tries to force dark/grayscale on WebView content.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
            androidx.webkit.WebSettingsCompat.setForceDark(settings, androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF)
        }

        webView.webChromeClient = object : WebChromeClient() {
            // The site opens Discord (and other links) via window.open(url, '_blank').
            // A plain WebView has nowhere to put that "new window" - without this,
            // it's either silently ignored or can behave unpredictably depending on
            // device/WebView version. We grab the target URL and hand it to a real
            // browser/Discord app via a normal Android Intent instead.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transient = WebView(this@MainActivity)
                transient.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        request?.url?.let { openExternally(it) }
                        return true
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = transient
                resultMsg?.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                if (intent.type.isNullOrEmpty()) intent.type = "image/*"

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                message?.let {
                    ErrorLog.add("${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(uri)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                // Only our own site's URL (served from HOST) should ever load
                // inside this WebView. Anything else - Discord, other sites,
                // a plain <a target="_blank"> link the user tapped - goes to
                // a real app/browser via Intent instead of being loaded here.
                if (uri.host == HOST) return false
                return openExternally(uri)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    ErrorLog.add("load error ${error?.errorCode}: ${error?.description} (${request.url})")
                    if (request.url.toString() != OFFLINE_URL) {
                        view?.loadUrl(OFFLINE_URL)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("offline.html") == true) {
                    // Hook the page's own retry button to a real connectivity
                    // re-check, without modifying the user's offline.html file.
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var btn = document.getElementById('retryBtn');
                            if (btn) {
                                btn.addEventListener('click', function() {
                                    if (window.AndroidBridge) { AndroidBridge.retry(); }
                                });
                            }
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }
        }
    }

    private val NOTIFICATION_CHANNEL_ID = "renexamc_messages"
    private var notificationIdCounter = 1000

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "RenexaMC - رسائل وإشعارات",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "إشعارات الرسائل والأحداث داخل RenexaMC"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun postSystemNotification(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)

        try {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(notificationIdCounter++, builder.build())
        } catch (e: SecurityException) {
            ErrorLog.add("Notification permission denied at post-time.")
        }
    }

    /** Opens a link in a real browser/app (Discord, etc.) instead of inside our WebView. */
    private fun openExternally(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            ErrorLog.add("Could not open external link: $uri (${e.message})")
            true
        }
    }

    private fun loadApp() {
        if (!Connectivity.isOnline(this)) {
            webView.loadUrl(OFFLINE_URL)
            return
        }
        Thread {
            val reachable = Connectivity.canReachServers()
            if (!reachable) ErrorLog.add("Servers unreachable (gstatic check failed)")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                webView.loadUrl(if (reachable) APP_URL else OFFLINE_URL)
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        networkWatcher.start()
        freezeWatchdog.start()
        memoryGuard.start()
    }

    override fun onStop() {
        super.onStop()
        networkWatcher.stop()
        freezeWatchdog.stop()
        memoryGuard.stop()
        webView.evaluateJavascript("window.__setPresence && window.__setPresence(false);", null)
    }

    override fun onDestroy() {
        // The app was really closed (not just sent to the background): remove the
        // "running in the background" notification so it can't be left without the app.
        if (isFinishing) KeepAlive.stop(this)
        super.onDestroy()
    }

    /**
     * Best-effort in-place recovery for a freeze that's severe but not
     * fully locked forever: stop whatever the WebView is doing, wipe its
     * cache, and reload the site fresh. If the main thread is truly dead,
     * this code never even gets to run - the persisted flag file (already
     * written by the watchdog's background thread) is what guarantees
     * recovery on the next launch instead.
     */
    private fun recoverFromFreeze() {
        if (recoveringFromFreeze) return
        recoveringFromFreeze = true
        ErrorLog.add("Freeze detected - attempting in-place recovery.")
        try {
            webView.stopLoading()
            webView.clearCache(true)
        } catch (e: Exception) {
        }
        loadApp()
        recoveringFromFreeze = false
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("window.__setPresence && window.__setPresence(true);", null)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
