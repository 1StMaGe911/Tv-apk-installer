package ru.tvapk.installer

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CleanYourTvUpdater {
    private const val API = "https://api.github.com/repos/1StMaGe911/Tv-apk-installer/releases/latest"
    private const val APK = "app-full-debug.apk"
    private const val PREFS = "clean_your_tv_updates"
    private const val DOWNLOAD_ID = "download_id"
    private const val DOWNLOADED_FROM_VERSION = "downloaded_from_version"
    private const val LAST_SEEN_VERSION = "last_seen_version"

    /** Checks GitHub and starts downloading the newest full APK. Installation is always user-initiated. */
    fun download(context: Context) {
        toast(context, "Проверяем обновление…")
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "Clean-Your-TV-Updater")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val json = try {
                    check(c.responseCode == 200) { "GitHub HTTP ${c.responseCode}" }
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally { c.disconnect() }
                val tag = json.optString("tag_name")
                require(Regex("^v?\\d+(?:\\.\\d+){1,3}$").matches(tag)) { "Некорректная версия" }
                val assets = json.optJSONArray("assets") ?: error("В релизе нет APK")
                val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == APK } ?: error("APK не найден")
                val url = asset.optString("browser_download_url")
                val uri = Uri.parse(url)
                require(uri.scheme == "https" && uri.host == "github.com" &&
                    uri.path?.startsWith("/1StMaGe911/Tv-apk-installer/releases/download/") == true) {
                    "Недоверенная ссылка"
                }
                val request = DownloadManager.Request(uri)
                    .setTitle("Clean Your TV $tag")
                    .setDescription("Загрузка обновления")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "clean-your-tv-update.apk")
                val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(DOWNLOAD_ID, id)
                    .putLong(DOWNLOADED_FROM_VERSION, appVersionCode(context))
                    .apply()
                toast(context, "Загрузка Clean Your TV $tag началась. После завершения нажмите «Установить обновление».")
            } catch (e: Exception) {
                toast(context, "Ошибка обновления: ${e.message ?: "не удалось загрузить APK"}")
            }
        }.start()
    }


    /** Returns download percentage while Android DownloadManager is receiving the APK. */
    fun downloadProgress(context: Context): Int? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(DOWNLOAD_ID, -1L)
        if (id < 0L) return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
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

    /**
     * Runs on app startup. If the installed version is newer than the version
     * that downloaded the APK, remove the DownloadManager entry and APK file.
     */
    fun cleanupIfUpdated(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentVersion = appVersionCode(context)
        val lastSeenVersion = preferences.getLong(LAST_SEEN_VERSION, -1L)

        // On first launch after installing this cleanup-capable build, remove leftovers
        // from older builds. On later launches, clean only after the installed version changes.
        val shouldCleanup = lastSeenVersion < 0L || currentVersion > lastSeenVersion
        if (!shouldCleanup) return

        val id = preferences.getLong(DOWNLOAD_ID, -1L)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (id >= 0L) manager.remove(id)

        // DownloadManager may have created suffixed copies when the same destination
        // filename was downloaded more than once. Remove every APK owned by this updater.
        val downloadDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val deletedCount = downloadDirectory?.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith("clean-your-tv-update", ignoreCase = true) &&
                    file.name.endsWith(".apk", ignoreCase = true)
            }
            ?.count { it.delete() } ?: 0

        preferences.edit()
            .remove(DOWNLOAD_ID)
            .remove(DOWNLOADED_FROM_VERSION)
            .putLong(LAST_SEEN_VERSION, currentVersion)
            .apply()

        if (deletedCount > 0) {
            toast(context, "Старые файлы обновления удалены: $deletedCount")
        }
    }

    private fun appVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    /**
     * Called only after Android has replaced this application package.
     * Removing the DownloadManager row also removes its downloaded APK file.
     */
    fun onPackageReplaced(context: Context, action: String?) {
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = preferences.getLong(DOWNLOAD_ID, -1L)
        if (id < 0L) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return
        val installedUpdate = try {
            cursor.moveToFirst() &&
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                DownloadManager.STATUS_SUCCESSFUL
        } catch (_: Exception) {
            false
        } finally {
            cursor.close()
        }

        if (installedUpdate) {
            manager.remove(id)
            preferences.edit().remove(DOWNLOAD_ID).apply()
            toast(context, "Обновление установлено. Загруженный APK удалён.")
        }
    }

    /** Opens Android's package installer only after DownloadManager confirms a complete download. */
    fun installLatest(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(DOWNLOAD_ID, -1L)
        if (id < 0L) {
            toast(context, "Сначала нажмите «Скачать обновление»")
            return
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        val cursor = dm.query(query)
        val status = try {
            if (!cursor.moveToFirst()) DownloadManager.STATUS_FAILED
            else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        } finally { cursor.close() }
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
        val uri = dm.getUriForDownloadedFile(id) ?: run {
            toast(context, "Не удалось получить загруженный APK")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) }
        catch (e: Exception) { toast(context, "Не удалось открыть установщик: ${e.message}") }
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
            // Remove notifications created by older builds; their PendingIntent targeted the legacy installer.
            androidx.core.app.NotificationManagerCompat.from(context).cancel(1001)
        }
        CleanYourTvUpdater.onPackageReplaced(context, intent.action)
    }
}
