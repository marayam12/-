package com.renexamc.lib

import android.content.Context
import java.io.File

/**
 * Kotlin-facing wrapper around the native crash guard (librenexamc.so).
 * Installs OS-level signal handlers so a hard native crash is recorded
 * even if the JVM has no chance to run any Kotlin code before dying.
 */
object CrashGuard {

    init {
        System.loadLibrary("renexamc")
    }

    private fun flagPath(context: Context): String =
        File(context.filesDir, "renexamc_crash.flag").absolutePath

    /** Call once, as early as possible (e.g. in Application.onCreate / first Activity.onCreate). */
    fun install(context: Context) {
        nativeInstall(flagPath(context))
    }

    /** True if the previous run of the app ended in a hard crash. */
    fun hadPreviousCrash(context: Context): Boolean =
        nativeHadCrash(flagPath(context))

    /** Call after handling/recovering from a detected previous crash. */
    fun clearPreviousCrash(context: Context) {
        nativeClearCrash(flagPath(context))
    }

    private external fun nativeInstall(flagPath: String)
    private external fun nativeHadCrash(flagPath: String): Boolean
    private external fun nativeClearCrash(flagPath: String)
}
