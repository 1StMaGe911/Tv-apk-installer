package ru.tvapk.installer

/** Keeps the report headline honest when its durable history entry could not be saved. */
internal object MaintenanceReportStatus {
    fun title(historySaved: Boolean): String =
        if (historySaved) {
            "АВТОМАТИЧЕСКАЯ ПРОВЕРКА ЗАВЕРШЕНА"
        } else {
            "АВТОМАТИЧЕСКАЯ ПРОВЕРКА ЗАВЕРШЕНА С ПРЕДУПРЕЖДЕНИЕМ"
        }
}
