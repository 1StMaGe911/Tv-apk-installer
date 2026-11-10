package ru.tvapk.installer

/** Prevents a failed history read from being mistaken for an empty history during writes. */
internal object MaintenanceHistoryWritePolicy {
    fun canAppend(existingValuePresent: Boolean, existingValueValid: Boolean): Boolean =
        !existingValuePresent || existingValueValid
}
