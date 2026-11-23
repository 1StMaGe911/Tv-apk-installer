package ru.tvapk.installer

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException
import java.security.MessageDigest

object CleanYourTvUpdater {
    private const val API = "https://api.github.com/repos/1StMaGe911/Tv-apk-installer/releases/latest"
    private const val APK = "app-full-debug.apk"
    private const val PREFS = "clean_your_tv_updates"
    private const val DOWNLOAD_ID = "download_id"
    private const val DOWNLOADED_FROM_VERSION = "downloaded_from_version"
    private const val LAST_SEEN_VERSION = "last_seen_version"
    private const val EXPECTED_SHA256 = "expected_sha256"
    private const val EXPECTED_SIZE = "expected_size"
    private const val EXPECTED_TAG = "expected_tag"

    // Prevent simultaneous notification and Settings taps from creating competing downloads.
    private val downloadLock = Any()
    private var downloadInProgress = false

    private fun updateFailureMessage(error: Exception): String = when (error) {
        is SocketTimeoutException -> "Истекло время ожидания ответа GitHub. Проверь подключение к интернету."
        is UnknownHostException -> "Не удалось связаться с GitHub. Проверь подключение к интернету."
        is SSLException -> "Не удалось безопасно подключиться к GitHub."
        is IOException -> "Ошибка сети при загрузке обновления: ${error.message ?: "нет подробностей"}"
        else -> error.message ?: "не удалось загрузить APK"
    }

