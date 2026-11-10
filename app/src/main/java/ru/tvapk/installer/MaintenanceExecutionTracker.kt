package ru.tvapk.installer

/**
 * Tracks the user-approved maintenance execution after the diagnostic plan is ready.
 * The same phases cover cache-only and package maintenance paths.
 */
internal enum class MaintenanceExecutionStage(val label: String) {
    PREPARING("Повторно проверяем телевизор и готовим обслуживание…"),
    APPLYING("Выполняем подтверждённые действия обслуживания…"),
    VERIFYING("Проверяем фактическое состояние телевизора…"),
    REPORTING("Сохраняем итоговый отчёт обслуживания…"),
    COMPLETE("Обслуживание завершено; результат проверен"),
    COMPLETE_WITH_WARNING("Обслуживание завершено с предупреждением")
}

/** Enforces a single forward-only execution sequence with an explicit report outcome. */
internal class MaintenanceExecutionTracker {
    private var currentIndex = -1

    @Synchronized
    fun advance(stage: MaintenanceExecutionStage): String {
        require(stage.ordinal <= MaintenanceExecutionStage.REPORTING.ordinal) {
            "Терминальный этап нельзя передать через advance"
        }
        require(stage.ordinal == currentIndex + 1) {
            "Недопустимый переход обслуживания: ${stage.name}"
        }
        currentIndex = stage.ordinal
        return stage.label
    }

    @Synchronized
    fun finish(historySaved: Boolean): String {
        require(currentIndex == MaintenanceExecutionStage.REPORTING.ordinal) {
            "Итоговый отчёт нельзя завершить до этапа сохранения"
        }
        currentIndex = if (historySaved) {
            MaintenanceExecutionStage.COMPLETE.ordinal
        } else {
            MaintenanceExecutionStage.COMPLETE_WITH_WARNING.ordinal
        }
        return MaintenanceExecutionStage.values()[currentIndex].label
    }

    @Synchronized
    fun isComplete(): Boolean =
        currentIndex == MaintenanceExecutionStage.COMPLETE.ordinal ||
            currentIndex == MaintenanceExecutionStage.COMPLETE_WITH_WARNING.ordinal
}
