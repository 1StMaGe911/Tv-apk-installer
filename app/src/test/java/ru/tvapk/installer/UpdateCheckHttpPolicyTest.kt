package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckHttpPolicyTest {
    @Test
    fun acceptsSuccessfulResponseAndTreatsMissingReleaseAsNoUpdate() {
        assertEquals(UpdateCheckHttpAction.CONTINUE, UpdateCheckHttpPolicy.actionFor(200))
        assertEquals(UpdateCheckHttpAction.NO_RELEASE, UpdateCheckHttpPolicy.actionFor(404))
    }

    @Test
    fun retriesRateLimitsTimeoutsAndServerErrors() {
        listOf(403, 408, 429, 500, 502, 503, 599).forEach { status ->
            assertEquals(
                "HTTP $status should be retried",
                UpdateCheckHttpAction.RETRY,
                UpdateCheckHttpPolicy.actionFor(status)
            )
        }
    }

    @Test
    fun doesNotRetryPermanentClientErrorsOrUnexpectedStatuses() {
        listOf(201, 301, 400, 401, 405, 410, 422, 499, 600).forEach { status ->
            assertEquals(
                "HTTP $status should fail without retry",
                UpdateCheckHttpAction.FAIL,
                UpdateCheckHttpPolicy.actionFor(status)
            )
        }
    }
}
