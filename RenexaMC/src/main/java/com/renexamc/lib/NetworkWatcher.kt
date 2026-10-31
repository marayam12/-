package com.renexamc.lib

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Calls [listener] whenever a network with internet capability becomes
 * available, so the app can recover by itself when the connection comes back
 * instead of waiting for the user to press "retry".
 * The listener runs on a background thread - post to the UI thread if needed.
 */
class NetworkWatcher(context: Context, private val listener: () -> Unit) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            listener()
        }
    }

    private var started = false

    fun start() {
        if (started) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            started = true
        } catch (e: Exception) {
            // Missing permission or unsupported device: recovery just stays manual.
        }
    }

    fun stop() {
        if (!started) return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
        }
        started = false
    }
}
