package ru.tvapk.installer

/**
 * Enforces the visible order of the one-tap maintenance workflow.
 * It is deliberately side-effect-free: callers decide how to publish each label.
 */
internal enum class MaintenanceStage(val label: String) {
    CONNECTING("Подключаемся к телевизору по ADB…"),
    IDENTIFYING("Определяем модель и совместимость…"),
    DIAGNOSTICS("Считываем диагностику и ресурсы…"),
    CACHE_POLICY("Проверяем разрешение на безопасную очистку кэша…"),
    APP_SCAN("Проверяем установленные приложения и профиль устройства…"),
    PLAN("Формируем безопасный план обслуживания…"),
    REPORT("Сохраняем отчёт и историю обслуживания…"),
    REVIEW_READY("План готов; ожидается подтверждение действий")
}

/** Rejects repeated, skipped, or out-of-order progress updates. */
internal class MaintenanceProgressTracker {
    private var currentIndex = -1

    @Synchronized
    fun advance(stage: MaintenanceStage): String {
        require(stage.ordinal == currentIndex + 1) {
            "Недопустимый переход обслуживания: ${stage.name}"
        }
        currentIndex = stage.ordinal
        return stage.label
    }

    @Synchronized
    fun isComplete(): Boolean = currentIndex == MaintenanceStage.REVIEW_READY.ordinal
}
