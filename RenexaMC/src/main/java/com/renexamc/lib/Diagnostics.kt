package com.renexamc.lib

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Builds one clean, copy-pasteable text report combining device info,
 * app version, memory state, and the recent error log - so a bug report
 * can be a single block of text instead of several screenshots.
 */
object Diagnostics {

    fun buildReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== RenexaMC Diagnostics Report ===")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("App version: ${appVersion(context)}")
        sb.appendLine("Memory: ${memorySummary(context)}")
        sb.appendLine("Online: ${Connectivity.isOnline(context)}")
        sb.appendLine()
        sb.appendLine("--- Recent errors ---")
        val errors = ErrorLog.recent()
        if (errors.isEmpty()) {
            sb.appendLine("(none logged)")
        } else {
            errors.forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }

    private fun appVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName} ($code)"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    private fun memorySummary(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "unknown"
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val availMb = info.availMem / (1024 * 1024)
            val totalMb = info.totalMem / (1024 * 1024)
            "$availMb MB free / $totalMb MB total${if (info.lowMemory) " (LOW MEMORY)" else ""}"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
