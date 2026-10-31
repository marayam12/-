package com.renexamc.lib

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * App integrity / anti-tamper guard (libsecurity.so).
 *
 * This checks that the running app is really the genuine RenexaMC build, unmodified:
 *   - same package name (com.renexamc.app)
 *   - same release signing certificate - THIS is the check that actually matters.
 *     Android requires any modified APK (different code, a swapped app name, a swapped
 *     icon, anything) to be re-signed, and a re-signed APK has a different certificate
 *     unless it was signed with the original renexamc.keystore. So this one check is what
 *     catches someone copying the app's code and rebranding it with a new name/icon.
 *   - the app label (name) shown to the user still says "RenexaMC" (a secondary,
 *     human-readable sanity check; it never blocks the app on its own, only logs)
 *   - a native-side self-check (libsecurity.so) for an attached debugger or a known
 *     hooking/patching framework - harder to bypass than a pure-Kotlin check, because it
 *     also has to be patched inside the compiled library, not just the Kotlin/Java code.
 *
 * If the package name or the signature don't match, [enforce] kills the app immediately,
 * before the WebView or any UI is shown.
 *
 * IMPORTANT - this is NOT a virus scanner: no small app-side library can reliably scan
 * for generic malware, and nothing here claims to. What it verifies is that THIS APK is
 * the genuine, unmodified RenexaMC build - which is the correct, standard way to stop
 * someone from reusing this app's code under a different name/icon.
 *
 * If you ever re-sign the app with a different keystore, update EXPECTED_CERT_SHA256
 * below (keytool -list -v -keystore ...) - otherwise every real build gets killed too.
 */
object SecurityGuard {

    private const val EXPECTED_CERT_SHA256 =
        "E6673CD8B4DEC0EB990D59733D25693E44E20DB03683106F9DF1406AFD92F56C"
    private const val EXPECTED_PACKAGE = "com.renexamc.app"
    private const val EXPECTED_LABEL = "RenexaMC"

    private val nativeLoaded: Boolean = try {
        System.loadLibrary("security")
        true
    } catch (e: Throwable) {
        ErrorLog.add("libsecurity.so not loaded: ${e.message}")
        false
    }

    @JvmStatic private external fun nativeSelfCheck(): Boolean
    @JvmStatic private external fun nativePadSize(): Int

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02X".format(b))
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun signingCertHashes(context: Context): List<String> = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo
            val certs = if (signing != null && signing.hasMultipleSigners())
                signing.apkContentsSigners else signing?.signingCertificateHistory
            (certs ?: emptyArray()).map { sha256Hex(it.toByteArray()) }
        } else {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            (info.signatures ?: emptyArray()).map { sha256Hex(it.toByteArray()) }
        }
    } catch (e: Exception) {
        ErrorLog.add("SecurityGuard: could not read signing certificate: ${e.message}")
        emptyList()
    }

    private fun packageOk(context: Context) = context.packageName == EXPECTED_PACKAGE

    private fun signatureOk(context: Context) =
        signingCertHashes(context).any { it.equals(EXPECTED_CERT_SHA256, ignoreCase = true) }

    private fun labelOk(context: Context): Boolean = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(context.packageName, 0)).toString() == EXPECTED_LABEL
    } catch (e: Exception) {
        true // never block the app just because the label could not be read
    }

    /** True if the running app looks genuine. Safe to call repeatedly; does not kill anything. */
    fun verify(context: Context): Boolean {
        if (!packageOk(context)) {
            ErrorLog.add("SecurityGuard: package name is '${context.packageName}', expected '$EXPECTED_PACKAGE'")
            return false
        }
        if (!signatureOk(context)) {
            ErrorLog.add("SecurityGuard: signing certificate does not match the original RenexaMC build")
            return false
        }
        if (!labelOk(context)) {
            ErrorLog.add("SecurityGuard: app name shown to the user does not say 'RenexaMC'")
            // label mismatch alone does not kill the app - see note above
        }
        if (nativeLoaded && !nativeSelfCheck()) {
            ErrorLog.add("SecurityGuard: native integrity check failed (debugger or hook framework detected)")
            return false
        }
        return true
    }

    /**
     * Call this as the very first thing in MainActivity.onCreate(), before setContentView
     * or the WebView are touched. If the app is not genuine, it is killed right here - no
     * UI, no site, nothing is shown.
     */
    fun enforce(context: Context) {
        if (verify(context)) return
        try {
            Process.killProcess(Process.myPid())
        } finally {
            exitProcess(0)
        }
    }
}
