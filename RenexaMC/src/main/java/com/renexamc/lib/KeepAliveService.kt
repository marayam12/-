package com.renexamc.lib

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import java.util.Locale

/**
 * Foreground service that keeps the RenexaMC process at "foreground service"
 * priority, so Android's low-memory killer treats it as one of the LAST
 * processes to kill instead of a normal cached background app.
 *
 * - Not sticky: if the process is killed, the service (and its notification) goes
 *   away with it, so a leftover "running" notification never appears without the app.
 * - onTaskRemoved: when the user swipes the app away from recents, the service
 *   stops itself and removes the notification.
 * - The notification has a "Close" button that fully closes the app.
 * - Shows one low-importance, silent notification (Android requires it).
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            ErrorLog.add("KeepAliveService.startForeground failed: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            // Fully close the app (the WebView lives in this process).
            Process.killProcess(Process.myPid())
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The user closed the app from recents: remove the notification too.
        shutDown()
    }

    private fun shutDown() {
        try {
            stopForeground(true)
        } catch (e: Exception) {
            ErrorLog.add("KeepAliveService.stopForeground failed: ${e.message}")
        }
        stopSelf()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ErrorLog.add("System low-memory signal received (KeepAliveService is protecting the process).")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "RenexaMC running", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            channel.setSound(null, null)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val ar = Locale.getDefault().language == "ar"
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RenexaMC")
            .setContentText(if (ar) "التطبيق يعمل في الخلفية" else "Running in the background")
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, if (ar) "إغلاق" else "Close", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.renexamc.lib.ACTION_STOP_KEEPALIVE"
        private const val CHANNEL_ID = "renexamc_keepalive"
        private const val NOTIFICATION_ID = 7001
    }
}
