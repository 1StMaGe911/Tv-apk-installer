package ru.tvapk.installer

/** Describes how the periodic update worker should handle an HTTP response. */
internal enum class UpdateCheckHttpAction {
    CONTINUE,
    NO_RELEASE,
    RETRY,
    FAIL
}

internal object UpdateCheckHttpPolicy {
    fun actionFor(responseCode: Int): UpdateCheckHttpAction = when {
        responseCode == 200 -> UpdateCheckHttpAction.CONTINUE
        responseCode == 404 -> UpdateCheckHttpAction.NO_RELEASE
        responseCode == 403 || responseCode == 408 || responseCode == 429 ||
            responseCode in 500..599 -> UpdateCheckHttpAction.RETRY
        else -> UpdateCheckHttpAction.FAIL
    }
}
