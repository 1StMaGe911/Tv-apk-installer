package ru.tvapk.installer

/**
 * Validates persisted recovery records without dropping malformed or duplicate entries.
 * Callers must leave the original persisted journal untouched when this rejects data.
 */
internal object ManualChangeJournalPolicy {
    fun <T> validateEntries(
        entries: List<T>,
        key: (T) -> String,
        isValid: (T) -> Boolean,
        maxEntries: Int = 500
    ): List<T> {
        require(maxEntries >= 0) { "Недопустимый лимит записей журнала" }
        require(entries.size <= maxEntries) { "Журнал превышает допустимый размер" }
        require(entries.all(isValid)) { "Журнал содержит некорректную запись" }
        val keys = entries.map(key)
        require(keys.all { it.isNotBlank() }) { "Журнал содержит пустой идентификатор записи" }
        require(keys.distinct().size == keys.size) { "Журнал содержит повторяющиеся записи" }
        return entries
    }
}