    /** Checks GitHub metadata and downloads only a newer, verifiable full APK. */
    fun download(context: Context) {
        synchronized(downloadLock) {
            if (downloadInProgress) {
                toast(context, "Проверка или загрузка обновления уже выполняется.")
                return
            }
            downloadInProgress = true
        }
        toast(context, "Проверяем обновление…")
        Thread {
            try {
                val connection = URL(API).openConnection() as HttpURLConnection
                val json = try {
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("User-Agent", "Clean-Your-TV-Updater")
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "GitHub HTTP ${connection.responseCode}"
                    }
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }

                val tag = UpdateCheckReleasePolicy.normalizeTag(json.optString("tag_name"))
                    ?: error("Некорректная версия релиза")
                val installedVersion = appVersionName(context)
                if (AppVersionComparator.compare(tag, installedVersion) <= 0) {
                    toast(context, "Установлена актуальная версия $installedVersion")
                    return@Thread
                }

                val assets = json.optJSONArray("assets") ?: error("В релизе нет APK")
                val asset = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == APK }
                    ?: error("В релизе $tag не найден $APK")

                val url = asset.optString("browser_download_url")
                require(UpdateArtifactPolicy.isAllowedDownloadUrl(url)) {
                    "Ссылка на APK не прошла проверку безопасности"
                }
                val size = asset.optLong("size", 0L)
                require(UpdateArtifactPolicy.isValidSize(size)) {
                    "Некорректный размер APK в GitHub"
                }

                val digest = UpdateArtifactPolicy.sha256FromRelease(
                    asset.optString("digest"),
                    json.optString("body")
                ) ?: error("GitHub не предоставил SHA-256 APK")

                val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val previousId = preferences.getLong(DOWNLOAD_ID, -1L)
                if (previousId >= 0L) manager.remove(previousId)

                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle("Clean Your TV $tag")
                    .setDescription("Загрузка обновления")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(
                        context,
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        "clean-your-tv-update.apk"
                    )
                val id = manager.enqueue(request)
                preferences.edit()
                    .putLong(DOWNLOAD_ID, id)
                    .putLong(DOWNLOADED_FROM_VERSION, appVersionCode(context))
                    .putString(EXPECTED_SHA256, digest.lowercase())
                    .putLong(EXPECTED_SIZE, size)
                    .putString(EXPECTED_TAG, tag)
                    .apply()
                toast(context, "Загрузка Clean Your TV $tag началась. После завершения нажмите «Установить обновление».")
            } catch (e: Exception) {
                toast(context, "Ошибка обновления: ${updateFailureMessage(e)}")
            } finally {
                synchronized(downloadLock) { downloadInProgress = false }
            }
        }.start()
    }

    /** Returns download percentage while Android DownloadManager is receiving the APK. */
    fun downloadProgress(context: Context): Int? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(DOWNLOAD_ID, -1L)
        if (id < 0L) return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        return try {
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) return 100
            if (status != DownloadManager.STATUS_RUNNING && status != DownloadManager.STATUS_PENDING &&
                status != DownloadManager.STATUS_PAUSED) return null
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            if (total <= 0L) 0 else ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
        } catch (_: Exception) {
            null
        } finally {
            cursor.close()
        }
    }

    /** Cleans stale updater files after a newer app version has actually been installed. */
    fun cleanupIfUpdated(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentVersion = appVersionCode(context)
        val lastSeenVersion = preferences.getLong(LAST_SEEN_VERSION, -1L)
        if (lastSeenVersion >= 0L && currentVersion <= lastSeenVersion) return

        val id = preferences.getLong(DOWNLOAD_ID, -1L)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (id >= 0L) manager.remove(id)

        val downloadDirectory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val deletedCount = downloadDirectory?.listFiles()
            ?.filter { file ->
                file.isFile && file.name.startsWith("clean-your-tv-update", true) &&
                    file.name.endsWith(".apk", true)
            }?.count { it.delete() } ?: 0

        File(context.cacheDir, "updates/clean-your-tv-update.apk").delete()
        preferences.edit()
            .remove(DOWNLOAD_ID)
            .remove(DOWNLOADED_FROM_VERSION)
            .remove(EXPECTED_SHA256)
            .remove(EXPECTED_SIZE)
            .remove(EXPECTED_TAG)
            .putLong(LAST_SEEN_VERSION, currentVersion)
            .apply()
        if (deletedCount > 0) toast(context, "Старые файлы обновления удалены: $deletedCount")
    }

    private fun appVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    private fun appVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    /** Clears the DownloadManager record only when the downloaded newer package was installed. */
    fun onPackageReplaced(context: Context, action: String?) {
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = preferences.getLong(DOWNLOAD_ID, -1L)
        val sourceVersion = preferences.getLong(DOWNLOADED_FROM_VERSION, -1L)
        if (id < 0L || sourceVersion < 0L || appVersionCode(context) <= sourceVersion) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return
        val completed = try {
            cursor.moveToFirst() &&
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                DownloadManager.STATUS_SUCCESSFUL
        } catch (_: Exception) {
            false
        } finally {
            cursor.close()
        }
        if (completed) {
            manager.remove(id)
            File(context.cacheDir, "updates/clean-your-tv-update.apk").delete()
            preferences.edit()
                .remove(DOWNLOAD_ID)
                .remove(DOWNLOADED_FROM_VERSION)
                .remove(EXPECTED_SHA256)
                .remove(EXPECTED_SIZE)
                .remove(EXPECTED_TAG)
                .putLong(LAST_SEEN_VERSION, appVersionCode(context))
                .apply()
            toast(context, "Обновление установлено. Временный APK удалён.")
        }
    }

    /** Verifies downloaded bytes, package name, signing certificate and version before opening installer. */
    fun installLatest(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = preferences.getLong(DOWNLOAD_ID, -1L)
        if (id < 0L) {
            toast(context, "Сначала нажмите «Скачать обновление»")
            return
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id))
        val status = try {
            if (cursor == null || !cursor.moveToFirst()) DownloadManager.STATUS_FAILED
            else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        } finally {
            cursor?.close()
        }
        when (status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                toast(context, "Загрузка ещё не завершена. Дождитесь её окончания.")
                return
            }
            DownloadManager.STATUS_SUCCESSFUL -> Unit
            else -> {
                toast(context, "Загрузка не удалась. Нажмите «Скачать обновление» ещё раз.")
                return
            }
        }

        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            toast(context, "Разрешите установку приложений для Clean Your TV и вернитесь сюда.")
            return
        }

        Thread {
            try {
                val expectedSha = preferences.getString(EXPECTED_SHA256, null)
                    ?: error("Нет контрольной суммы. Скачайте обновление заново.")
                val expectedSize = preferences.getLong(EXPECTED_SIZE, -1L)
                val uri = manager.getUriForDownloadedFile(id) ?: error("Не удалось получить загруженный APK")
                val directory = File(context.cacheDir, "updates")
                if (!directory.exists() && !directory.mkdirs()) error("Не удалось подготовить папку обновления")
                val apk = File(directory, "clean-your-tv-update.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                context.contentResolver.openInputStream(uri)?.use { input ->
                    apk.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= UpdateArtifactPolicy.MAX_APK_BYTES) { "APK превышает допустимый размер" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Не удалось прочитать загруженный APK")

                require(UpdateArtifactPolicy.matchesSize(total, expectedSize) && apk.length() == expectedSize) {
                    "Размер APK не совпадает с GitHub. Установка отменена."
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                require(UpdateArtifactPolicy.matchesSha256(actualSha, expectedSha)) {
                    "SHA-256 APK не совпадает с GitHub. Установка отменена."
                }

                @Suppress("DEPRECATION")
                val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else PackageManager.GET_SIGNATURES
                val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
                    ?: error("Загруженный файл не является корректным APK")
                @Suppress("DEPRECATION")
                val archiveSigners = if (Build.VERSION.SDK_INT >= 28) {
                    archive.signingInfo?.apkContentsSigners?.toList().orEmpty()
                } else archive.signatures?.toList().orEmpty()
                require(archiveSigners.size == 1) { "У APK должен быть ровно один сертификат подписи" }
                val archiveSigner = MessageDigest.getInstance("SHA-256")
                    .digest(archiveSigners.single().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val installedSigner = installedSignerSha256(context)
                @Suppress("DEPRECATION")
                val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode
                    else archive.versionCode.toLong()
                val installedVersion = appVersionCode(context)
                require(UpdateArtifactPolicy.isTrustedUpdate(
                    archive.packageName,
                    context.packageName,
                    archiveSigner,
                    installedSigner,
                    archiveVersion,
                    installedVersion
                )) {
                    "APK не прошёл проверку пакета, подписи или версии. Установка заблокирована."
                }

                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            } catch (e: Exception) {
                toast(context, "Ошибка проверки обновления: ${e.message ?: "установка отменена"}")
            }
        }.start()
    }

    private fun installedSignerSha256(context: Context): String {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES
            )
        }
        @Suppress("DEPRECATION")
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else info.signatures?.toList().orEmpty()
        require(signers.size == 1) { "Не удалось однозначно определить подпись установленного приложения" }
        return MessageDigest.getInstance("SHA-256")
            .digest(signers.single().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun toast(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

/** Handles the package-replaced broadcast after Android has finished installing our update. */
class CleanYourTvUpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(1001)
        }
        CleanYourTvUpdater.onPackageReplaced(context, intent.action)
    }
}
