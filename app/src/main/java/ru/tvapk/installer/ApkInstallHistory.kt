package ru.tvapk.installer

internal data class ApkInstallHistoryEntry(
    val timestamp: Long,
    val apkName: String,
    val transport: String,
    val success: Boolean,
    val details: String
) {
    fun displayText(formatTimestamp: (Long) -> String): String = buildString {
        append(if (success) "УСПЕШНО" else "ОШИБКА")
        append(" • ")
        append(if (timestamp > 0L) formatTimestamp(timestamp) else "Старая запись")
        append("\n")
        append(apkName)
        append(" • ")
        append(transport)
        if (details.isNotBlank()) {
            append("\n")
            append(details)
        }
    }
}

internal object ApkInstallHistory {
    const val MAX_ENTRIES = 30

    fun fromLegacyLine(line: String): ApkInstallHistoryEntry {
        val separator = line.indexOf(" — ")
        val apkName = if (separator > 0) line.substring(0, separator) else line
        val transport = if (line.contains(" — USB ")) "USB ADB" else "Wi-Fi ADB"
        return ApkInstallHistoryEntry(
            timestamp = 0L,
            apkName = apkName.ifBlank { "Неизвестный APK" },
            transport = transport,
            success = true,
            details = "Импортировано из старой истории: $line"
        )
    }

    fun limit(entries: List<ApkInstallHistoryEntry>): List<ApkInstallHistoryEntry> =
        entries.take(MAX_ENTRIES)
}
