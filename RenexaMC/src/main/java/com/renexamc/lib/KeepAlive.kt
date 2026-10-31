package com.renexamc.lib

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Entry point for keeping RenexaMC alive in the background.
 *
 *   KeepAlive.start(context)                      - starts the foreground service
 *   KeepAlive.requestBatteryExemption(activity)   - asks (once) to be exempt from battery optimisation
 */
object KeepAlive {

    private const val PREFS = "renexamc_keepalive"
    private const val KEY_ASKED_BATTERY = "asked_battery"

    fun start(context: Context) {
        try {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            ErrorLog.add("KeepAlive.start failed: ${e.message}")
        }
    }

    /** Stops the service and removes its notification. */
    fun stop(context: Context) {
        try {
            context.stopService(Intent(context, KeepAliveService::class.java))
        } catch (e: Exception) {
            ErrorLog.add("KeepAlive.stop failed: ${e.message}")
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Shows the system "allow background activity" dialog, only once per install. */
    fun requestBatteryExemption(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBatteryOptimizations(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_BATTERY, false)) return
        prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                ErrorLog.add("Battery exemption request failed: ${e2.message}")
            }
        }
    }
}
