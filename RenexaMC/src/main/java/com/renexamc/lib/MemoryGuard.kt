package com.renexamc.lib

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Watches the app's actual memory headroom and reacts BEFORE an
 * out-of-memory crash happens, instead of only cleaning up after one
 * (that's what CrashGuard already does). A WebView rendering a heavy
 * site is one of the most common OOM sources on low-RAM devices.
 *
 * When available memory drops below [criticalThresholdMb], [onCritical]
 * is invoked once (on the main thread) so the caller can proactively
 * trim memory - e.g. clear WebView cache, drop image caches - while
 * there's still enough headroom left to do it safely.
 */
class MemoryGuard(
    private val context: Context,
    private val onCritical: () -> Unit,
    private val checkIntervalMs: Long = 5000,
    private val criticalThresholdMb: Long = 60
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val alive = AtomicBoolean(false)
    private var lastWarnedAt = 0L
    private val rewarnCooldownMs = 30000

    fun start() {
        if (alive.getAndSet(true)) return
        thread(name = "RenexaMC-MemoryGuard", isDaemon = true) {
            while (alive.get()) {
                val info = readMemoryInfo()
                if (info != null) {
                    val availableMb = info.availMem / (1024 * 1024)
                    val isLow = info.lowMemory || availableMb < criticalThresholdMb
                    val now = System.currentTimeMillis()
                    if (isLow && (now - lastWarnedAt) > rewarnCooldownMs) {
                        lastWarnedAt = now
                        mainHandler.post { onCritical() }
                    }
                }
                Thread.sleep(checkIntervalMs)
            }
        }
    }

    fun stop() {
        alive.set(false)
    }

    private fun readMemoryInfo(): ActivityManager.MemoryInfo? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info
        } catch (e: Exception) {
            null
        }
    }
}
