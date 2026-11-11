package ru.tvapk.installer

/** Extracts a short, safe-to-display summary from the release description. */
internal object UpdateReleaseNotesPolicy {
    private const val MAX_LENGTH = 1_000

    fun forNotification(body: String): String {
        val lines = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("#") }
            .filterNot { it.startsWith("SHA-256:", ignoreCase = true) }
            .filterNot { it.startsWith("Автоматическая сборка", ignoreCase = true) }
            .filterNot { it.startsWith("Установочный APK", ignoreCase = true) }
            .map { it.removePrefix("-").trim() }
            .filter { it.isNotBlank() }
            .toList()
        return lines.joinToString(" • ").take(MAX_LENGTH)
    }
}
