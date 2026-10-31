package com.renexamc.lib

/**
 * Keeps the most recent JavaScript errors reported by the page so that, when
 * something breaks (for example Firebase not loading), the exact error can be
 * shown and copied instead of guessing.
 */
object ErrorLog {

    private val entries = mutableListOf<String>()

    private val CRITICAL = listOf(
        "firebase", "gstatic", "failed to fetch", "access-control",
        "cors", "net::err", "networkerror"
    )

    @Synchronized
    fun add(message: String) {
        if (entries.size >= 40) entries.removeAt(0)
        entries.add(message.take(400))
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun recent(): List<String> = entries.toList()

    fun isCritical(message: String): Boolean {
        val m = message.lowercase()
        return CRITICAL.any { m.contains(it) }
    }

    fun criticalRecent(): List<String> = recent().filter { isCritical(it) }

    fun report(): String = recent().joinToString("\n")
}
