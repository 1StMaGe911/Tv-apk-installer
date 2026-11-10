package ru.tvapk.installer

import org.json.JSONArray

/** Prevents a failed history read from being mistaken for an empty history during writes. */
internal object MaintenanceHistoryWritePolicy {
    fun canAppend(existingValuePresent: Boolean, existingValueValid: Boolean): Boolean =
        !existingValuePresent || existingValueValid

    fun isCorrupt(existingRaw: String?): Boolean {
        if (existingRaw == null) return false
        return runCatching {
            val normalized = existingRaw.trim()
            require(normalized.startsWith("["))
            JSONArray(normalized)
        }.isFailure
    }
}
