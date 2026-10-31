package com.renexamc.lib

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

/**
 * Connectivity helpers that check REAL reachability, not just "connected".
 * (Wi-Fi can be connected with no actual internet behind it.)
 */
object Connectivity {

    /** True when the device reports an active network with internet capability. */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= 23) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /**
     * True when the servers the site depends on (Firebase is served from
     * gstatic.com) can actually be reached. Must be called off the main thread.
     */
    fun canReachServers(timeoutMs: Int = 5000): Boolean {
        return try {
            val conn = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
