package ru.tvapk.installer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val UPDATE_WORK_NAME = "tv_apk_installer_periodic_update_check"
private const val UPDATE_CHANNEL_ID = "app_updates"
private const val MAX_RELEASE_METADATA_BYTES = 256 * 1024

fun schedulePeriodicUpdateCheck(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UPDATE_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val connection = URL(
                "https://api.github.com/repos/1StMaGe911/Tv-apk-installer/releases/latest"
            ).openConnection() as HttpURLConnection

            val json = try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "TV-APK-Installer")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != 200) return Result.retry()
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_RELEASE_METADATA_BYTES) {
                    throw IOException("GitHub release metadata is unexpectedly large")
                }
                val body = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_RELEASE_METADATA_BYTES) {
                            throw IOException("GitHub release metadata exceeds size limit")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                JSONObject(body)
            } finally {
                connection.disconnect()
            }

            val rawTag = json.optString("tag_name")
            if (!rawTag.matches(Regex("^v?\\d+(?:\\.\\d+){1,3}$"))) {
                throw IOException("GitHub returned an invalid release version")
            }
            val latestTag = rawTag.removePrefix("v")
            val installedVersion = appVersionName()

            if (AppVersionComparator.compare(latestTag, installedVersion) > 0) {
                val preferences = applicationContext.getSharedPreferences("tv_installer", Context.MODE_PRIVATE)
                val alreadyNotified = preferences.getString("last_notified_update_version", null)
                if (alreadyNotified != latestTag) {
                    showUpdateNotification(latestTag)
                    preferences.edit().putString("last_notified_update_version", latestTag).apply()
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun appVersionName(): String =
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0).versionName ?: "0.0.0"

    private fun showUpdateNotification(version: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    UPDATE_CHANNEL_ID,
                    "Обновления приложения",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Уведомления о новых версиях TV APK Installer"
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            applicationContext,
            1001,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, UPDATE_CHANNEL_ID)
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentTitle("Доступно обновление TV APK Installer")
            .setContentText("Вышла версия $version. Открой приложение, чтобы скачать обновление.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Вышла версия $version. Открой TV APK Installer и проверь обновления."
                )
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (Build.VERSION.SDK_INT < 33 ||
            applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(applicationContext).notify(1001, notification)
        }
    }
}
