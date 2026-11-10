package ru.tvapk.installer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckNotificationPolicyTest {
    @Test
    fun notifiesWhenLatestReleaseIsNewerAndWasNotNotified() {
        assertTrue(
            UpdateCheckNotificationPolicy.shouldNotify(
                latestVersion = "0.9.131",
                installedVersion = "0.9.130",
                lastNotifiedVersion = null
            )
        )
    }

    @Test
    fun doesNotNotifyWhenLatestVersionWasAlreadyNotified() {
        assertFalse(
            UpdateCheckNotificationPolicy.shouldNotify(
                latestVersion = "0.9.131",
                installedVersion = "0.9.130",
                lastNotifiedVersion = "0.9.131"
            )
        )
    }

    @Test
    fun doesNotNotifyForSameOrOlderInstalledVersion() {
        assertFalse(
            UpdateCheckNotificationPolicy.shouldNotify(
                latestVersion = "0.9.130",
                installedVersion = "0.9.130",
                lastNotifiedVersion = null
            )
        )
        assertFalse(
            UpdateCheckNotificationPolicy.shouldNotify(
                latestVersion = "0.9.129",
                installedVersion = "0.9.130",
                lastNotifiedVersion = null
            )
        )
    }

    @Test
    fun comparesVersionComponentsNumerically() {
        assertTrue(
            UpdateCheckNotificationPolicy.shouldNotify(
                latestVersion = "0.9.100",
                installedVersion = "0.9.99",
                lastNotifiedVersion = null
            )
        )
    }
}
