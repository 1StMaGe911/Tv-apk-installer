package ru.tvapk.installer

/** Decides whether a release should produce a user-visible update notification. */
internal object UpdateCheckNotificationPolicy {
    fun shouldNotify(
        latestVersion: String,
        installedVersion: String,
        lastNotifiedVersion: String?
    ): Boolean {
        return AppVersionComparator.compare(latestVersion, installedVersion) > 0 &&
            latestVersion != lastNotifiedVersion
    }
}
