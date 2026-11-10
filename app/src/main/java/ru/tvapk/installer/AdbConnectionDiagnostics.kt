package ru.tvapk.installer

internal object AdbConnectionDiagnostics {
    enum class Kind { AUTHORIZATION, REFUSED, TIMEOUT, NETWORK_UNREACHABLE, INVALID_ENDPOINT, UNKNOWN }

    data class Diagnosis(
        val kind: Kind,
        val retryable: Boolean,
        val guidance: String
    )

    fun classify(host: String, port: Int, errorText: String): Diagnosis {
        val message = errorText.lowercase()
        if (host.isBlank() || port !in 1..65535 || message.contains("unknownhost") ||
            message.contains("unknown host") || message.contains("invalid hostname")) {
            return Diagnosis(Kind.INVALID_ENDPOINT, false,
                "Проверь IP-адрес телевизора и порт ADB (обычно 5555). Убедись, что оба устройства в одной сети.")
        }
        if (listOf("unauthorized", "not authorized", "authentication", "adb key", "rsa", "auth failed")
                .any { message.contains(it) }) {
            return Diagnosis(Kind.AUTHORIZATION, false,
                "На экране ТВ подтверди запрос ADB/RSA. Если запрос не появляется, отзови старые разрешения отладки и подключись заново.")
        }
        if (listOf("connection refused", "econnrefused", "actively refused").any { message.contains(it) }) {
            return Diagnosis(Kind.REFUSED, true,
                "Телевизор отклонил соединение. Включи сетевую ADB-отладку и проверь порт; после включения подожди несколько секунд.")
        }
        if (listOf("timeout", "timed out", "sockettimeoutexception").any { message.contains(it) }) {
            return Diagnosis(Kind.TIMEOUT, true,
                "Телевизор не ответил вовремя. Проверь Wi-Fi, расстояние до роутера и отсутствие изоляции клиентов.")
        }
        if (listOf("no route to host", "network is unreachable", "host is unreachable", "enetunreach")
                .any { message.contains(it) }) {
            return Diagnosis(Kind.NETWORK_UNREACHABLE, true,
                "Нет сетевого маршрута до ТВ. Проверь IP, одну Wi-Fi/LAN-сеть и настройки гостевой сети.")
        }
        return Diagnosis(Kind.UNKNOWN, false,
            "Проверь, что ТВ включён, ADB-отладка активна, IP и порт верны. Подробности исходной ошибки приведены выше.")
    }

    fun format(host: String, port: Int, errorText: String): String {
        val diagnosis = classify(host, port, errorText)
        return "Причина: ${diagnosis.kind}. ${diagnosis.guidance} Исходная ошибка: $errorText"
    }
    /** Exponential delay for retry number 1..N, capped to avoid long connection stalls. */
    internal fun retryDelayForAttempt(baseDelayMillis: Long, retryNumber: Int): Long {
        require(baseDelayMillis >= 0L) { "retry delay must not be negative" }
        require(retryNumber >= 1) { "retry number must be positive" }
        var delay = baseDelayMillis.coerceAtMost(10_000L)
        repeat(retryNumber - 1) {
            delay = if (delay > 5_000L) 10_000L else delay * 2L
        }
        return delay.coerceAtMost(10_000L)
    }

    /** Retries only connection establishment; callers must execute device actions afterwards. */
    internal fun <T : Any> retryConnection(
        maxAttempts: Int = 3,
        retryDelayMillis: Long = 1500L,
        connect: () -> T,
        isRetryable: (Exception) -> Boolean,
        pause: (Long) -> Unit = { Thread.sleep(it) }
    ): T {
        require(maxAttempts in 1..5) { "maxAttempts must be between 1 and 5" }
        require(retryDelayMillis >= 0L) { "retryDelayMillis must not be negative" }
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return connect()
            } catch (error: Exception) {
                lastError = error
                if (attempt == maxAttempts - 1 || !isRetryable(error)) throw error
                pause(retryDelayForAttempt(retryDelayMillis, attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Connection failed without an error")
    }

}
