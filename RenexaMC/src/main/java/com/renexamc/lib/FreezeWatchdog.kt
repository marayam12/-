package com.renexamc.lib

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Detects when the main thread / WebView stops responding ("frozen",
 * stuck spinning, shows up as an ANR in logcat) and reacts - instead of
 * leaving the user staring at a dead screen forever.
 *
 * How: a background thread posts a lightweight "heartbeat" to the main
 * Looper every [checkIntervalMs]. If the heartbeat hasn't been
 * acknowledged within [timeoutMs], the main thread is considered frozen:
 * - a flag file is written immediately (from the background thread, so
 *   this works even if the main thread never recovers at all)
 * - [onFrozen] is also posted to the main thread as a best-effort
 *   in-place recovery attempt, for freezes that are severe-but-temporary
 *   (e.g. one slow synchronous JS call) rather than fully locked forever
 *
 * On the NEXT app launch, [hadPreviousFreeze] lets the app know a freeze
 * was recorded, so it can start clean (clear WebView cache) instead of
 * loading the same state that froze last time.
 */
class FreezeWatchdog(
    private val context: Context,
    private val onFrozen: () -> Unit,
    private val timeoutMs: Long = 8000,
    private val checkIntervalMs: Long = 2000
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val alive = AtomicBoolean(false)
    @Volatile private var lastHeartbeatAck = System.currentTimeMillis()
    private var watcherThread: Thread? = null

    fun start() {
        if (alive.getAndSet(true)) return
        lastHeartbeatAck = System.currentTimeMillis()
        watcherThread = thread(name = "RenexaMC-FreezeWatchdog", isDaemon = true) {
            while (alive.get()) {
                mainHandler.post { lastHeartbeatAck = System.currentTimeMillis() }
                Thread.sleep(checkIntervalMs)
                if (!alive.get()) break
                if (System.currentTimeMillis() - lastHeartbeatAck > timeoutMs) {
                    markFrozen()
                    mainHandler.post { onFrozen() }
                    // Give the recovery attempt (or the user force-closing
                    // via the system ANR dialog) time to take effect before
                    // checking again, instead of spamming the callback.
                    Thread.sleep(timeoutMs)
                }
            }
        }
    }

    fun stop() {
        alive.set(false)
    }

    private fun flagFile() = File(context.filesDir, "renexamc_freeze.flag")

    private fun markFrozen() {
        try {
            flagFile().writeText("frozen:${System.currentTimeMillis()}")
        } catch (e: Exception) {
        }
    }

    fun hadPreviousFreeze(): Boolean = flagFile().exists()

    fun clearPreviousFreeze() {
        try {
            flagFile().delete()
        } catch (e: Exception) {
        }
    }
}
