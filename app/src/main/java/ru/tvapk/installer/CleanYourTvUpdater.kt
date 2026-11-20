package ru.tvapk.installer

import android.app.DownloadManager
import android.content.BroadcastReceiver
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

    fun download(context: Context) {
        Toast.makeText(context, "Проверяем обновление…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "Clean-Your-TV-Updater")
                val json = try {
                    check(c.responseCode == 200) { "GitHub HTTP ${c.responseCode}" }
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally { c.disconnect() }
                val tag = json.optString("tag_name")
                require(Regex("^v?\\d+(?:\\.\\d+){1,3}$").matches(tag)) { "Некорректная версия" }
                val assets = json.optJSONArray("assets") ?: error("В релизе нет APK")
                val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == APK }
                    ?.optString("browser_download_url") ?: error("APK не найден")
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
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                toast(context, "Загружаем Clean Your TV $tag")
            } catch (e: Exception) {
                toast(context, "Ошибка обновления: ${e.message ?: "не удалось загрузить APK"}")
            }
        }.start()
    }

    internal fun install(context: Context, id: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
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

class CleanYourTvUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id >= 0) CleanYourTvUpdater.install(context, id)
    }
}
