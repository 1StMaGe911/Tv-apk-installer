
package ru.tvapk.installer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.app.PendingIntent
import android.net.Uri as AndroidUri
import android.provider.Settings
import android.provider.OpenableColumns
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import dadb.AdbKeyPair
import dadb.Dadb
import dadb.InstallResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val APP_VERSION = "0.9.33"
private const val RELEASES_URL =
    "https://api.github.com/repos/1StMaGe911/Tv-apk-installer/releases/latest"

private val Bg = Color(0xFF080808)
private val Panel = Color(0xFF17130C)
private val Accent = Color(0xFFD6A84F)
private val GoldLight = Color(0xFFF4D58A)
private val TextMain = Color(0xFFF7F1E5)
private val TextMuted = Color(0xFFBDB4A3)

data class TvDevice(
    val name: String,
    val ip: String,
    val port: Int
)

private data class CleanupJournal(
    val deviceIdentity: String,
    val host: String,
    val port: Int,
    val plannedPackages: List<String>,
    val completedPackages: List<String>,
    val createdAt: Long,
    val inFlightPackage: String? = null,
    val baselineDisabledPackages: List<String> = emptyList(),
    val schemaVersion: Int = 2,
    val restoredPackages: List<String> = emptyList()
)

private fun loadCleanupJournal(context: Context): CleanupJournal? {
    return try {
        val raw = prefs(context).getString("cleanup_operation_journal", null) ?: return null
        val json = JSONObject(raw)
        val planned = json.optJSONArray("planned") ?: JSONArray()
        val completed = json.optJSONArray("completed") ?: JSONArray()
        CleanupJournal(
            deviceIdentity = json.optString("deviceIdentity"),
            host = json.optString("host"),
            port = json.optInt("port", 5555),
            plannedPackages = (0 until planned.length()).map { planned.optString(it) }.filter { it.isNotBlank() },
            completedPackages = (0 until completed.length()).map { completed.optString(it) }.filter { it.isNotBlank() },
            createdAt = json.optLong("createdAt", 0L),
            inFlightPackage = json.optString("inFlightPackage").takeIf { it.isNotBlank() && it != "null" },
            baselineDisabledPackages = (json.optJSONArray("baselineDisabled") ?: JSONArray()).let { values ->
                (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }
            },
            schemaVersion = json.optInt("schemaVersion", 1),
            restoredPackages = (json.optJSONArray("restored") ?: JSONArray()).let { values ->
                (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }
            }
        ).takeIf { it.deviceIdentity.isNotBlank() && it.plannedPackages.isNotEmpty() }
    } catch (_: Exception) { null }
}

private fun saveCleanupJournal(context: Context, journal: CleanupJournal) {
    val json = JSONObject()
        .put("deviceIdentity", journal.deviceIdentity)
        .put("host", journal.host)
        .put("port", journal.port)
        .put("planned", JSONArray(journal.plannedPackages))
        .put("completed", JSONArray(journal.completedPackages))
        .put("createdAt", journal.createdAt)
        .put("inFlightPackage", journal.inFlightPackage)
        .put("baselineDisabled", JSONArray(journal.baselineDisabledPackages))
        .put("schemaVersion", journal.schemaVersion)
        .put("restored", JSONArray(journal.restoredPackages))
    prefs(context).edit().putString("cleanup_operation_journal", json.toString()).commit()
}

private fun clearCleanupJournal(context: Context) {
    prefs(context).edit().remove("cleanup_operation_journal").commit()
}
private data class CleanupHistoryEntry(
    val createdAt: Long,
    val deviceIdentity: String,
    val plannedCount: Int,
    val changedPackages: List<String>,
    val baselineDisabledCount: Int,
    val outcome: String
)

private fun loadCleanupHistory(context: Context): List<CleanupHistoryEntry> {
    return try {
        val raw = prefs(context).getString("cleanup_operation_history", null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val changed = item.optJSONArray("changed") ?: JSONArray()
            CleanupHistoryEntry(
                createdAt = item.optLong("createdAt"),
                deviceIdentity = item.optString("deviceIdentity"),
                plannedCount = item.optInt("plannedCount"),
                changedPackages = (0 until changed.length()).map { changed.optString(it) }.filter { it.isNotBlank() },
                baselineDisabledCount = item.optInt("baselineDisabledCount"),
                outcome = item.optString("outcome", "Завершено")
            )
        }.sortedByDescending { it.createdAt }
    } catch (_: Exception) { emptyList() }
}

private fun appendCleanupHistory(context: Context, journal: CleanupJournal, outcome: String, changed: List<String>) {
    val history = JSONArray()
    val existing = loadCleanupHistory(context).take(29)
    (existing + CleanupHistoryEntry(
        createdAt = journal.createdAt,
        deviceIdentity = journal.deviceIdentity,
        plannedCount = journal.plannedPackages.size,
        changedPackages = changed.distinct(),
        baselineDisabledCount = journal.baselineDisabledPackages.size,
        outcome = outcome
    )).forEach { entry ->
        history.put(JSONObject()
            .put("createdAt", entry.createdAt)
            .put("deviceIdentity", entry.deviceIdentity)
            .put("plannedCount", entry.plannedCount)
            .put("changed", JSONArray(entry.changedPackages))
            .put("baselineDisabledCount", entry.baselineDisabledCount)
            .put("outcome", entry.outcome))
    }
    prefs(context).edit().putString("cleanup_operation_history", history.toString()).commit()
}


class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        schedulePeriodicUpdateCheck(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            TvInstallerApp()
        }
    }
}

private fun prefs(context: Context) =
    context.getSharedPreferences("tv_installer", Context.MODE_PRIVATE)

private fun exportAppSettings(context: Context): String {
    val root = JSONObject()
    prefs(context).all.forEach { (key, value) ->
        when (value) {
            is String -> root.put(key, JSONObject().put("type", "string").put("value", value))
            is Boolean -> root.put(key, JSONObject().put("type", "boolean").put("value", value))
            is Int -> root.put(key, JSONObject().put("type", "int").put("value", value))
            is Long -> root.put(key, JSONObject().put("type", "long").put("value", value))
            is Float -> root.put(key, JSONObject().put("type", "float").put("value", value.toDouble()))
            is Set<*> -> {
                val array = JSONArray()
                value.filterIsInstance<String>().forEach { array.put(it) }
                root.put(key, JSONObject().put("type", "string_set").put("value", array))
            }
        }
    }
    return JSONObject().put("format", 1).put("preferences", root).toString(2)
}

private fun importAppSettings(context: Context, jsonText: String) {
    val root = JSONObject(jsonText)
    require(root.optInt("format") == 1) { "Неподдерживаемый формат резервной копии" }
    val values = root.getJSONObject("preferences")
    val editor = prefs(context).edit().clear()
    val keys = values.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val item = values.getJSONObject(key)
        when (item.getString("type")) {
            "string" -> editor.putString(key, item.getString("value"))
            "boolean" -> editor.putBoolean(key, item.getBoolean("value"))
            "int" -> editor.putInt(key, item.getInt("value"))
            "long" -> editor.putLong(key, item.getLong("value"))
            "float" -> editor.putFloat(key, item.getDouble("value").toFloat())
            "string_set" -> {
                val array = item.getJSONArray("value")
                editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
            }
            else -> throw Exception("Неизвестный тип настройки")
        }
    }
    editor.apply()
}

private fun getAdbKey(context: Context): AdbKeyPair {
    val dir = File(context.filesDir, "adb")
    val privateKey = File(dir, "adbkey")
    val publicKey = File(dir, "adbkey.pub")

    if (!privateKey.exists()) {
        dir.mkdirs()
        AdbKeyPair.generate(privateKey, publicKey)
    }

    return AdbKeyPair.read(privateKey, publicKey)
}

private fun errorText(error: Throwable): String {
    return generateSequence(error) { it.cause }
        .joinToString(" → ") {
            "${it.javaClass.simpleName}: ${it.message ?: "без сообщения"}"
        }
}

private fun connectWithRetry(
    context: Context,
    host: String,
    port: Int,
    action: (Dadb) -> String
): String {
    var lastError: Throwable? = null

    repeat(3) { attempt ->
        try {
            return Dadb.create(host, port, getAdbKey(context)).use {
                action(it)
            }
        } catch (e: Exception) {
            lastError = e
            if (attempt < 2) Thread.sleep(1500)
        }
    }

    throw Exception(
        "Не удалось подключиться после 3 попыток: " +
            errorText(lastError ?: Exception("Неизвестная ошибка"))
    )
}

private fun loadDevices(context: Context): List<TvDevice> {
    return try {
        val array = JSONArray(prefs(context).getString("devices", "[]"))
        (0 until array.length()).map {
            val item = array.getJSONObject(it)
            TvDevice(
                item.getString("name"),
                item.getString("ip"),
                item.getInt("port")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveDevices(context: Context, devices: List<TvDevice>) {
    val array = JSONArray()
    devices.forEach {
        array.put(
            JSONObject()
                .put("name", it.name)
                .put("ip", it.ip)
                .put("port", it.port)
        )
    }
    prefs(context).edit().putString("devices", array.toString()).apply()
}

private fun loadHistory(context: Context): List<String> {
    return prefs(context).getString("history", "")
        .orEmpty()
        .lines()
        .filter { it.isNotBlank() }
}

private fun saveHistory(context: Context, history: List<String>) {
    prefs(context).edit()
        .putString("history", history.take(30).joinToString("\n"))
        .apply()
}

private fun compareVersions(first: String, second: String): Int {
    val a = first.removePrefix("v").split(".").map {
        it.toIntOrNull() ?: 0
    }
    val b = second.removePrefix("v").split(".").map {
        it.toIntOrNull() ?: 0
    }

    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

private data class UpdateInfo(
    val status: String,
    val releaseUrl: String?,
    val apkUrl: String?,
    val apkSize: Long,
    val expectedSha256: String? = null
)

private fun checkLatestVersion(): UpdateInfo {
    val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "TV-APK-Installer")
        connection.setRequestProperty("Accept", "application/vnd.github+json")

        if (connection.responseCode != 200) {
            return UpdateInfo("Ошибка проверки: HTTP ${connection.responseCode}", null, null, 0L)
        }

        val json = JSONObject(readBoundedUtf8(connection.inputStream, 1024 * 1024))
        val tag = json.optString("tag_name")
        val releaseUrl = json.optString("html_url").takeIf { it.isNotBlank() }
        if (tag.isBlank()) return UpdateInfo("Версия релиза не найдена", releaseUrl, null, 0L)

        if (compareVersions(tag, APP_VERSION) <= 0) {
            return UpdateInfo("Установлена актуальная версия $APP_VERSION", releaseUrl, null, 0L)
        }

        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        var apkSize = 0L
        var apkSha256: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.equals("app-debug.apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                apkSize = asset.optLong("size", 0L)
                apkSha256 = asset.optString("digest")
                    .removePrefix("sha256:")
                    .lowercase()
                    .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                if (apkUrl != null) break
            }
        }

        return when {
            apkUrl == null -> UpdateInfo("Доступно обновление $tag, но APK ещё не прикреплён к релизу", releaseUrl, null, 0L)
            apkSha256 == null -> UpdateInfo("Доступно обновление $tag, но GitHub не предоставил SHA-256 APK", releaseUrl, null, 0L)
            else -> UpdateInfo("Доступно обновление: $tag", releaseUrl, apkUrl, apkSize, apkSha256)
        }
    } finally {
        connection.disconnect()
    }
}

private fun downloadUpdateApk(context: Context, downloadUrl: String, expectedSha256: String): File {
    require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Некорректная контрольная сумма APK" }
    val directory = File(context.cacheDir, "updates")
    if (!directory.exists() && !directory.mkdirs()) throw Exception("Не удалось создать папку обновлений")
    val apkFile = File(directory, "tv-apk-installer-update.apk")
    val connection = URL(downloadUrl).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TV-APK-Installer")
        if (connection.responseCode != 200) throw Exception("Ошибка загрузки: HTTP ${connection.responseCode}")
        connection.inputStream.use { input ->
            apkFile.outputStream().use { output -> input.copyTo(output) }
        }
        if (!apkFile.exists() || apkFile.length() < 1024L) {
            apkFile.delete()
            throw Exception("Загруженный APK повреждён или пуст")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        apkFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            apkFile.delete()
            throw Exception("SHA-256 APK не совпадает с GitHub. Установка отменена")
        }
        return apkFile
    } finally {
        connection.disconnect()
    }
}


private fun shellText(adb: Dadb, command: String): String {
    val response = adb.shell(command)
    if (response.exitCode != 0) throw Exception(response.output.ifBlank { "Команда завершилась с кодом ${response.exitCode}" })
    return response.output.trim()
}

private fun applyLauncherAppearance(
    adb: Dadb,
    columns: Int,
    rowsBelowDock: Int,
    dockTitle: Boolean,
    dockRow: Boolean,
    animations: Boolean,
    advancedEffects: Boolean,
    appLibrary: Boolean,
    iconOnlyButtons: Boolean
): String {
    val packageName = "com.gathin.tvhome"
    val installed = try {
        shellText(adb, "pm path $packageName").contains("package:")
    } catch (_: Exception) {
        false
    }
    require(installed) { "Clean Your TV Launcher не установлен. Сначала установи модуль в разделе «Модули Clean Your TV»." }

    // Keep the existing Launcher configuration so changing appearance does not
    // erase dock pins, hidden apps, key mappings, wallpaper, or rotation settings.
    val config = try {
        readCurrentLauncherConfig(adb)
    } catch (_: Exception) {
        JSONObject()
    }
    config
        .put("background_color", "#080808")
        .put("columns", columns.coerceIn(4, 8))
        .put("rows_below_dock", rowsBelowDock.coerceIn(0, 3))
        .put("dock_title", dockTitle)
        .put("dock_row", dockRow)
        .put("animations", animations)
        .put("advanced_effects", animations && advancedEffects)
        .put("reduced_motion", !animations)
        .put("app_library", appLibrary)
        .put("icon_only_buttons", iconOnlyButtons)
        .put("show_network_pill", true)
        .put("show_inputs_pill", true)

    val encoded = Base64.encodeToString(config.toString(2).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    shellText(adb, "printf '%s' '$encoded' | base64 -d > /data/local/tmp/tvapk-launcher-config.json")
    val directory = "/sdcard/Android/data/$packageName/files"
    val writeCommand =
        "rm -f $directory/config.json 2>/dev/null; " +
        "o=\$(content write --uri content://$packageName.theme/config.json < /data/local/tmp/tvapk-launcher-config.json 2>&1); rc=\$?; " +
        "case \"\$o\" in *Unsupported*|*Error*) rc=1;; esac; " +
        "if [ \"\$rc\" -ne 0 ]; then mkdir -p $directory 2>/dev/null && cp /data/local/tmp/tvapk-launcher-config.json $directory/config.json || exit 1; fi; " +
        "rm -f /data/local/tmp/tvapk-launcher-config.json"
    shellText(adb, writeCommand)
    shellText(adb, "am force-stop --user 0 $packageName")
    val restart = try {
        shellText(adb, "am start --user 0 -n $packageName/.MainActivity")
    } catch (_: Exception) {
        shellText(adb, "monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }
    return "Оформление Clean Your TV применено: $columns приложений в ряду, $rowsBelowDock ряд(а) под доком. Launcher перезапущен. $restart"
}
private fun downloadCleanYourTvTheme(context: Context, themeName: String): Pair<File, File> {
    require(themeName in setOf("arcade", "cinema", "console", "diwali", "horizon", "library", "mist", "quiet")) {
        "Неизвестная тема Clean Your TV"
    }
    val base = "https://raw.githubusercontent.com/udhaykumarbala/cleanyourtv-app/main/core/spec/src/main/resources/tv/cleanyour/core/spec/looks/$themeName"
    fun download(fileName: String, maxBytes: Long): File {
        val connection = URL("$base/$fileName").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "TV-APK-Installer")
            if (connection.responseCode != 200) throw Exception("Не удалось загрузить $fileName: HTTP ${connection.responseCode}")
            val target = File(context.cacheDir, "cyt-$themeName-$fileName")
            var total = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw Exception("Файл темы слишком большой: $fileName")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total < 32L) throw Exception("Файл темы пустой или повреждён: $fileName")
            return target
        } finally {
            connection.disconnect()
        }
    }
    val config = download("config.json", 128 * 1024L)
    JSONObject(config.readText(Charsets.UTF_8))
    // Quiet is a pure-black look and intentionally ships without wallpaper.jpg.
    // pushLauncherTheme expects an image payload, but config.json disables wallpaper for this look.
    val wallpaper = if (themeName == "quiet") {
        val connection = URL("https://raw.githubusercontent.com/udhaykumarbala/cleanyourtv-app/main/core/spec/src/main/resources/tv/cleanyour/core/spec/looks/cinema/wallpaper.jpg").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            require(connection.responseCode == 200) { "Не удалось получить нейтральный файл обоев для темы Quiet" }
            val target = File(context.cacheDir, "cyt-quiet-wallpaper.jpg")
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            require(target.length() in 32L..(12L * 1024L * 1024L)) { "Файл обоев Quiet повреждён" }
            target
        } finally {
            connection.disconnect()
        }
    } else download("wallpaper.jpg", 12 * 1024 * 1024L)
    return config to wallpaper
}

private fun prepareCustomWallpaper(context: Context, uri: Uri): File {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = resolver.openInputStream(uri) ?: throw Exception("Не удалось открыть изображение")
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Файл не является корректным изображением" }
    require(bounds.outWidth <= 12000 && bounds.outHeight <= 12000) { "Изображение слишком большое по разрешению" }
    val sample = maxOf(1, (maxOf(bounds.outWidth, bounds.outHeight) + 3839) / 3840)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw Exception("Не удалось декодировать изображение")
    val target = File(context.cacheDir, "cleanyourtv-custom-wallpaper.jpg")
    try {
        target.outputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw Exception("Не удалось подготовить изображение")
        }
    } finally { bitmap.recycle() }
    require(target.length() in 1024L..(12L * 1024L * 1024L)) { "Подготовленное изображение слишком большое или повреждено" }
    return target
}

private fun makeCustomWallpaperConfig(context: Context, baseConfig: File, dim: Int): File {
    val config = JSONObject(baseConfig.readText(Charsets.UTF_8))
        .put("wallpaper", true)
        .put("wallpaper_dim", dim.coerceIn(0, 80))
        .put("wallpaper_rotation_minutes", 0)
    return File(context.cacheDir, "cleanyourtv-custom-config.json").apply {
        writeText(config.toString(2), Charsets.UTF_8)
    }
}

private fun pushLauncherTheme(
    adb: Dadb,
    themeName: String,
    configFile: File,
    wallpaperFile: File,
    preservePersonalSettings: Boolean = true
): String {
    val packageName = "com.gathin.tvhome"
    require(shellText(adb, "pm path $packageName").contains("package:")) {
        "Clean Your TV Launcher не установлен. Сначала установи модуль в разделе «Модули Clean Your TV»."
    }

    // Themes control visual styling, but must not silently discard the user's
    // dock, hidden apps, key mappings, clock preferences, or wallpaper rotation.
    val configToInstall = if (preservePersonalSettings) {
        val themed = JSONObject(configFile.readText(Charsets.UTF_8))
        val current = readCurrentLauncherConfig(adb)
        listOf(
            "dock_apps", "hidden_apps", "app_keys", "key_filter",
            "wallpaper_rotation_minutes", "wallpaper_order", "wallpaper_moods", "wallpaper_captions",
            "clock_format", "clock_glow", "clock_wallpaper", "clock_wallpaper_dim",
            "home_idle_clock_minutes", "back_opens_clock",
            // Keep the personal home-screen widgets and controls when a theme omits them.
            "greeting_name", "show_greeting", "date_below_clock", "show_stats",
            "show_network_pill", "show_inputs_pill", "icon_only_buttons", "icon_only_inputs",
            "wifi_status_color", "wifi_status_warnings", "top_bar_apps", "vpn_app",
            "library_button", "show_library_pill", "settings_key_panel",
            "inputs_connected_only", "inputs_cec_state_only",
            "boot_to_live_tv", "open_on_start_choice", "open_on_start_network_wait",
            "open_on_start_on_wake", "live_tv_package", "live_tv_action", "live_tv_input_id"
        ).forEach { key ->
            if (current.has(key)) themed.put(key, current.get(key))
        }
        // Forward-compatible merge: retain any settings written by newer launcher
        // versions when an older theme archive does not know about those keys.
        val currentKeys = current.keys()
        while (currentKeys.hasNext()) {
            val key = currentKeys.next()
            if (!themed.has(key)) themed.put(key, current.get(key))
        }
        File.createTempFile("cleanyourtv-theme-merged-", ".json").apply {
            writeText(themed.toString(2), Charsets.UTF_8)
        }
    } else {
        configFile
    }

    val remoteConfig = "/data/local/tmp/theme-config.json"
    val remoteWallpaper = "/data/local/tmp/theme-wallpaper.jpg"
    fun pushFile(local: File, remote: String) {
        when (val result = adb.push(local, remote)) {
            is dadb.SyncResult.Success -> Unit
            is dadb.SyncResult.Failure -> throw Exception("Не удалось передать файл на телевизор: $result")
        }
    }
    pushFile(configToInstall, remoteConfig)
    pushFile(wallpaperFile, remoteWallpaper)

    val directory = "/sdcard/Android/data/$packageName/files"
    fun writeFile(fileName: String, remotePath: String) {
        val command =
            "rm -f $directory/$fileName 2>/dev/null; " +
            "o=\$(content write --uri content://$packageName.theme/$fileName < $remotePath 2>&1) && [ -z \"\$o\" ] || (mkdir -p $directory 2>/dev/null && cp $remotePath $directory/$fileName); " +
            "r=\$?; rm -f $remotePath; exit \$r"
        shellText(adb, command)
    }
    writeFile("wallpaper.jpg", remoteWallpaper)
    writeFile("config.json", remoteConfig)
    shellText(adb, "am force-stop --user 0 $packageName")
    val restart = shellText(adb, "am start --user 0 -n $packageName/.MainActivity")
    if (configToInstall !== configFile) configToInstall.delete()
    return "Тема «$themeName» передана на телевизор. Визуальное оформление обновлено, персональные настройки сохранены, Launcher перезапущен. $restart"
}

private fun parseLauncherPackageList(raw: String): List<String> {
    val entries = raw.split(Regex("[,;\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
    require(entries.size <= 24) { "Можно закрепить или скрыть не более 24 приложений за один раз" }
    val invalid = entries.filterNot { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
    require(invalid.isEmpty()) { "Неверный package name: ${invalid.take(3).joinToString()}" }
    return entries.distinct()
}

private fun readLauncherApps(adb: Dadb): List<String> {
    val output = shellText(adb, "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
    val packages = output.lineSequence()
        .map { it.trim().substringBefore("/") }
        .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
        .distinct()
        .sorted()
        .toList()
    require(packages.isNotEmpty()) { "Телевизор не вернул список приложений с TV-значком запуска" }
    return packages
}

private fun readCurrentLauncherConfig(adb: Dadb): JSONObject {
    val provider = try {
        shellText(adb, "content read --uri content://com.gathin.tvhome.theme/config.json")
    } catch (_: Exception) { "" }
    if (provider.trim().startsWith("{")) {
        try { return JSONObject(provider.trim()) } catch (_: Exception) { }
    }
    val legacy = try {
        shellText(adb, "cat /sdcard/Android/data/com.gathin.tvhome/files/config.json")
    } catch (_: Exception) { "" }
    if (legacy.trim().startsWith("{")) {
        try { return JSONObject(legacy.trim()) } catch (_: Exception) { }
    }
    throw Exception("Не удалось прочитать текущий config.json Launcher. Ничего на телевизоре не изменено.")
}

private fun writeLauncherConfig(adb: Dadb, config: JSONObject): String {
    val packageName = "com.gathin.tvhome"
    require(shellText(adb, "pm path $packageName").contains("package:")) {
        "Clean Your TV Launcher не установлен."
    }
    val safeFile = File.createTempFile("launcher-config-", ".json")
    val remote = "/data/local/tmp/launcher-config.json"
    try {
        safeFile.writeText(config.toString(2), Charsets.UTF_8)
        when (val result = adb.push(safeFile, remote)) {
            is dadb.SyncResult.Success -> Unit
            is dadb.SyncResult.Failure -> throw Exception("Не удалось передать конфигурацию: $result")
        }
        val directory = "/sdcard/Android/data/$packageName/files"
        val command =
            "rm -f $directory/config.json 2>/dev/null; " +
            "o=\$(content write --uri content://$packageName.theme/config.json < $remote 2>&1) && [ -z \"\$o\" ] || (mkdir -p $directory 2>/dev/null && cp $remote $directory/config.json); " +
            "r=\$?; rm -f $remote; exit \$r"
        shellText(adb, command)
        shellText(adb, "am force-stop --user 0 $packageName")
        return try {
            shellText(adb, "am start --user 0 -n $packageName/.MainActivity")
        } catch (_: Exception) {
            shellText(adb, "monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        }
    } finally {
        safeFile.delete()
    }
}

private fun applyLauncherAppLists(adb: Dadb, dock: List<String>, hidden: List<String>, fillDock: Boolean): String {
    require(dock.none { it in hidden }) {
        "Одно приложение нельзя одновременно закрепить в доке и скрыть с главного экрана."
    }
    val config = readCurrentLauncherConfig(adb)
    if (dock.isEmpty()) {
        config.remove("dock_apps")
        config.remove("rows_below_dock")
    } else config.put("dock_apps", JSONArray(dock))
    if (hidden.isEmpty()) config.remove("hidden_apps") else config.put("hidden_apps", JSONArray(hidden))
    config.put("fill_dock", fillDock)
    val columns = config.optInt("columns", 4).coerceIn(1, 8)
    if (dock.isNotEmpty()) {
        val rowsBelow = if (config.optBoolean("cinematic_home", false)) 0
            else ((dock.size + columns - 1) / columns - 1).coerceIn(0, 2)
        config.put("rows_below_dock", rowsBelow)
        if (rowsBelow == 2) config.put("dock_title", false)
    }
    val restart = writeLauncherConfig(adb, config)
    return "Список приложений обновлён. В доке: ${dock.size}, скрыто с главного экрана: ${hidden.size}. Приложения не удалялись и не отключались. $restart"
}

private fun applyLauncherClockSettings(
    adb: Dadb,
    idleMinutes: Int,
    wallpaper: Boolean,
    wallpaperDim: Int,
    glow: Boolean,
    backOpensClock: Boolean,
    clockFormat: String
): String {
    require(clockFormat in setOf("system", "12", "24")) { "Неверный формат времени" }
    val config = readCurrentLauncherConfig(adb)
        .put("home_idle_clock_minutes", idleMinutes.coerceIn(0, 1440))
        .put("clock_wallpaper", wallpaper)
        .put("clock_wallpaper_dim", wallpaperDim.coerceIn(0, 90))
        .put("clock_glow", glow)
        .put("back_opens_clock", backOpensClock)
        .put("clock_format", clockFormat)
    val restart = writeLauncherConfig(adb, config)
    return "Параметры часов Clean Your TV применены. Авточасы: ${if (idleMinutes <= 0) "выключены" else "через $idleMinutes мин."}. $restart"
}

private fun configureClockDream(adb: Dadb, enabled: Boolean): String {
    val component = "com.gathin.tvhome/.ClockDream"
    val current = try { shellText(adb, "settings get secure screensaver_components").trim() } catch (_: Exception) { "" }
    val components = current.split(":")
        .map { it.trim() }
        .filter { it != "null" && it.matches(Regex("[A-Za-z0-9_.$/]+")) }
        .toMutableSet()
    if (enabled) components.add(component) else components.remove(component)
    val next = components.joinToString(":")
    val wasEnabled = try { shellText(adb, "settings get secure screensaver_enabled").trim() == "1" } catch (_: Exception) { false }
    val nowEnabled = if (enabled) true else wasEnabled && components.isNotEmpty()
    shellText(adb, "settings put secure screensaver_components '$next'; settings put secure screensaver_enabled ${if (nowEnabled) 1 else 0}")
    val verifiedComponents = try {
        shellText(adb, "settings get secure screensaver_components").trim()
    } catch (e: Exception) {
        throw Exception("Не удалось проверить список заставок после изменения: ${errorText(e)}", e)
    }
    val verifiedEnabled = shellText(adb, "settings get secure screensaver_enabled").trim() == "1"
    require(verifiedComponents == next && verifiedEnabled == nowEnabled) {
        "Телевизор не подтвердил новое состояние заставки. Список или переключатель отличается от ожидаемого."
    }
    return if (enabled) {
        val opened = try { shellText(adb, "am start -a android.settings.DREAM_SETTINGS") } catch (_: Exception) { "Открой системные настройки заставки вручную." }
        "Системная заставка Clean Your TV включена. Настрой время запуска в открывшемся меню. $opened"
    } else {
        "Системная заставка Clean Your TV отключена. Другие заставки сохранены."
    }
}

private fun parseLauncherRemoteMappings(raw: String): List<String> {
    val entries = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    require(entries.size <= 32) { "Можно назначить не более 32 кнопок" }
    val packagePattern = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    val actions = setOf("back", "home", "recents", "mute", "sleep", "screensaver", "settings", "wifi", "library", "ignore", "inputs")
    fun validTarget(target: String): Boolean {
        if (target.length > 256 || target.any { it.isWhitespace() }) return false
        if (target in actions) return true
        if (target.startsWith("input:")) return target.removePrefix("input:").matches(Regex("[A-Za-z0-9_./:-]+"))
        if (target.startsWith("guide:")) return packagePattern.matches(target.removePrefix("guide:"))
        if (packagePattern.matches(target)) return true
        return target.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))
    }
    val invalid = entries.filter { entry ->
        var value = entry
        if (value.startsWith("hold:")) value = value.removePrefix("hold:")
        else if (value.startsWith("front:")) value = value.removePrefix("front:")
        if (value.startsWith("scan:")) {
            val parts = value.split(":", limit = 3)
            parts.size != 3 || parts[1].toIntOrNull()?.let { it <= 0 } != false || !validTarget(parts[2])
        } else {
            val parts = value.split(":", limit = 2)
            parts.size != 2 ||
                !(parts[0].matches(Regex("[0-9]{1,6}")) && parts[0].toIntOrNull()?.let { it > 0 } == true ||
                    parts[0].matches(Regex("KEYCODE_[A-Z0-9_]+")) && parts[0] != "KEYCODE_UNKNOWN") ||
                !validTarget(parts[1])
        }
    }
    require(invalid.isEmpty()) {
        "Неверная запись кнопки: ${invalid.take(3).joinToString()}. Проверь keycode и назначение."
    }
    return entries.distinct()
}

private fun configureInputKeyService(adb: Dadb, enabled: Boolean): String {
    val packageName = "com.gathin.tvhome"
    val component = "$packageName/.InputKeyService"
    require(shellText(adb, "pm path $packageName").contains("package:")) {
        "Clean Your TV Launcher не установлен."
    }
    if (enabled) {
        val declared = try { shellText(adb, "dumpsys package $packageName | grep -F 'InputKeyService'") } catch (_: Exception) { "" }
        require(declared.contains("InputKeyService")) {
            "В установленной версии Launcher нет InputKeyService. Обнови модуль Clean Your TV."
        }
    }
    val current = try { shellText(adb, "settings get secure enabled_accessibility_services").trim() } catch (_: Exception) { "" }
    val services = current.split(":")
        .filter { it != "null" && it.matches(Regex("[A-Za-z0-9_.$/]+")) }
        .distinct()
    val wasEnabled = component in services
    if (enabled) {
        try { shellText(adb, "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS") } catch (_: Exception) { }
        try {
            shellText(adb, "cmd appops set $packageName AUTO_START allow 2>/dev/null || true; cmd appops set $packageName APP_AUTO_START allow 2>/dev/null || true; appops write-settings 2>/dev/null || true")
        } catch (_: Exception) { }
        val base = services.filterNot { it == component }
        val next = (base + component).distinct().joinToString(":")
        val command = if (wasEnabled) {
            "settings put secure enabled_accessibility_services '${base.joinToString(":")}'; sleep 1; settings put secure enabled_accessibility_services '$next'; settings put secure accessibility_enabled 1"
        } else {
            "settings put secure enabled_accessibility_services '$next'; settings put secure accessibility_enabled 1"
        }
        shellText(adb, command)
        val verifiedRaw = shellText(adb, "settings get secure enabled_accessibility_services").trim()
        val verifiedServices = verifiedRaw.takeUnless { it.isBlank() || it == "null" }?.split(":").orEmpty()
        val accessibilityEnabled = shellText(adb, "settings get secure accessibility_enabled").trim() == "1"
        require(verifiedServices.count { it == component } == 1 &&
            verifiedServices.filterNot { it == component } == base &&
            accessibilityEnabled) {
            "Телевизор не подтвердил включение InputKeyService или изменился список других служб специальных возможностей."
        }
        return "Сервис обработки кнопок включён. Сохранённые назначения готовы к работе."
    } else {
        val nextServices = services.filterNot { it == component }
        val next = nextServices.joinToString(":")
        shellText(adb, "settings put secure enabled_accessibility_services '$next'")
        val verifiedRaw = shellText(adb, "settings get secure enabled_accessibility_services").trim()
        val verifiedServices = verifiedRaw.takeUnless { it.isBlank() || it == "null" }?.split(":").orEmpty()
        require(component !in verifiedServices && verifiedServices == nextServices) {
            "Телевизор не подтвердил отключение InputKeyService или изменился список других служб специальных возможностей."
        }
        return "Сервис обработки кнопок отключён. Остальные службы специальных возможностей сохранены."
    }
}

private fun applyLauncherRemoteMappings(adb: Dadb, raw: String): String {
    val entries = parseLauncherRemoteMappings(raw)
    val config = readCurrentLauncherConfig(adb)
    if (entries.isEmpty()) config.remove("app_keys") else config.put("app_keys", JSONArray(entries))
    config.put("key_filter", true)
    val restart = writeLauncherConfig(adb, config)
    val service = configureInputKeyService(adb, true)
    return "Назначения кнопок сохранены: ${entries.size}. $service $restart"
}

private fun disableLauncherRemoteMappings(adb: Dadb): String {
    val config = readCurrentLauncherConfig(adb)
    config.remove("app_keys")
    config.remove("key_filter")
    val restart = writeLauncherConfig(adb, config)
    return "Назначения кнопок очищены. InputKeyService оставлен включённым для штатной кнопки TV/Input; при необходимости отключи его в настройках специальных возможностей. $restart"
}

private fun launcherWallpaperExists(adb: Dadb): Boolean? {
    val packageName = "com.gathin.tvhome"
    val path = "/sdcard/Android/data/$packageName/files/wallpaper.jpg"
    return try {
        when (shellText(
            adb,
            "if content read --uri content://$packageName.theme/wallpaper.jpg >/dev/null 2>&1; then echo present; elif [ -f $path ]; then echo present; else echo absent; fi"
        ).trim()) {
            "present" -> true
            "absent" -> false
            else -> null
        }
    } catch (_: Exception) { null }
}

private fun readLauncherWallpaper(adb: Dadb): ByteArray? {
    val provider = try { shellText(adb, "content read --uri content://com.gathin.tvhome.theme/wallpaper.jpg | base64") } catch (_: Exception) { "" }
    val encoded = if (provider.isNotBlank()) provider else try {
        shellText(adb, "cat /sdcard/Android/data/com.gathin.tvhome/files/wallpaper.jpg | base64")
    } catch (_: Exception) { "" }
    if (encoded.isBlank()) return null
    val compact = encoded.filterNot { it.isWhitespace() }
    if (compact.length > 16 * 1024 * 1024) throw Exception("Файл обоев слишком большой для резервной копии")
    val bytes = try { Base64.decode(compact, Base64.DEFAULT) } catch (_: Exception) { return null }
    if (bytes.size !in 64..(12 * 1024 * 1024)) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 12000 || bounds.outHeight > 12000) return null
    return bytes
}

private fun saveLauncherThemeRollback(context: Context, adb: Dadb): String {
    val (archive, hasWallpaper, collectionCount) = exportLauncherBackup(context, adb)
    try {
        val destination = File(context.filesDir, "launcher-theme-rollback.zip")
        archive.copyTo(destination, overwrite = true)
        return "Автокопия прежнего оформления сохранена (${if (hasWallpaper) "с обоями" else "без файла обоев"}, коллекция: $collectionCount)."
    } finally {
        archive.delete()
    }
}
private fun exportLauncherBackup(context: Context, adb: Dadb): Triple<File, Boolean, Int> {
    val config = readCurrentLauncherConfig(adb)
    val wallpaper = readLauncherWallpaper(adb)
    val wallpaperState = when {
        wallpaper != null -> "present"
        launcherWallpaperExists(adb) == false -> "absent"
        else -> "unknown"
    }
    val collectionDir = File(context.filesDir, "launcher_wallpapers")
    val allowed = Regex("wallpaper-[0-9]{3}\\.(jpg|jpeg|png|webp)")
    val collection = collectionDir.listFiles()?.filter { file ->
        file.isFile && allowed.matches(file.name) && file.length() in 64..(10 * 1024 * 1024).toLong()
    }?.sortedBy { it.name }?.take(100) ?: emptyList()
    require(collection.sumOf { it.length() } <= 50L * 1024 * 1024) { "Коллекция обоев на телефоне превышает 50 МБ" }
    val archive = File.createTempFile("cleanyourtv-backup-", ".zip", context.cacheDir)
    try {
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("config.json"))
            zip.write(config.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("wallpaper-state.txt"))
            zip.write(wallpaperState.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (wallpaper != null) {
                zip.putNextEntry(ZipEntry("wallpaper.jpg"))
                zip.write(wallpaper)
                zip.closeEntry()
            }
            collection.forEach { file ->
                zip.putNextEntry(ZipEntry("wallpapers/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return Triple(archive, wallpaper != null, collection.size)
    } catch (e: Exception) {
        archive.delete()
        throw e
    }
}

private fun restoreLauncherBackup(context: Context, uri: Uri, adb: Dadb): String {
    val input = context.contentResolver.openInputStream(uri) ?: throw Exception("Не удалось открыть ZIP-архив")
    return restoreLauncherBackupFromStream(context, input, adb)
}

private fun restoreLauncherBackupFile(context: Context, file: File, adb: Dadb): String {
    require(file.isFile && file.length() in 1..(64L * 1024L * 1024L)) {
        "Автоматическая резервная копия оформления не найдена или повреждена"
    }
    return restoreLauncherBackupFromStream(context, file.inputStream(), adb)
}

private fun restoreLauncherBackupFromStream(context: Context, input: java.io.InputStream, adb: Dadb): String {
    var configBytes: ByteArray? = null
    var wallpaperBytes: ByteArray? = null
    var wallpaperState: String? = null
    val collectionBytes = linkedMapOf<String, ByteArray>()
    var totalBytes = 0
    var collectionTotalBytes = 0
    val allowedWallpaper = Regex("wallpaper-[0-9]{3}\\.(jpg|jpeg|png|webp)")
    fun validateImage(bytes: ByteArray, label: String) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000) {
            "$label повреждён или имеет слишком большое разрешение"
        }
    }
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) {
                zip.closeEntry()
                continue
            }
            val collectionName = if (entry.name.startsWith("wallpapers/")) entry.name.removePrefix("wallpapers/") else null
            require(entry.name == "config.json" || entry.name == "wallpaper.jpg" || entry.name == "wallpaper-state.txt" ||
                (collectionName != null && allowedWallpaper.matches(collectionName))) {
                "В архиве есть неподдерживаемый файл: ${entry.name}"
            }
            val maxEntrySize = when {
                entry.name == "config.json" -> 1024 * 1024
                entry.name == "wallpaper.jpg" -> 12 * 1024 * 1024
                entry.name == "wallpaper-state.txt" -> 16
                else -> 10 * 1024 * 1024
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var entryBytes = 0
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                entryBytes += count
                totalBytes += count
                require(entryBytes <= maxEntrySize && totalBytes <= 64 * 1024 * 1024) {
                    "Резервная копия превышает допустимый размер"
                }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            when {
                entry.name == "config.json" -> {
                    require(configBytes == null) { "В архиве несколько файлов config.json" }
                    require(entryBytes in 2..(1024 * 1024)) { "Некорректный размер config.json" }
                    configBytes = bytes
                }
                entry.name == "wallpaper.jpg" -> {
                    require(wallpaperBytes == null) { "В архиве несколько файлов wallpaper.jpg" }
                    require(entryBytes in 64..(12 * 1024 * 1024)) { "Некорректный размер wallpaper.jpg" }
                    validateImage(bytes, "wallpaper.jpg")
                    wallpaperBytes = bytes
                }
                entry.name == "wallpaper-state.txt" -> {
                    require(wallpaperState == null) { "В архиве несколько файлов wallpaper-state.txt" }
                    require(entryBytes in 3..16) { "Некорректный wallpaper-state.txt" }
                    wallpaperState = String(bytes, Charsets.UTF_8).trim()
                    require(wallpaperState in setOf("present", "absent", "unknown")) { "Неизвестное состояние обоев в архиве" }
                }
                collectionName != null -> {
                    require(collectionBytes.size < 100) { "В архиве больше 100 обоев" }
                    require(collectionName !in collectionBytes) { "В архиве повторяется файл $collectionName" }
                    require(entryBytes in 64..(10 * 1024 * 1024)) { "Некорректный размер $collectionName" }
                    collectionTotalBytes += entryBytes
                    require(collectionTotalBytes <= 50 * 1024 * 1024) { "Коллекция обоев в архиве больше 50 МБ" }
                    validateImage(bytes, collectionName)
                    collectionBytes[collectionName] = bytes
                }
            }
            zip.closeEntry()
        }
    }
    val config = JSONObject(String(configBytes ?: throw Exception("В архиве отсутствует config.json"), Charsets.UTF_8))
    val wallpaper = wallpaperBytes
    require(wallpaperState != "present" || wallpaper != null) { "В архиве отмечены обои, но wallpaper.jpg отсутствует" }
    require(wallpaperState != "absent" || wallpaper == null) { "Архив противоречив: состояние обоев отмечено как отсутствующее, но файл есть" }
    val baseRestore = if (wallpaper == null) {
        if (wallpaperState == "absent") {
            try {
                shellText(adb, "content delete --uri content://com.gathin.tvhome.theme/wallpaper.jpg")
            } catch (_: Exception) {
                shellText(adb, "rm -f /sdcard/Android/data/com.gathin.tvhome/files/wallpaper.jpg")
            }
        }
        writeLauncherConfig(adb, config)
    } else {
        val configFile = File.createTempFile("cleanyourtv-restore-", ".json", context.cacheDir)
        val wallpaperFile = File.createTempFile("cleanyourtv-restore-", ".jpg", context.cacheDir)
        try {
            configFile.writeText(config.toString(2), Charsets.UTF_8)
            wallpaperFile.writeBytes(wallpaper)
            pushLauncherTheme(adb, "backup", configFile, wallpaperFile, preservePersonalSettings = false)
        } finally {
            configFile.delete()
            wallpaperFile.delete()
        }
    }
    if (collectionBytes.isEmpty()) {
        val wallpaperNote = when {
            wallpaper != null -> "Текущие обои восстановлены."
            wallpaperState == "absent" -> "Восстановлено отсутствие файла обоев."
            else -> "Файл обоев не удалось сохранить; текущие обои оставлены без изменений."
        }
        return "Конфигурация Clean Your TV восстановлена. $wallpaperNote Коллекция ротации не менялась. $baseRestore"
    }
    val staging = File(context.cacheDir, "launcher-wallpapers-restore")
    if (staging.exists()) staging.deleteRecursively()
    require(staging.mkdirs()) { "Не удалось подготовить коллекцию обоев для восстановления" }
    val localFiles = try {
        collectionBytes.forEach { (name, bytes) -> File(staging, name).writeBytes(bytes) }
        val destination = File(context.filesDir, "launcher_wallpapers")
        if (destination.exists()) require(destination.deleteRecursively()) { "Не удалось заменить локальную коллекцию обоев" }
        if (!staging.renameTo(destination)) {
            require(destination.mkdirs()) { "Не удалось создать локальную коллекцию обоев" }
            staging.listFiles()?.forEach { file -> file.copyTo(File(destination, file.name), overwrite = true) }
            staging.deleteRecursively()
        }
        destination.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    } catch (e: Exception) {
        staging.deleteRecursively()
        throw e
    }
    val rotation = uploadWallpaperCollection(
        adb,
        localFiles,
        config.optInt("wallpaper_rotation_minutes", 0),
        config.optString("wallpaper_order", "shuffle").takeIf { it in setOf("sequential", "shuffle") } ?: "shuffle"
    )
    return "Резервная копия восстановлена: конфигурация, ${if (wallpaper != null) "текущие обои и " else ""}${collectionBytes.size} изображений коллекции. $baseRestore $rotation"
}

private fun applyLauncherHomeKeySettings(adb: Dadb, longPressHome: Boolean): String {
    val config = readCurrentLauncherConfig(adb)
        .put("home_long_press_home", longPressHome)
    val restart = writeLauncherConfig(adb, config)
    return "Перехват долгого нажатия Home ${if (longPressHome) "включён" else "выключен"}. Для работы требуется HomeKeyService. $restart"
}

private fun enableHomeKeyService(adb: Dadb): String {
    val packageName = "com.gathin.tvhome"
    val component = "$packageName/.HomeKeyService"
    val fullComponent = "$packageName/$packageName.HomeKeyService"
    fun isOurService(entry: String): Boolean = entry == component || entry == fullComponent

    require(shellText(adb, "pm path $packageName").contains("package:")) {
        "Clean Your TV Launcher не установлен."
    }
    val declared = try { shellText(adb, "dumpsys package $packageName | grep -F 'HomeKeyService'") } catch (_: Exception) { "" }
    require(declared.contains("HomeKeyService")) {
        "В установленной версии Launcher нет HomeKeyService. Обнови модуль Clean Your TV."
    }

    // Never turn Accessibility on if that would also start unrelated services.
    val rawResult = shellText(adb, "settings get secure enabled_accessibility_services").trim()
    val raw = rawResult.takeUnless { it.isBlank() || it == "null" }.orEmpty()
    val validEntry = Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
    val services = if (raw.isEmpty()) emptyList() else raw.split(":")
    require(services.size <= 50 && services.all { validEntry.matches(it) }) {
        "Список специальных возможностей имеет неизвестный формат. Ничего не изменено."
    }
    val others = services.filterNot(::isOurService).distinct()
    val accessibilityOn = shellText(adb, "settings get secure accessibility_enabled").trim() == "1"
    if (!accessibilityOn && others.isNotEmpty()) {
        throw IllegalStateException(
            "Специальные возможности телевизора выключены, но в списке есть другие службы. " +
                "Чтобы не включать их без разрешения, сначала включи HomeKeyService вручную в настройках телевизора."
        )
    }

    val next = (others + component).distinct().joinToString(":")
    if (raw != next) {
        // Compare-and-set on the TV: don't overwrite a service list changed after our read.
        val expectedCondition = if (raw.isEmpty()) {
            "[ -z \"\$current\" ] || [ \"\$current\" = null ]"
        } else {
            "[ \"\$current\" = '" + raw + "' ]"
        }
        val command = "current=\$(settings get secure enabled_accessibility_services); " +
            "if $expectedCondition; then settings put secure enabled_accessibility_services '$next'; " +
            "else echo 'ACCESSIBILITY_LIST_CHANGED' >&2; exit 3; fi"
        shellText(adb, command)
    }
    if (!accessibilityOn) {
        shellText(adb, "settings put secure accessibility_enabled 1")
    }

    val verifiedRaw = shellText(adb, "settings get secure enabled_accessibility_services").trim()
    val verifiedServices = verifiedRaw.takeUnless { it.isBlank() || it == "null" }?.split(":").orEmpty()
    val verifiedEnabled = shellText(adb, "settings get secure accessibility_enabled").trim() == "1"
    require(verifiedServices.count(::isOurService) == 1 &&
        verifiedServices.filterNot(::isOurService) == others && verifiedEnabled) {
        "Телевизор не подтвердил включение HomeKeyService или список служб изменился. Проверь настройки специальных возможностей."
    }
    return "HomeKeyService включён и проверен. Другие службы специальных возможностей не включались. " +
        "Список служб проверяется перед записью, чтобы не затереть изменения пользователя. " +
        "Служба нужна только пультам, которые отправляют KEYCODE_ALL_APPS вместо обычной кнопки Home."
}

private fun copyLauncherWallpaperUris(context: Context, uris: List<Uri>): List<File> {
    require(uris.isNotEmpty()) { "Не выбраны изображения" }
    require(uris.size <= 100) { "Можно выбрать не более 100 обоев" }
    val staging = File(context.cacheDir, "launcher-wallpapers-stage")
    if (staging.exists()) staging.deleteRecursively()
    require(staging.mkdirs()) { "Не удалось создать временную папку обоев" }
    var totalBytes = 0L
    try {
        uris.forEachIndexed { index, uri ->
            val displayName = try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (_: Exception) { null }
            val mime = context.contentResolver.getType(uri)?.lowercase()
            val extension = when (mime) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> displayName?.substringAfterLast('.', "")?.lowercase()
                    ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
            }
            val target = File(staging, "wallpaper-${(index + 1).toString().padStart(3, '0')}.$extension")
            val input = context.contentResolver.openInputStream(uri) ?: throw Exception("Не удалось открыть изображение №${index + 1}")
            var fileBytes = 0
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        fileBytes += count
                        totalBytes += count
                        require(fileBytes <= 10 * 1024 * 1024) { "Изображение №${index + 1} больше 10 МБ" }
                        require(totalBytes <= 50L * 1024 * 1024) { "Общий размер коллекции больше 50 МБ" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(fileBytes >= 64) { "Изображение №${index + 1} пустое" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000) {
                "Файл №${index + 1} не является поддерживаемым изображением или имеет слишком большое разрешение"
            }
        }
        val destination = File(context.filesDir, "launcher_wallpapers")
        if (destination.exists()) require(destination.deleteRecursively()) { "Не удалось заменить старую коллекцию обоев" }
        if (!staging.renameTo(destination)) {
            require(destination.mkdirs()) { "Не удалось создать папку коллекции обоев" }
            staging.listFiles()?.forEach { file -> file.copyTo(File(destination, file.name), overwrite = true) }
            staging.deleteRecursively()
        }
        return destination.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    } catch (e: Exception) {
        staging.deleteRecursively()
        throw e
    }
}

private fun uploadWallpaperCollection(adb: Dadb, files: List<File>, minutes: Int, order: String): String {
    require(files.isNotEmpty()) { "Сначала выбери изображения для ротации" }
    require(files.size <= 100) { "В коллекции не может быть больше 100 файлов" }
    require(order in setOf("sequential", "shuffle")) { "Неверный порядок обоев" }
    val packageName = "com.gathin.tvhome"
    require(shellText(adb, "pm path $packageName").contains("package:")) {
        "Clean Your TV Launcher не установлен."
    }
    val config = readCurrentLauncherConfig(adb)
    val allowed = Regex("wallpaper-[0-9]{3}\\.(jpg|jpeg|png|webp)")
    files.forEach { file ->
        require(file.isFile && file.length() in 64..(10 * 1024 * 1024).toLong() && allowed.matches(file.name)) {
            "Недопустимый файл обоев: ${file.name}"
        }
    }
    val remoteFiles = files.map { file -> file to "/data/local/tmp/cyt-${file.name}" }
    remoteFiles.forEach { (file, remote) ->
        when (val result = adb.push(file, remote)) {
            is dadb.SyncResult.Success -> Unit
            is dadb.SyncResult.Failure -> throw Exception("Не удалось передать ${file.name}: $result")
        }
    }
    val directory = "/sdcard/Android/data/$packageName/files"
    try {
        shellText(adb, "content delete --uri content://$packageName.theme/wallpapers")
    } catch (_: Exception) {
        shellText(adb, "rm -rf $directory/wallpapers 2>/dev/null; mkdir -p $directory/wallpapers")
    }
    remoteFiles.forEach { (file, remote) ->
        val command =
            "o=\$(content write --uri content://$packageName.theme/wallpapers/${file.name} < $remote 2>&1); rc=\$?; " +
            "case \"\$o\" in *Unsupported*|*Error*) rc=1;; esac; " +
            "if [ \"\$rc\" -ne 0 ]; then mkdir -p $directory/wallpapers 2>/dev/null && cp $remote $directory/wallpapers/${file.name} || exit 1; fi; " +
            "rm -f $remote"
        shellText(adb, command)
    }
    config.put("wallpaper_rotation_minutes", minutes.coerceIn(0, 1440))
        .put("wallpaper_order", order)
        .put("wallpaper", true)
    val restart = writeLauncherConfig(adb, config)
    return "Загружено обоев: ${files.size}. Ротация: ${if (minutes <= 0) "выключена" else "каждые $minutes мин."}, порядок: ${if (order == "shuffle") "случайный" else "по порядку"}. $restart"
}

private fun clearWallpaperCollectionOnTv(adb: Dadb): String {
    val packageName = "com.gathin.tvhome"
    require(shellText(adb, "pm path $packageName").contains("package:")) {
        "Clean Your TV Launcher не установлен."
    }
    val directory = "/sdcard/Android/data/$packageName/files"
    try {
        shellText(adb, "content delete --uri content://$packageName.theme/wallpapers")
    } catch (_: Exception) {
        shellText(adb, "rm -rf $directory/wallpapers 2>/dev/null; mkdir -p $directory/wallpapers")
    }
    val config = readCurrentLauncherConfig(adb)
        .put("wallpaper_rotation_minutes", 0)
    val restart = writeLauncherConfig(adb, config)
    return "Коллекция ротации удалена с телевизора, автоматическая смена выключена. Копии на телефоне сохранены — их можно загрузить повторно. $restart"
}

private fun applyWallpaperRotationSettings(adb: Dadb, minutes: Int, order: String): String {
    require(order in setOf("sequential", "shuffle")) { "Неверный порядок обоев" }
    val config = readCurrentLauncherConfig(adb)
        .put("wallpaper_rotation_minutes", minutes.coerceIn(0, 1440))
        .put("wallpaper_order", order)
    if (minutes > 0) config.put("wallpaper", true)
    val restart = writeLauncherConfig(adb, config)
    return "Ротация обоев: ${if (minutes <= 0) "выключена" else "каждые $minutes мин."}. $restart"
}

private fun readTvDiagnostics(adb: Dadb): String {
    fun safe(command: String): String = try {
        shellText(adb, command).ifBlank { "Недоступно" }
    } catch (_: Exception) { "Недоступно" }

    val memory = safe("cat /proc/meminfo | head -n 3")
    val storage = safe("df -h /data")
    val uptime = safe("cat /proc/uptime")
    val battery = safe("dumpsys battery | grep -E 'level|temperature|status'")
    val appStorage = safe("du -sh /data/app")
    val userStorage = safe("du -sh /data/user/0")

    return buildString {
        appendLine("ОПЕРАТИВНАЯ ПАМЯТЬ")
        appendLine(memory)
        appendLine()
        appendLine("ХРАНИЛИЩЕ /DATA")
        appendLine(storage)
        appendLine("Папка установленных приложений: ${appStorage}")
        appendLine("Пользовательские данные приложений: ${userStorage}")
        appendLine()
        appendLine("БАТАРЕЯ (если доступна)")
        appendLine(battery)
        appendLine()
        appendLine("ВРЕМЯ РАБОТЫ")
        appendLine(uptime.substringBefore(" ").plus(" секунд"))
    }
}

private fun readTvIdentity(adb: Dadb): String {
    fun prop(name: String) = try { shellText(adb, "getprop $name").trim().ifBlank { "неизвестно" } } catch (_: Exception) { "неизвестно" }
    val manufacturer = prop("ro.product.manufacturer")
    val model = prop("ro.product.model")
    val device = prop("ro.product.device")
    val fingerprint = prop("ro.build.fingerprint")
    require(listOf(manufacturer, model, device, fingerprint).none { it == "неизвестно" }) {
        "Не удалось надёжно определить телевизор. Точка не создана, чтобы не привязать её к неизвестному устройству."
    }
    return listOf(manufacturer, model, device, fingerprint).joinToString("|")
}

private fun shellSingleQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

private data class PortableRestorePoint(
    val fingerprint: String,
    val userDisabled: Set<String>,
    val managedUsers: Set<String>,
    val managedSystems: Set<String>,
    val autoStartComponents: Set<String>
)

private fun buildRestorePointBackupJson(
    fingerprint: String,
    userDisabled: Set<String>,
    managedUsers: Set<String>,
    managedSystems: Set<String>,
    autoStartComponents: Set<String>
): String {
    val identity = fingerprint.split("|")
    require(identity.size == 4 && identity.none { it.isBlank() || it == "неизвестно" }) {
        "В точке нет полной идентификации телевизора"
    }
    val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    val componentPattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")
    require(userDisabled.size <= 5000 && managedUsers.size <= 5000 && managedSystems.size <= 5000 && autoStartComponents.size <= 5000) {
        "Слишком много записей в точке восстановления"
    }
    require((userDisabled + managedUsers + managedSystems).all { packagePattern.matches(it) }) {
        "В точке есть некорректное имя пакета"
    }
    require(managedSystems.none { isProtectedSystemPackage(it) }) {
        "Точка содержит защищённый системный пакет"
    }
    require(autoStartComponents.all { componentPattern.matches(it) }) {
        "В точке есть некорректный компонент автозапуска"
    }
    return JSONObject()
        .put("format", "tv-apk-installer-restore-point")
        .put("schemaVersion", 2)
        .put("exportedAt", System.currentTimeMillis())
        .put("deviceFingerprint", fingerprint)
        .put("userDisabledPackages", JSONArray(userDisabled.sorted()))
        .put("managedUserPackages", JSONArray(managedUsers.sorted()))
        .put("managedSystemPackages", JSONArray(managedSystems.sorted()))
        .put("autoStartComponents", JSONArray(autoStartComponents.sorted()))
        .toString(2)
}

private fun readBoundedUtf8(input: java.io.InputStream, maxBytes: Int): String {
    require(maxBytes > 0) { "Некорректный лимит чтения" }
    val output = ByteArrayOutputStream()
    input.use { stream ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "Файл превышает допустимый размер ${maxBytes / 1024} КБ" }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

private fun parseRestorePointBackup(jsonText: String): PortableRestorePoint {
    require(jsonText.length <= 2_000_000) { "Файл точки восстановления слишком большой" }
    val root = JSONObject(jsonText)
    require(root.optString("format") == "tv-apk-installer-restore-point") {
        "Это не файл точки восстановления TV APK Installer"
    }
    require(root.optInt("schemaVersion") == 2) { "Неподдерживаемая версия точки восстановления" }
    val fingerprint = root.optString("deviceFingerprint")
    val identity = fingerprint.split("|")
    require(identity.size == 4 && identity.none { it.isBlank() || it == "неизвестно" }) {
        "В файле отсутствует полная идентификация телевизора"
    }
    fun readSet(key: String): Set<String> {
        val array = root.getJSONArray(key)
        require(array.length() <= 5000) { "Слишком много записей в разделе $key" }
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }
    val userDisabled = readSet("userDisabledPackages")
    val managedUsers = readSet("managedUserPackages")
    val managedSystems = readSet("managedSystemPackages")
    val autoStart = readSet("autoStartComponents")
    val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    val componentPattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")
    require((userDisabled + managedUsers + managedSystems).all { packagePattern.matches(it) }) {
        "Файл содержит некорректное имя пакета"
    }
    require(managedSystems.none { isProtectedSystemPackage(it) }) {
        "Файл содержит защищённый системный пакет"
    }
    require(autoStart.all { componentPattern.matches(it) }) {
        "Файл содержит некорректный компонент автозапуска"
    }
    return PortableRestorePoint(fingerprint, userDisabled, managedUsers, managedSystems, autoStart)
}


private data class PortableRestoreBundle(val point: PortableRestorePoint, val restoreScript: String)

private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun buildPortableRestorePowerShell(
    fingerprint: String,
    managedUsers: Set<String>,
    snapshotUsers: Set<String>,
    managedSystems: Set<String>,
    snapshotSystems: Set<String>,
    managedAutoStart: Set<String>,
    snapshotAutoStart: Set<String>
): String {
    val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    val componentPattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")
    val users = managedUsers + snapshotUsers
    val systems = managedSystems + snapshotSystems
    val components = managedAutoStart + snapshotAutoStart
    require(users.all { packagePattern.matches(it) } && systems.all { packagePattern.matches(it) && !isProtectedSystemPackage(it) }) {
        "В ZIP есть некорректный или защищённый пакет"
    }
    require(components.all { componentPattern.matches(it) }) { "В ZIP есть некорректный компонент" }
    val identity = fingerprint.split("|")
    require(identity.size == 4 && identity.none { it.isBlank() || it == "неизвестно" }) { "Нет полной идентификации телевизора" }
    return buildString {
        appendLine("# TV APK Installer portable restore bundle")
        appendLine("\$ErrorActionPreference = 'Stop'")
        appendLine("if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'Android platform-tools (adb) не найдены.' }")
        appendLine("adb get-state | Out-Null")
        appendLine("if (\$LASTEXITCODE -ne 0) { throw 'Телевизор не подключён или не авторизован в ADB.' }")
        appendLine("\$actual = @((adb shell getprop ro.product.manufacturer).Trim(), (adb shell getprop ro.product.model).Trim(), (adb shell getprop ro.product.device).Trim(), (adb shell getprop ro.build.fingerprint).Trim()) -join '|'")
        appendLine("\$expected = '" + fingerprint + "'")
        appendLine("if (\$actual -cne \$expected) { throw 'Модель или прошивка не совпадает с точкой. Ничего не изменено.' }")
        appendLine("\$failed = 0")
        appendLine("function Set-TvState([string]\$Target, [bool]\$Disabled) {")
        appendLine("  if (\$Disabled) { adb shell pm disable-user --user 0 \$Target | Out-Null } else { adb shell pm enable --user 0 \$Target | Out-Null }")
        appendLine("  if (\$LASTEXITCODE -ne 0) { Write-Warning ('Не удалось изменить: ' + \$Target); \$script:failed++ }")
        appendLine("}")
        users.sorted().forEach { pkg -> appendLine("Set-TvState '" + pkg + "' " + if (pkg in snapshotUsers) "\$true" else "\$false") }
        systems.sorted().forEach { pkg -> appendLine("Set-TvState '" + pkg + "' " + if (pkg in snapshotSystems) "\$true" else "\$false") }
        components.sorted().forEach { component -> appendLine("Set-TvState '" + component + "' " + if (component in snapshotAutoStart) "\$true" else "\$false") }
        appendLine("if (\$failed -ne 0) { throw ('Восстановление выполнено частично. Ошибок: ' + \$failed) }")
        appendLine("Write-Host 'Сценарий завершён. Проверь состояние телевизора.'")
    }
}

private fun buildPortableRestoreBundleZip(
    restorePointJson: String,
    restoreSh: String,
    restorePs1: String
): ByteArray {
    val point = parseRestorePointBackup(restorePointJson)
    val entries = linkedMapOf(
        "restore-point.json" to restorePointJson.toByteArray(Charsets.UTF_8),
        "restore.sh" to restoreSh.toByteArray(Charsets.UTF_8),
        "restore.ps1" to restorePs1.toByteArray(Charsets.UTF_8),
        "README.txt" to ("TV APK Installer — переносимая точка восстановления\n\n" +
            "Содержимое: restore-point.json — снимок состояния; restore.sh — сценарий Bash; " +
            "restore.ps1 — сценарий PowerShell.\n" +
            "Перед запуском сценария установи Android platform-tools, подключи телевизор по ADB " +
            "и подтверди авторизацию. Скрипты сверяют производителя, модель, устройство и fingerprint прошивки.\n" +
            "Импорт ZIP в приложение только загружает точку; команды из архива автоматически не запускаются.\n").toByteArray(Charsets.UTF_8)
    )
    val manifest = JSONObject()
        .put("format", "tv-apk-installer-restore-bundle")
        .put("schemaVersion", 1)
        .put("createdAt", System.currentTimeMillis())
        .put("deviceFingerprint", point.fingerprint)
        .put("entries", JSONArray().also { array ->
            entries.forEach { (name, content) ->
                array.put(JSONObject().put("name", name).put("size", content.size).put("sha256", sha256Hex(content)))
            }
        }).toString(2).toByteArray(Charsets.UTF_8)
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        (linkedMapOf("manifest.json" to manifest) + entries).forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0L })
            zip.write(content)
            zip.closeEntry()
        }
    }
    return output.toByteArray().also { require(it.size <= 8 * 1024 * 1024) { "ZIP точки восстановления слишком большой" } }
}

private fun parsePortableRestoreBundleZip(bytes: ByteArray): PortableRestoreBundle {
    require(bytes.size in 1..(8 * 1024 * 1024)) { "Размер ZIP недопустим" }
    val files = linkedMapOf<String, ByteArray>()
    var total = 0
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(!entry.isDirectory) { "Папки в ZIP не поддерживаются" }
            require(entry.name in setOf("manifest.json", "restore-point.json", "restore.sh", "restore.ps1", "README.txt")) {
                "В ZIP найден неизвестный файл: " + entry.name.take(80)
            }
            require(entry.name !in files) { "Повторяющийся файл в ZIP: " + entry.name }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 8 * 1024 * 1024) { "Распакованный ZIP слишком большой" }
                out.write(buffer, 0, count)
            }
            files[entry.name] = out.toByteArray()
            zip.closeEntry()
        }
    }
    val manifestBytes = files["manifest.json"] ?: throw Exception("В ZIP отсутствует manifest.json")
    val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
    require(manifest.optString("format") == "tv-apk-installer-restore-bundle" && manifest.optInt("schemaVersion") == 1) {
        "Неподдерживаемый формат ZIP-архива"
    }
    val listed = linkedMapOf<String, Pair<Int, String>>()
    val array = manifest.getJSONArray("entries")
    for (i in 0 until array.length()) {
        val item = array.getJSONObject(i)
        val name = item.getString("name")
        require(name in setOf("restore-point.json", "restore.sh", "restore.ps1", "README.txt") && name !in listed) {
            "Некорректная запись в манифесте"
        }
        listed[name] = item.getInt("size") to item.getString("sha256")
    }
    require(listed.keys == setOf("restore-point.json", "restore.sh", "restore.ps1", "README.txt")) {
        "Манифест неполный или содержит неизвестные записи"
    }
    require(files.keys == listed.keys + "manifest.json") { "Состав ZIP не совпадает с манифестом" }
    listed.forEach { (name, expected) ->
        val content = files[name] ?: throw Exception("В ZIP отсутствует " + name)
        require(content.size == expected.first && sha256Hex(content) == expected.second) {
            "Проверка целостности " + name + " не пройдена"
        }
    }
    val point = parseRestorePointBackup(files.getValue("restore-point.json").toString(Charsets.UTF_8))
    require(manifest.optString("deviceFingerprint") == point.fingerprint) { "Отпечаток в манифесте не совпадает с точкой" }
    return PortableRestoreBundle(point, files.getValue("restore.sh").toString(Charsets.UTF_8))
}

private fun buildPortableRestoreScript(
    fingerprint: String,
    managedUsers: Set<String>,
    snapshotUsers: Set<String>,
    managedSystems: Set<String>,
    snapshotSystems: Set<String>,
    managedAutoStart: Set<String>,
    snapshotAutoStart: Set<String>
): String {
    val identity = fingerprint.split("|")
    require(identity.size == 4 && identity.none { it.isBlank() || it == "неизвестно" }) {
        "В точке нет полной идентификации телевизора"
    }
    val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    val componentPattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")
    val userUniverse = managedUsers + snapshotUsers
    val systemUniverse = managedSystems + snapshotSystems
    val autoUniverse = managedAutoStart + snapshotAutoStart
    require(userUniverse.all { packagePattern.matches(it) }) { "В списке пользовательских пакетов есть некорректное имя" }
    require(systemUniverse.all { packagePattern.matches(it) && !isProtectedSystemPackage(it) }) {
        "В списке системных пакетов есть защищённый или некорректный пакет"
    }
    require(autoUniverse.all { componentPattern.matches(it) }) { "В списке автозапуска есть некорректный компонент" }
    require(userUniverse.isNotEmpty() || systemUniverse.isNotEmpty() || autoUniverse.isNotEmpty()) {
        "В точке нет изменений TV APK Installer для экспорта"
    }

    return buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("# TV APK Installer portable restore script")
        appendLine("# Restores only package/component states tracked by TV APK Installer.")
        appendLine("# Requires Android platform-tools (adb), an authorized TV and Bash.")
        appendLine("set -uo pipefail")
        appendLine()
        appendLine("command -v adb >/dev/null 2>&1 || { echo 'adb не найден: установи Android platform-tools.' >&2; exit 2; }")
        appendLine("adb get-state >/dev/null 2>&1 || { echo 'Телевизор не подключён или не авторизован в ADB.' >&2; exit 2; }")
        appendLine("actual_identity=$(printf '%s|%s|%s|%s' \\")
        appendLine("  \"$(adb shell getprop ro.product.manufacturer | tr -d '\\r')\" \\")
        appendLine("  \"$(adb shell getprop ro.product.model | tr -d '\\r')\" \\")
        appendLine("  \"$(adb shell getprop ro.product.device | tr -d '\\r')\" \\")
        appendLine("  \"$(adb shell getprop ro.build.fingerprint | tr -d '\\r')\")")
        appendLine("expected_identity=${shellSingleQuote(fingerprint)}")
        appendLine("if [[ \$actual_identity != \$expected_identity ]]; then")
        appendLine("  echo 'ОШИБКА: модель или прошивка не совпадает с точкой восстановления. Ничего не изменено.' >&2")
        appendLine("  exit 3")
        appendLine("fi")
        appendLine()
        appendLine("failed=0")
        appendLine("restore_state() {")
        appendLine("  local target=\$1 state=\$2")
        appendLine("  if [[ \$state == disabled ]]; then")
        appendLine("    adb shell pm disable-user --user 0 \"\$target\" >/dev/null || { echo \"Не удалось отключить: \$target\" >&2; failed=1; }")
        appendLine("  else")
        appendLine("    adb shell pm enable --user 0 \"\$target\" >/dev/null || { echo \"Не удалось включить: \$target\" >&2; failed=1; }")
        appendLine("  fi")
        appendLine("}")
        appendLine()
        appendLine("# Пользовательские приложения")
        userUniverse.sorted().forEach { pkg ->
            appendLine("restore_state ${shellSingleQuote(pkg)} ${if (pkg in snapshotUsers) "disabled" else "enabled"}")
        }
        appendLine()
        appendLine("# Системные пакеты, отключённые самим приложением")
        systemUniverse.sorted().forEach { pkg ->
            appendLine("restore_state ${shellSingleQuote(pkg)} ${if (pkg in snapshotSystems) "disabled" else "enabled"}")
        }
        appendLine()
        appendLine("# Компоненты автозапуска")
        autoUniverse.sorted().forEach { component ->
            appendLine("restore_state ${shellSingleQuote(component)} ${if (component in snapshotAutoStart) "disabled" else "enabled"}")
        }
        appendLine()
        appendLine("if [[ \$failed -ne 0 ]]; then")
        appendLine("  echo 'Восстановление выполнено частично. Повтори скрипт после проверки ошибок.' >&2")
        appendLine("  exit 1")
        appendLine("fi")
        appendLine("echo 'Готово: сохранённое состояние пакетов и автозапуска восстановлено.'")
    }
}

private fun readTvDetails(adb: Dadb): String {
    fun prop(name: String) = try { shellText(adb, "getprop $name").ifBlank { "неизвестно" } } catch (_: Exception) { "неизвестно" }
    fun safe(command: String) = try { shellText(adb, command).ifBlank { "неизвестно" } } catch (_: Exception) { "недоступно" }
    return buildString {
        appendLine("Производитель: ${prop("ro.product.manufacturer")}")
        appendLine("Модель: ${prop("ro.product.model")}")
        appendLine("Устройство: ${prop("ro.product.device")}")
        appendLine("Android: ${prop("ro.build.version.release")} (SDK ${prop("ro.build.version.sdk")})")
        appendLine("Тип: ${prop("ro.build.characteristics")}")
        appendLine("Сборка: ${prop("ro.build.display.id")}")
        appendLine("Патч безопасности: ${prop("ro.build.version.security_patch")}")
        appendLine("Разрешение: ${safe("wm size")}")
        appendLine("Плотность: ${safe("wm density")}")
        appendLine("Сеть: ${safe("ip route | head -n 2")}")
        appendLine("Отпечаток: ${prop("ro.build.fingerprint")}")
    }.trim()
}

private fun readPackageDisableCapability(adb: Dadb): Boolean {
    val help = try { shellText(adb, "pm help") } catch (_: Exception) { return false }
    val commandAdvertised = Regex("""(?m)^.*\bdisable-user\b.*$""").containsMatchIn(help)
    if (!commandAdvertised) return false
    return try {
        readDisabledPackages(adb)
        true
    } catch (_: Exception) {
        false
    }
}

private fun readTvCompatibilityReport(adb: Dadb): String {
    fun probe(label: String, command: String, success: (String) -> Boolean): String {
        val output = try { shellText(adb, command).trim() } catch (_: Exception) { "" }
        return "$label: " + if (output.isNotBlank() && success(output)) "доступно" else "не подтверждено"
    }
    val sdk = try { shellText(adb, "getprop ro.build.version.sdk").trim().toIntOrNull() } catch (_: Exception) { null }
    val packageDisable = readPackageDisableCapability(adb)
    return buildString {
        appendLine("ПРОВЕРКА СОВМЕСТИМОСТИ")
        appendLine("Версия Android SDK: ${sdk?.toString() ?: "не определена"}")
        appendLine(probe("Package Manager", "pm list packages", { it.contains("package:") }))
        appendLine("Отключение пакетов с проверкой результата: " + if (packageDisable) "поддерживается" else "не подтверждено — пакетная очистка заблокирована")
        appendLine(probe("Команды cmd package", "cmd package help", { it.isNotBlank() && !it.contains("not found", true) }))
        appendLine(probe("Системные настройки", "settings get secure android_id", { it.isNotBlank() }))
        appendLine(probe("Сведения о питании", "dumpsys power", { it.contains("power", true) }))
        appendLine()
        append("Проверка команд — предварительная. Каждая операция дополнительно проверяется на телевизоре; неподтверждённое отключение не будет считаться успешным.")
    }
}

private fun sendTvKey(adb: Dadb, keyCode: Int): String {
    require(keyCode in 0..300) { "Недопустимый код клавиши" }
    return shellText(adb, "input keyevent $keyCode")
}

private fun openTvAppInfo(adb: Dadb, packageName: String): String {
    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Некорректное имя пакета" }
    return shellText(adb, "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName")
}

private fun listUndoBackups(context: Context): List<File> {
    val dir = File(context.filesDir, "backups")
    return dir.listFiles()?.filter { file ->
        file.isFile && file.length() > 0L &&
            file.name.matches(Regex("undo-[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+-\\d+\\.zip"))
    }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

private fun undoBackupPackage(file: File): String =
    file.name.removePrefix("undo-").substringBeforeLast("-")

private fun undoBackupDate(file: File): String {
    val timestamp = file.name.removeSuffix(".zip").substringAfterLast("-").toLongOrNull()
        ?: file.lastModified()
    return java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
}

private fun uninstallUserApp(adb: Dadb, packageName: String, context: Context): String {
    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Некорректное имя пакета" }
    require(packageName in readUserPackages(adb)) { "Разрешено удалять только пользовательские приложения" }

    // Preserve every split APK before uninstall. If backup cannot be completed,
    // abort deletion so the user is not left without a reinstall source.
    val backupDir = File(context.filesDir, "backups")
    if (!backupDir.exists() && !backupDir.mkdirs()) {
        throw Exception("Не удалось создать папку резервных копий. Удаление отменено.")
    }
    val tempDir = File(context.cacheDir, "undo-" + packageName.replace('.', '_') + "-" + System.currentTimeMillis())
    if (!tempDir.mkdirs()) throw Exception("Не удалось создать временную папку. Удаление отменено.")
    val archive = File(backupDir, "undo-" + packageName + "-" + System.currentTimeMillis() + ".zip")
    try {
        val remotePaths = shellText(adb, "pm path $packageName").lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.startsWith("/") && !it.contains("..") }
            .distinct()
            .toList()
        require(remotePaths.isNotEmpty() && remotePaths.size <= 32) {
            "Не удалось получить полный список APK приложения. Удаление отменено."
        }
        val localFiles = remotePaths.mapIndexed { index, remote ->
            val file = File(tempDir, "split-" + index + ".apk")
            when (val result = adb.pull(file, remote)) {
                is dadb.SyncResult.Success -> Unit
                is dadb.SyncResult.Failure -> throw Exception("Не удалось сохранить APK: " + result)
            }
            require(file.isFile && file.length() in 1..(256L * 1024L * 1024L)) {
                "Резервная копия APK неполная или слишком большая. Удаление отменено."
            }
            file
        }
        require(localFiles.sumOf { it.length() } <= 512L * 1024L * 1024L) {
            "Размер резервной копии превышает 512 МБ. Удаление отменено."
        }
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("applications.txt"))
            zip.write(packageName.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            localFiles.forEachIndexed { index, file ->
                val entryName = if (index == 0) "base.apk" else "split-" + index + ".apk"
                zip.putNextEntry(ZipEntry("apks/" + packageName + "/" + entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(archive.isFile && archive.length() > 0L) {
            "Не удалось проверить резервную копию. Удаление отменено."
        }
        val result = shellText(adb, "pm uninstall --user 0 $packageName")
        require(result.contains("Success", ignoreCase = true) && packageName !in readUserPackages(adb)) {
            "Android не подтвердил удаление. Резервная копия сохранена: " + archive.name + ". Ответ: " + result
        }
        return "Приложение удалено: " + packageName + ". Резервная копия APK сохранена: " + archive.name
    } catch (e: Exception) {
        if (archive.exists() && archive.length() == 0L) archive.delete()
        throw e
    } finally {
        tempDir.deleteRecursively()
    }
}

private fun readAllPackages(adb: Dadb): List<String> =
    shellText(adb, "pm list packages").lineSequence()
        .map { it.removePrefix("package:").trim() }
        .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
        .distinct().sorted().toList()

private fun readUserPackages(adb: Dadb): List<String> =
    shellText(adb, "pm list packages -3").lineSequence()
        .map { it.removePrefix("package:").trim() }
        .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
        .distinct().sorted().toList()
private fun changePackageState(adb: Dadb, packageName: String, enabled: Boolean): String {
    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Некорректное имя пакета" }
    return shellText(adb, if (enabled) "pm enable --user 0 $packageName" else "pm disable-user --user 0 $packageName")
}

private fun launchTvApp(adb: Dadb, packageName: String): String {
    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Некорректное имя пакета" }
    return shellText(adb, "monkey -p $packageName -c android.intent.category.LAUNCHER 1")
}

private const val CLEAN_TV_LAUNCHER_PACKAGE = "com.gathin.tvhome"
private const val CLEAN_TV_LAUNCHER_COMPONENT = "com.gathin.tvhome/.MainActivity"
private val KNOWN_STOCK_HOME_PACKAGES = setOf(
    "com.google.android.tvlauncher",
    "com.google.android.apps.tv.launcherx",
    "com.google.android.leanbacklauncher",
    "com.mitv.tvhome"
)

private fun resolveHomeComponent(adb: Dadb, packageFilter: String? = null): String? {
    val base = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    val output = try {
        shellText(adb, if (packageFilter == null) base else "$base $packageFilter")
    } catch (_: Exception) {
        return null
    }
    return output.lineSequence().map { it.trim() }.lastOrNull {
        it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+"))
    }
}

private fun handOverToCleanYourTvLauncher(context: Context, adb: Dadb): String {
    val currentHome = resolveHomeComponent(adb)
        ?: throw Exception("Не удалось определить текущий домашний экран. Ничего не изменено.")
    val currentPackage = currentHome.substringBefore("/")
    if (currentPackage == CLEAN_TV_LAUNCHER_PACKAGE) {
        return "Clean Your TV Launcher уже назначен домашним экраном."
    }
    require(currentPackage in KNOWN_STOCK_HOME_PACKAGES) {
        "Текущий Home — $currentHome. Автоматическое переключение разрешено только для проверенных штатных пакетов Android TV. Выбери Launcher вручную в системных настройках."
    }
    val stockComponent = resolveHomeComponent(adb, currentPackage)
        ?: throw Exception("Не удалось получить компонент штатного Home $currentPackage. Штатный экран оставлен без изменений.")
    require(stockComponent.startsWith("$currentPackage/")) {
        "Телевизор вернул неожиданный компонент для штатного Home: $stockComponent. Переключение отменено."
    }

    // Save recovery data before the first state-changing command. If ADB drops mid-handover,
    // the user can reconnect and use the rollback button without guessing the original Home.
    prefs(context).edit()
        .putString("launcher_handover_stock_package", currentPackage)
        .putString("launcher_handover_stock_component", stockComponent)
        .putBoolean("launcher_handover_active", false)
        .apply()

    // A failed launch happens before any TV state is changed. Clear the
    // provisional recovery record so the UI cannot offer a misleading rollback.
    val start = try {
        shellText(adb, "am start -W -n $CLEAN_TV_LAUNCHER_COMPONENT")
    } catch (launchError: Exception) {
        prefs(context).edit()
            .remove("launcher_handover_stock_package")
            .remove("launcher_handover_stock_component")
            .remove("launcher_handover_active")
            .apply()
        throw Exception("Не удалось запустить Clean Your TV Launcher. Штатный экран не изменён: ${errorText(launchError)}", launchError)
    }
    if (!start.contains("Status: ok", ignoreCase = true) || start.contains("Error:", ignoreCase = true)) {
        prefs(context).edit()
            .remove("launcher_handover_stock_package")
            .remove("launcher_handover_stock_component")
            .remove("launcher_handover_active")
            .apply()
        throw Exception("Новый Launcher не прошёл проверку запуска. Штатный экран не отключался. Ответ: $start")
    }

    try {
        changePackageStateVerified(adb, currentPackage, false)
        shellText(adb, "cmd package set-home-activity --user 0 $CLEAN_TV_LAUNCHER_COMPONENT")
        val after = resolveHomeComponent(adb)
        require(after?.startsWith("$CLEAN_TV_LAUNCHER_PACKAGE/") == true) {
            "Android не подтвердил новый Home (получено: ${after ?: "нет ответа"})."
        }
        prefs(context).edit().putBoolean("launcher_handover_active", true).apply()
        return "Clean Your TV Launcher назначен главным экраном. Штатный пакет $currentPackage сохранён в записи отката."
    } catch (handoverError: Exception) {
        val rollbackResult = try {
            changePackageStateVerified(adb, currentPackage, true)
            shellText(adb, "cmd package set-home-activity --user 0 $stockComponent")
            val restored = resolveHomeComponent(adb)
            require(restored?.startsWith("$currentPackage/") == true) {
                "Android вернул неожиданный Home: ${restored ?: "нет ответа"}"
            }
            prefs(context).edit()
                .remove("launcher_handover_stock_package")
                .remove("launcher_handover_stock_component")
                .remove("launcher_handover_active")
                .apply()
            "Штатный Home восстановлен автоматически."
        } catch (rollbackError: Exception) {
            "Автоматический откат не подтверждён: ${errorText(rollbackError)}. Запись восстановления сохранена — повтори откат после подключения к телевизору."
        }
        throw Exception("${errorText(handoverError)}\n$rollbackResult", handoverError)
    }
}

private fun restoreStockHomeAfterHandover(context: Context, adb: Dadb): String {
    val store = prefs(context)
    val stockPackage = store.getString("launcher_handover_stock_package", null)
        ?: throw Exception("Нет сохранённой записи переключения Home.")
    val stockComponent = store.getString("launcher_handover_stock_component", null)
        ?: throw Exception("В записи отката отсутствует компонент штатного Home.")
    require(stockPackage in KNOWN_STOCK_HOME_PACKAGES && stockComponent.startsWith("$stockPackage/")) {
        "Запись отката не прошла проверку безопасности. Ничего не изменено."
    }
    require(shellText(adb, "pm path $stockPackage").contains("package:")) {
        "Штатный Launcher $stockPackage больше не установлен. Установи его средствами производителя или выбери другой Home."
    }
    changePackageStateVerified(adb, stockPackage, true)
    shellText(adb, "cmd package set-home-activity --user 0 $stockComponent")
    val restored = resolveHomeComponent(adb)
    require(restored?.startsWith("$stockPackage/") == true) {
        "Не удалось подтвердить возврат штатного Home. Получено: ${restored ?: "нет ответа"}. Запись отката сохранена."
    }
    store.edit()
        .remove("launcher_handover_stock_package")
        .remove("launcher_handover_stock_component")
        .remove("launcher_handover_active")
        .apply()
    return "Штатный домашний экран восстановлен: $restored. Clean Your TV Launcher не удалялся."
}


private data class AutoStartReceiver(val component: String, val packageName: String)

private fun findAutoStartReceivers(adb: Dadb): List<AutoStartReceiver> {
    val userPackages = readUserPackages(adb).toSet()
    val actions = listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED")
    val found = linkedMapOf<String, AutoStartReceiver>()
    actions.forEach { action ->
        val output = shellText(adb, "cmd package query-receivers --brief --components -a $action")
        output.lineSequence().forEach { line ->
            val component = Regex("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+)")
                .find(line)?.groupValues?.get(1) ?: return@forEach
            val packageName = component.substringBefore("/")
            if (packageName in userPackages) found[component] = AutoStartReceiver(component, packageName)
        }
    }
    return found.values.sortedBy { it.component }
}

private fun setAutoStartReceiverEnabled(adb: Dadb, component: String, enabled: Boolean): String {
    require(component.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+"))) {
        "Некорректный компонент автозапуска"
    }
    val command = if (enabled) "pm enable --user 0 $component" else "pm disable-user --user 0 $component"
    return shellText(adb, command)
}

private fun readDisabledUserPackages(adb: Dadb): Set<String> =
    shellText(adb, "pm list packages -3 -d").lineSequence()
        .map { it.removePrefix("package:").trim() }
        .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
        .toSet()

private fun readDisabledPackages(adb: Dadb): Set<String> =
    shellText(adb, "pm list packages -d").lineSequence()
        .map { it.removePrefix("package:").trim() }
        .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
        .toSet()

/**
 * Executes a package state change and verifies the resulting state against Package Manager.
 * A successful shell exit alone is not treated as proof that Android applied the change.
 */
private fun changePackageStateVerified(adb: Dadb, packageName: String, enabled: Boolean): String {
    val result = changePackageState(adb, packageName, enabled)
    val disabled = packageName in readDisabledPackages(adb)
    val expected = if (enabled) !disabled else disabled
    require(expected) {
        "Android не подтвердил " + (if (enabled) "включение" else "отключение") + " пакета " + packageName
    }
    return result
}


private fun createTvBackup(adb: Dadb, context: Context): File {
    val packages = readUserPackages(adb)
    val tempDir = File(context.cacheDir, "tv-backup-temp")
    if (tempDir.exists()) tempDir.deleteRecursively()
    if (!tempDir.mkdirs()) throw Exception("Не удалось создать временную папку резервной копии")

    val backupDir = File(context.cacheDir, "backups")
    if (!backupDir.exists() && !backupDir.mkdirs()) throw Exception("Не удалось создать папку резервных копий")
    val archive = File(backupDir, "tv-backup-${System.currentTimeMillis()}.zip")
    val failures = mutableListOf<String>()
    var savedApks = 0

    ZipOutputStream(FileOutputStream(archive)).use { zip ->
        fun addText(name: String, value: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(value.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        addText("device-info.txt", readTvDetails(adb))
        addText("applications.txt", packages.joinToString("\n"))
        packages.forEach { pkg ->
            try {
                val paths = shellText(adb, "pm path $pkg").lineSequence()
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.startsWith("/") }
                    .toList()
                if (paths.isEmpty()) throw Exception("Пути APK не найдены")
                paths.forEachIndexed { index, remotePath ->
                    val local = File(tempDir, "${pkg.replace('.', '_')}_${index}.apk")
                    when (val pullResult = adb.pull(local, remotePath)) {
                        is dadb.SyncResult.Success -> Unit
                        is dadb.SyncResult.Failure -> throw Exception(pullResult.toString())
                    }
                    if (!local.exists() || local.length() == 0L) throw Exception("Пустой APK")
                    zip.putNextEntry(ZipEntry("apks/$pkg/${local.name}"))
                    local.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    savedApks++
                    local.delete()
                }
            } catch (e: Exception) {
                failures.add("$pkg: ${e.message ?: "ошибка копирования"}")
            }
        }
        addText(
            "backup-report.txt",
            "Всего пользовательских приложений: ${packages.size}\nСохранено APK-файлов: $savedApks\nОшибок по приложениям: ${failures.size}\n\n" +
                if (failures.isEmpty()) "Ошибок нет" else failures.joinToString("\\n")
        )
    }
    tempDir.deleteRecursively()
    return archive
}

private fun restoreTvBackup(adb: Dadb, context: Context, archive: File): String {
    val maxArchiveBytes = 512L * 1024L * 1024L
    val maxApkBytes = 256L * 1024L * 1024L
    val maxTotalBytes = 512L * 1024L * 1024L
    val maxEntries = 512
    val maxPerPackage = 32
    require(archive.isFile && archive.length() in 1..maxArchiveBytes) {
        "Архив отсутствует, пуст или превышает 512 МиБ"
    }
    data class ApkFile(val name: String, val file: File)
    val dir = File(context.cacheDir, "tv-restore-" + System.currentTimeMillis())
    if (dir.exists()) dir.deleteRecursively()
    if (!dir.mkdirs()) throw Exception("Не удалось создать временную папку восстановления")
    val grouped = linkedMapOf<String, MutableList<ApkFile>>()
    val names = mutableSetOf<String>()
    var entries = 0
    var totalBytes = 0L
    var metadataBytes = 0L
    try {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > maxEntries) throw Exception("В архиве больше 512 записей")
                if (!names.add(entry.name)) throw Exception("Повторяющаяся запись ZIP: " + entry.name)
                if (entry.name.startsWith("/") || entry.name.contains("\\") ||
                    entry.name.split("/").any { it == ".." || it == "." }) {
                    throw Exception("Недопустимый путь в ZIP: " + entry.name)
                }
                if (entry.isDirectory) {
                    val parts = entry.name.removeSuffix("/").split("/")
                    val validDir = entry.name == "apks/" || entry.name == "metadata/" ||
                        (parts.size == 2 && parts[0] == "apks" &&
                            parts[1].matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")))
                    if (!validDir) throw Exception("Неизвестная папка в ZIP: " + entry.name)
                } else {
                    val parts = entry.name.split("/")
                    val apkEntry = parts.size == 3 && parts[0] == "apks"
                    if (apkEntry) {
                        val pkg = parts[1]
                        val fileName = parts[2]
                        if (!pkg.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) ||
                            !fileName.matches(Regex("[A-Za-z0-9._-]+\\.apk", RegexOption.IGNORE_CASE))) {
                            throw Exception("Недопустимое имя APK: " + entry.name)
                        }
                        val list = grouped.getOrPut(pkg) { mutableListOf() }
                        if (list.size >= maxPerPackage) throw Exception("Слишком много APK для " + pkg)
                        val target = File(dir, pkg.replace('.', '_') + "_" + list.size + ".apk")
                        var fileBytes = 0L
                        val header = ByteArray(4)
                        var headerSize = 0
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                fileBytes += count
                                totalBytes += count
                                if (fileBytes > maxApkBytes) throw Exception("APK больше 256 МиБ: " + fileName)
                                if (totalBytes > maxTotalBytes) throw Exception("Распакованные APK превышают 512 МиБ")
                                var pos = 0
                                while (headerSize < header.size && pos < count) {
                                    header[headerSize++] = buffer[pos++]
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                        if (fileBytes < 4L || header[0] != 0x50.toByte() || header[1] != 0x4b.toByte()) {
                            target.delete()
                            throw Exception("Файл не похож на APK/ZIP: " + fileName)
                        }
                        list.add(ApkFile(fileName, target))
                    } else {
                        val allowed = entry.name == "device-info.txt" ||
                            entry.name == "applications.txt" ||
                            entry.name == "backup-report.txt" ||
                            (parts.size == 2 && parts[0] == "metadata" &&
                                parts[1].matches(Regex("[A-Za-z0-9._-]+\\.txt", RegexOption.IGNORE_CASE)))
                        if (!allowed) throw Exception("Неизвестная запись в архиве: " + entry.name)
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            metadataBytes += count
                            if (metadataBytes > 2L * 1024L * 1024L) throw Exception("Метаданные больше 2 МиБ")
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (grouped.isEmpty()) throw Exception("В архиве не найдены APK из резервной копии")
        var installed = 0
        val failures = mutableListOf<String>()
        grouped.forEach { (pkg, apks) ->
            try {
                val ordered = apks.sortedWith(
                    compareBy<ApkFile> { if (it.name.equals("base.apk", true)) 0 else 1 }
                        .thenBy { apks.indexOf(it) }
                ).map { it.file }
                val result = if (ordered.size > 1) adb.installMultiple(ordered) else adb.install(ordered.first())
                when (result) {
                    is InstallResult.Success -> installed++
                    is InstallResult.Failure -> failures.add(pkg + ": " + result.reason)
                }
            } catch (e: Exception) {
                failures.add(pkg + ": " + (e.message ?: "ошибка установки"))
            }
        }
        return "Восстановлено приложений: " + installed + " из " + grouped.size +
            ". Ошибок: " + failures.size +
            if (failures.isEmpty()) "" else "\n" + failures.take(10).joinToString("\n")
    } finally {
        dir.deleteRecursively()
    }
}
private fun isProtectedSystemPackage(packageName: String): Boolean {
    // Core Android / TV middleware: upstream Clean Your TV recovery rules prohibit
    // disabling these because a TV can lose setup, networking, package management or boot.
    val protected = setOf(
        "android",
        "android.ext.services",
        "android.ext.shared",
        "com.android.sdksandbox",
        "com.android.location.fused",
        "com.android.systemui",
        "com.android.shell",
        "com.android.phone",
        "com.android.settings",
        "com.android.tv.settings",
        "com.android.providers.settings",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.android.ext.services",
        "com.android.ext.shared",
        "com.android.modulemetadata",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.google.android.ext.services",
        "com.google.android.ext.shared",
        "com.google.android.modulemetadata",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gsf.login",
        "com.google.android.webview",
        "com.google.android.tvlauncher",
        "com.google.android.tv",
        "com.android.vending",
        "com.cltv.mal",
        "com.mediatek.tis",
        "com.mediatek.tv.service",
        "com.mediatek.tv.agent"
    )
    val allowedProviders = setOf(
        "com.android.providers.calendar",
        "com.android.providers.contacts",
        "com.android.providers.userdictionary",
        "com.android.providers.blockednumber"
    )
    return packageName in protected ||
        (packageName.startsWith("com.android.providers.") && packageName !in allowedProviders) ||
        packageName.startsWith("com.android.systemui.") ||
        packageName.startsWith("com.google.android.gms.") ||
        packageName.startsWith("com.google.android.overlay.modules")
}

private fun detectTvProfile(details: String): String {
    val value = details.lowercase()
    val brand = when {
        "tcl" in value -> "TCL"
        "hisense" in value -> "Hisense"
        "xiaomi" in value || "redmi" in value -> "Xiaomi"
        "sony" in value -> "Sony"
        "philips" in value -> "Philips"
        "amazon" in value || "fire" in value -> "Amazon Fire TV"
        "samsung" in value -> "Samsung"
        "lg" in value -> "LG"
        else -> "Универсальный профиль"
    }
    val platform = when {
        "fire" in value || "amazon" in value -> "Fire TV / Fire OS"
        "google" in value -> "Google TV / Android TV"
        "android" in value -> "Android TV / Android"
        "vidaa" in value -> "VIDAA (не Android)"
        else -> "Android-совместимое устройство"
    }
    return "$brand • $platform"
}

private data class PackageFact(
    val packageName: String,
    val name: String,
    val description: String,
    val category: String
)

private fun loadPackageFacts(context: Context): Map<String, PackageFact> {
    return try {
        context.assets.open("cleanyourtv/facts.tsv").bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val columns = line.split('\t', limit = 4)
                if (columns.size < 4 || columns[0].isBlank()) null
                else PackageFact(columns[0], columns[1], columns[2], columns[3])
            }.associateBy { it.packageName }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}


private data class TvProfilePackage(val category: String, val ask: Boolean)

private data class TvModelProfile(
    val id: String,
    val platform: String,
    val models: Set<String>,
    val devices: Set<String>,
    val brand: String,
    val sdkMin: Int?,
    val sdkMax: Int?,
    val fingerprintPrefix: String?,
    val neverDisable: Set<String>,
    val packages: Map<String, TvProfilePackage>,
    val testedBy: String,
    val testedFirmware: String,
    val rebootTested: Boolean
)

private data class TvProfileMatch(
    val profile: TvModelProfile,
    val matchLabel: String
)

private fun loadTvModelProfiles(context: Context): List<TvModelProfile> {
    return try {
        val root = JSONObject(context.assets.open("cleanyourtv/profile-meta.json").bufferedReader().use { it.readText() })
        val profiles = root.optJSONObject("profiles") ?: return emptyList()
        val result = mutableListOf<TvModelProfile>()
        val keys = profiles.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val item = profiles.optJSONObject(id) ?: continue
            val match = item.optJSONObject("match") ?: JSONObject()
            fun stringSet(array: JSONArray?): Set<String> {
                if (array == null) return emptySet()
                return buildSet {
                    for (i in 0 until array.length()) {
                        val value = array.optString(i).trim()
                        if (value.isNotEmpty()) add(value)
                    }
                }
            }
            fun nullableInt(key: String): Int? =
                if (match.has(key) && !match.isNull(key)) match.optInt(key).takeIf { it >= 0 } else null

            val packageMap = linkedMapOf<String, TvProfilePackage>()
            val packageJson = item.optJSONObject("packages") ?: JSONObject()
            val packageKeys = packageJson.keys()
            while (packageKeys.hasNext()) {
                val packageName = packageKeys.next()
                val packageInfo = packageJson.optJSONObject(packageName) ?: continue
                packageMap[packageName] = TvProfilePackage(
                    packageInfo.optString("category", "other"),
                    packageInfo.optBoolean("ask", true)
                )
            }
            val tested = item.optJSONObject("tested") ?: JSONObject()
            result += TvModelProfile(
                id = id,
                platform = item.optString("platform", "Android TV"),
                models = stringSet(match.optJSONArray("models")),
                devices = stringSet(match.optJSONArray("devices")),
                brand = match.optString("brand", ""),
                sdkMin = nullableInt("sdkMin"),
                sdkMax = nullableInt("sdkMax"),
                fingerprintPrefix = match.optString("fingerprintPrefix").takeIf { it.isNotBlank() && it != "null" },
                neverDisable = stringSet(item.optJSONArray("neverDisable")),
                packages = packageMap,
                testedBy = tested.optString("by", "").takeIf { it.isNotBlank() && it != "null" } ?: "не указано",
                testedFirmware = tested.optString("firmware", "").takeIf { it.isNotBlank() && it != "null" } ?: "не указана",
                rebootTested = tested.optBoolean("rebootTested", false)
            )
        }
        result
    } catch (_: Exception) {
        emptyList()
    }
}

private fun matchTvModelProfile(details: String, profiles: List<TvModelProfile>): TvProfileMatch? {
    fun field(label: String) = details.lineSequence()
        .firstOrNull { it.trim().startsWith("$label:", ignoreCase = true) }
        ?.substringAfter(":")?.trim().orEmpty()

    val manufacturer = field("Производитель")
    val model = field("Модель")
    val device = field("Устройство")
    val sdk = Regex("SDK\\s+(\\d+)", RegexOption.IGNORE_CASE)
        .find(field("Android"))?.groupValues?.getOrNull(1)?.toIntOrNull()
    val fingerprint = field("Отпечаток")

    return profiles.mapNotNull { profile ->
        val modelHit = profile.models.any { it.equals(model, ignoreCase = true) }
        val deviceHit = profile.devices.any { it.equals(device, ignoreCase = true) }
        val fingerprintHit = profile.fingerprintPrefix?.let { fingerprint.startsWith(it, ignoreCase = true) } == true
        val hasIdentityRule = profile.models.isNotEmpty() || profile.devices.isNotEmpty() || profile.fingerprintPrefix != null
        if (!hasIdentityRule || !(modelHit || deviceHit || fingerprintHit)) return@mapNotNull null
        if (sdk != null && profile.sdkMin != null && sdk < profile.sdkMin) return@mapNotNull null
        if (sdk != null && profile.sdkMax != null && sdk > profile.sdkMax) return@mapNotNull null

        val brandHit = profile.brand.isNotBlank() &&
            (manufacturer.contains(profile.brand, ignoreCase = true) || profile.brand.contains(manufacturer, ignoreCase = true))
        val score = (if (modelHit) 100 else 0) + (if (fingerprintHit) 80 else 0) +
            (if (deviceHit) 50 else 0) + (if (brandHit) 10 else 0)
        val label = when {
            modelHit -> "точная модель"
            fingerprintHit -> "совпадение прошивки"
            else -> "совпадение кода устройства"
        }
        Triple(score, profile, label)
    }.maxByOrNull { it.first }?.let { TvProfileMatch(it.second, it.third) }
}

private fun tvProfileDisplayName(id: String): String = id
    .replace('-', ' ').replace('_', ' ')
    .split(' ').filter { it.isNotBlank() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

private fun cleanupCategoryLabel(category: String): String = when (category) {
    "accessibility" -> "Специальные возможности"
    "ads" -> "Реклама и рекомендации"
    "brand" -> "Сервисы производителя"
    "casting" -> "Трансляция и передача"
    "demo" -> "Демо-компоненты"
    "home" -> "Домашний экран"
    "launcher" -> "Оболочка и запуск"
    "other" -> "Прочие компоненты"
    "preloads" -> "Предустановленные приложения"
    "screensaver" -> "Заставка"
    "streaming" -> "Стриминг и видео"
    "system" -> "Системные компоненты"
    "tracking" -> "Телеметрия и аналитика"
    "updates" -> "Обновления"
    "voice" -> "Голосовые функции"
    else -> category
}

private fun findCleanupCandidates(
    packages: List<String>,
    facts: Map<String, PackageFact>,
    profile: TvModelProfile? = null
): List<String> {
    val protected = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.tv.settings",
        "com.android.systemui",
        "com.android.providers.settings"
    )
    val reviewedCategories = setOf("ads", "tracking", "demo", "preloads")
    val markers = listOf("ads", "advert", "tracking", "analytics", "promo", "promotion", "feedback", "demo")
    return packages.filter { pkg ->
        pkg !in protected && pkg !in (profile?.neverDisable ?: emptySet()) && (
            facts[pkg]?.category?.let { it in reviewedCategories } == true ||
                profile?.packages?.get(pkg)?.category?.let { it in reviewedCategories } == true ||
                markers.any { marker -> pkg.substringAfterLast('.').contains(marker) || pkg.contains(".$marker") }
            )
    }.distinct().sorted()
}


@Composable
private fun DashboardTile(
    title: String,
    subtitle: String,
    symbol: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.height(118.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF30230F) else Color(0xFF17130C)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Color(0xFFD6A84F) else Color(0xFF5A4525)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(13.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(symbol, fontSize = 23.sp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Color(0xFFF4D58A), style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = Color(0xFFBDB4A3), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun TvInstallerApp() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val uriHandler = LocalUriHandler.current

    val settings = remember { prefs(context) }
    val packageFacts = remember { loadPackageFacts(context) }
    val tvModelProfiles = remember { loadTvModelProfiles(context) }

    var ip by remember {
        mutableStateOf(settings.getString("ip", "") ?: "")
    }
    var port by remember {
        mutableStateOf(settings.getString("port", "5555") ?: "5555")
    }
    var tvName by remember { mutableStateOf("") }

    var status by remember { mutableStateOf("Не подключено") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("APK не выбран") }

    var busy by remember { mutableStateOf(false) }
    var tvDetails by remember { mutableStateOf("Подключись к телевизору и выполни определение устройства.") }
    var tvCompatibilityReport by remember { mutableStateOf("") }
    var packageDisableSupported by remember { mutableStateOf(false) }
    val matchedTvProfile = remember(tvDetails, tvModelProfiles) { matchTvModelProfile(tvDetails, tvModelProfiles) }
    var diagnostics by remember { mutableStateOf("Диагностика ещё не выполнялась.") }
    var userPackages by remember { mutableStateOf(listOf<String>()) }
    var allPackages by remember { mutableStateOf(listOf<String>()) }
    var disabledOnDevice by remember { mutableStateOf(emptySet<String>()) }
    var autoStartReceivers by remember { mutableStateOf(listOf<AutoStartReceiver>()) }
    var disabledAutoStart by remember { mutableStateOf(settings.getStringSet("disabled_autostart_components", emptySet())?.toSet() ?: emptySet()) }
    var pendingAutoStartDisable by remember { mutableStateOf<String?>(null) }
    var autoStartLoaded by remember { mutableStateOf(false) }
    var systemPackageFilter by remember { mutableStateOf("") }
    var pendingSystemDisable by remember { mutableStateOf<String?>(null) }
    var pendingRestoreManagedChanges by remember { mutableStateOf(false) }
    var selectedCleanupPackages by remember { mutableStateOf(emptySet<String>()) }
    var showCleanupPlan by remember { mutableStateOf(false) }
    var cleanupJournal by remember { mutableStateOf(loadCleanupJournal(context)) }
    var cleanupHistory by remember { mutableStateOf(loadCleanupHistory(context)) }
    var pendingCleanupRollback by remember { mutableStateOf(false) }
    var pendingCleanupHistoryUndo by remember { mutableStateOf<CleanupHistoryEntry?>(null) }
    var privacyAuditExpanded by remember { mutableStateOf(false) }
    var profileCatalogExpanded by remember { mutableStateOf(false) }
    var pendingCleanupPackages by remember { mutableStateOf(emptyList<String>()) }
    var disabledPackages by remember { mutableStateOf(settings.getStringSet("disabled_packages", emptySet())?.toSet() ?: emptySet()) }
    var managedSystemDisabledPackages by remember { mutableStateOf(settings.getStringSet("managed_system_disabled_packages", emptySet())?.toSet() ?: emptySet()) }
    var packageFilter by remember { mutableStateOf("") }
    var restorePoint by remember { mutableStateOf(settings.getStringSet("restore_point_user_disabled", emptySet())?.toSet() ?: emptySet()) }
    var restorePointManagedUserPackages by remember { mutableStateOf(settings.getStringSet("restore_point_managed_user_packages", emptySet())?.toSet() ?: emptySet()) }
    var restorePointManagedSystemPackages by remember { mutableStateOf(settings.getStringSet("restore_point_managed_system_packages", emptySet())?.toSet() ?: emptySet()) }
    var restorePointAutoStartComponents by remember { mutableStateOf(settings.getStringSet("restore_point_autostart_components", emptySet())?.toSet() ?: emptySet()) }
    var restorePointExists by remember { mutableStateOf(settings.getBoolean("restore_point_exists", false)) }
    var restorePointSchemaVersion by remember { mutableStateOf(settings.getInt("restore_point_schema_version", 1)) }
    var restorePointDeviceFingerprint by remember { mutableStateOf(settings.getString("restore_point_device_fingerprint", null)) }
    var pendingPortableRestoreScript by remember { mutableStateOf<String?>(null) }
    var pendingPortableRestoreBundle by remember { mutableStateOf<ByteArray?>(null) }


    var usbMode by remember { mutableStateOf(false) }
    var usbDevices by remember { mutableStateOf(emptyList<UsbDevice>()) }
    var showCleanYourTv by remember { mutableStateOf(false) }
    var showRemoteControl by remember { mutableStateOf(false) }
    var pendingAppUninstall by remember { mutableStateOf<String?>(null) }
    var localUndoBackups by remember { mutableStateOf(listUndoBackups(context)) }
    var pendingUndoBackupExport by remember { mutableStateOf<File?>(null) }
    var pendingUndoBackupRestore by remember { mutableStateOf<File?>(null) }
    var pendingUndoBackupDelete by remember { mutableStateOf<File?>(null) }
    var pendingModuleRemoval by remember { mutableStateOf(false) }
    var pendingLauncherHandover by remember { mutableStateOf(false) }
    var hasLauncherHandover by remember { mutableStateOf(settings.getString("launcher_handover_stock_package", null) != null) }
    var launcherColumns by remember { mutableStateOf(settings.getInt("launcher_columns", 6)) }
    var launcherRowsBelowDock by remember { mutableStateOf(settings.getInt("launcher_rows_below_dock", 1)) }
    var launcherDockTitle by remember { mutableStateOf(settings.getBoolean("launcher_dock_title", true)) }
    var launcherDockRow by remember { mutableStateOf(settings.getBoolean("launcher_dock_row", true)) }
    var launcherAnimations by remember { mutableStateOf(settings.getBoolean("launcher_animations", true)) }
    var launcherAdvancedEffects by remember { mutableStateOf(settings.getBoolean("launcher_advanced_effects", false)) }
    var launcherAppLibrary by remember { mutableStateOf(settings.getBoolean("launcher_app_library", false)) }
    var launcherIconOnlyButtons by remember { mutableStateOf(settings.getBoolean("launcher_icon_only_buttons", true)) }
    var customWallpaperFile by remember { mutableStateOf<File?>(null) }
    var customWallpaperName by remember { mutableStateOf("Изображение не выбрано") }
    var customWallpaperDim by remember { mutableStateOf(0f) }
    var launcherDockPackages by remember { mutableStateOf(settings.getString("launcher_dock_packages", "") ?: "") }
    var launcherHiddenPackages by remember { mutableStateOf(settings.getString("launcher_hidden_packages", "") ?: "") }
    var launcherAvailablePackages by remember { mutableStateOf(emptyList<String>()) }
    var launcherFillDock by remember { mutableStateOf(settings.getBoolean("launcher_fill_dock", false)) }
    var launcherAppFilter by remember { mutableStateOf("") }
    var launcherIdleClockMinutes by remember { mutableStateOf(settings.getInt("launcher_idle_clock_minutes", 0)) }
    var launcherClockWallpaper by remember { mutableStateOf(settings.getBoolean("launcher_clock_wallpaper", false)) }
    var launcherClockWallpaperDim by remember { mutableStateOf(settings.getInt("launcher_clock_wallpaper_dim", 50).toFloat()) }
    var launcherClockGlow by remember { mutableStateOf(settings.getBoolean("launcher_clock_glow", true)) }
    var launcherBackOpensClock by remember { mutableStateOf(settings.getBoolean("launcher_back_opens_clock", false)) }
    var launcherClockFormat by remember { mutableStateOf(settings.getString("launcher_clock_format", "system") ?: "system") }
    var launcherWallpaperRotationMinutes by remember { mutableStateOf(settings.getInt("launcher_wallpaper_rotation_minutes", 0).coerceIn(0, 120)) }
    var launcherWallpaperOrder by remember { mutableStateOf(settings.getString("launcher_wallpaper_order", "shuffle") ?: "shuffle") }
    var pendingWallpaperCollectionClear by remember { mutableStateOf(false) }
    var launcherRemoteKeyMappings by remember { mutableStateOf(settings.getString("launcher_remote_key_mappings", "") ?: "") }
    var pendingRemoteKeyEnable by remember { mutableStateOf(false) }
    var pendingHomeKeyServiceEnable by remember { mutableStateOf(false) }
    var launcherHomeLongPressHome by remember { mutableStateOf(settings.getBoolean("launcher_home_long_press_home", false)) }
    var pendingLauncherConfigExport by remember { mutableStateOf<String?>(null) }
    var pendingLauncherBackupFile by remember { mutableStateOf<File?>(null) }
    var pendingLauncherBackupHasWallpaper by remember { mutableStateOf(false) }
    var pendingLauncherBackupCollectionCount by remember { mutableStateOf(0) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var activeSection by remember { mutableStateOf<String?>(null) }
    var isDarkTheme by remember { mutableStateOf(settings.getBoolean("dark_theme", true)) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    val Bg = if (isDarkTheme) Color(0xFF080808) else Color(0xFFF5F2EA)
    val Panel = if (isDarkTheme) Color(0xFF17130C) else Color(0xFFFFFFFF)
    val Accent = Color(0xFFD6A84F)
    val GoldLight = if (isDarkTheme) Color(0xFFF4D58A) else Color(0xFF8A641D)
    val TextMain = if (isDarkTheme) Color(0xFFF7F1E5) else Color(0xFF211B12)
    val TextMuted = if (isDarkTheme) Color(0xFFBDB4A3) else Color(0xFF665D50)

    var versionStatus by remember {
        mutableStateOf("Проверка ещё не выполнялась")
    }
    var releaseUrl by remember { mutableStateOf<String?>(null) }
    var updateApkUrl by remember { mutableStateOf<String?>(null) }
    var updateApkSha256 by remember { mutableStateOf<String?>(null) }
    var downloadedUpdatePath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        Thread {
            val result = try {
                checkLatestVersion()
            } catch (e: Exception) {
                UpdateInfo("Не удалось проверить обновления: ${errorText(e)}", null, null, 0L)
            }
            activity.runOnUiThread {
                versionStatus = result.status
                releaseUrl = result.releaseUrl
                updateApkUrl = result.apkUrl
                updateApkSha256 = result.expectedSha256
            }
        }.start()
    }

    val devices = remember {
        mutableStateListOf<TvDevice>().apply {
            addAll(loadDevices(context))
        }
    }

    val history = remember {
        mutableStateListOf<String>().apply {
            addAll(loadHistory(context))
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        selectedUri = uri
        selectedName = uri?.lastPathSegment ?: "APK не выбран"
    }

    val settingsBackupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destinationUri ->
        if (destinationUri != null) {
            try {
                context.contentResolver.openOutputStream(destinationUri)?.bufferedWriter()?.use {
                    it.write(exportAppSettings(context))
                } ?: throw Exception("Не удалось создать файл")
                status = "Резервная копия настроек сохранена"
            } catch (e: Exception) {
                status = "Ошибка экспорта настроек: ${errorText(e)}"
            }
        }
    }

    val cleanupHistoryExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destinationUri ->
        if (destinationUri != null) {
            try {
                val archive = JSONArray().apply {
                    cleanupHistory.forEach { entry ->
                        put(JSONObject()
                            .put("createdAt", entry.createdAt)
                            .put("deviceIdentity", entry.deviceIdentity)
                            .put("plannedCount", entry.plannedCount)
                            .put("changedPackages", JSONArray(entry.changedPackages))
                            .put("baselineDisabledCount", entry.baselineDisabledCount)
                            .put("outcome", entry.outcome))
                    }
                }
                val document = JSONObject()
                    .put("format", "tv-apk-installer-cleanup-history")
                    .put("version", 1)
                    .put("exportedAt", System.currentTimeMillis())
                    .put("operations", archive)
                context.contentResolver.openOutputStream(destinationUri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(document.toString(2))
                } ?: throw Exception("Не удалось создать файл")
                status = "История очистки экспортирована (${cleanupHistory.size} операций)"
            } catch (e: Exception) {
                status = "Ошибка экспорта истории: ${errorText(e)}"
            }
        }
    }

    val settingsBackupImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            try {
                val json = context.contentResolver.openInputStream(sourceUri)?.let { readBoundedUtf8(it, 4 * 1024 * 1024) }
                    ?: throw Exception("Не удалось прочитать файл")
                importAppSettings(context, json)
                status = "Настройки восстановлены. Перезапускаю приложение..."
                activity.recreate()
            } catch (e: Exception) {
                status = "Ошибка восстановления: ${errorText(e)}"
            }
        }
    }



    fun runTask(
        action: () -> String,
        onFinished: (String) -> Unit = {}
    ) {
        if (busy) return
        busy = true

        Thread {
            val result = try {
                action()
            } catch (e: Exception) {
                "Ошибка: ${errorText(e)}"
            }

            activity.runOnUiThread {
                status = result
                busy = false
                onFinished(result)
            }
        }.start()
    }

    val restorePointJsonExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destinationUri ->
        if (destinationUri != null) {
            try {
                val json = buildRestorePointBackupJson(
                    fingerprint = restorePointDeviceFingerprint ?: throw Exception("Создай точку заново"),
                    userDisabled = restorePoint,
                    managedUsers = restorePointManagedUserPackages,
                    managedSystems = restorePointManagedSystemPackages,
                    autoStartComponents = restorePointAutoStartComponents
                )
                context.contentResolver.openOutputStream(destinationUri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(json)
                } ?: throw Exception("Не удалось создать файл")
                status = "Точка восстановления экспортирована в JSON"
            } catch (e: Exception) {
                status = "Ошибка экспорта точки: ${errorText(e)}"
            }
        }
    }

    val restorePointJsonImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            try {
                val json = context.contentResolver.openInputStream(sourceUri)?.let { readBoundedUtf8(it, 2 * 1024 * 1024) }
                    ?: throw Exception("Не удалось прочитать файл")
                val point = parseRestorePointBackup(json)
                restorePoint = point.userDisabled
                restorePointManagedUserPackages = point.managedUsers
                restorePointManagedSystemPackages = point.managedSystems
                restorePointAutoStartComponents = point.autoStartComponents
                restorePointDeviceFingerprint = point.fingerprint
                restorePointSchemaVersion = 2
                restorePointExists = true
                settings.edit()
                    .putStringSet("restore_point_user_disabled", point.userDisabled)
                    .putStringSet("restore_point_managed_user_packages", point.managedUsers)
                    .putStringSet("restore_point_managed_system_packages", point.managedSystems)
                    .putStringSet("restore_point_autostart_components", point.autoStartComponents)
                    .putString("restore_point_device_fingerprint", point.fingerprint)
                    .putInt("restore_point_schema_version", 2)
                    .putBoolean("restore_point_exists", true)
                    .apply()
                status = "Точка импортирована. При восстановлении будет проверена модель и прошивка телевизора."
            } catch (e: Exception) {
                status = "Ошибка импорта точки: ${errorText(e)}"
            }
        }
    }

    val portableRestoreBundleExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { destinationUri ->
        val bundle = pendingPortableRestoreBundle
        if (destinationUri != null && bundle != null) {
            try {
                context.contentResolver.openOutputStream(destinationUri)?.use { it.write(bundle) }
                    ?: throw Exception("Не удалось создать ZIP-файл")
                status = "ZIP-архив точки восстановления сохранён. Внутри есть JSON, Bash и PowerShell сценарии."
            } catch (e: Exception) {
                status = "Ошибка экспорта ZIP: ${errorText(e)}"
            }
        }
        pendingPortableRestoreBundle = null
    }

    val portableRestoreBundleImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 8 * 1024 * 1024) { "ZIP-файл превышает допустимый размер 8 МБ" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw Exception("Не удалось прочитать ZIP-файл")
                val bundle = parsePortableRestoreBundleZip(bytes)
                val point = bundle.point
                restorePoint = point.userDisabled
                restorePointManagedUserPackages = point.managedUsers
                restorePointManagedSystemPackages = point.managedSystems
                restorePointAutoStartComponents = point.autoStartComponents
                restorePointDeviceFingerprint = point.fingerprint
                restorePointSchemaVersion = 2
                restorePointExists = true
                settings.edit()
                    .putStringSet("restore_point_user_disabled", point.userDisabled)
                    .putStringSet("restore_point_managed_user_packages", point.managedUsers)
                    .putStringSet("restore_point_managed_system_packages", point.managedSystems)
                    .putStringSet("restore_point_autostart_components", point.autoStartComponents)
                    .putString("restore_point_device_fingerprint", point.fingerprint)
                    .putInt("restore_point_schema_version", 2)
                    .putBoolean("restore_point_exists", true)
                    .apply()
                status = "ZIP проверен по SHA-256. Точка импортирована; модель и прошивка будут проверены перед восстановлением."
            } catch (e: Exception) {
                status = "Ошибка импорта ZIP: ${errorText(e)}"
            }
        }
    }

    val portableRestoreScriptExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-shellscript")
    ) { destinationUri ->
        val script = pendingPortableRestoreScript
        if (destinationUri != null && script != null) {
            try {
                context.contentResolver.openOutputStream(destinationUri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(script)
                } ?: throw Exception("Не удалось создать файл")
                status = "Переносимый сценарий восстановления сохранён."
            } catch (e: Exception) {
                status = "Ошибка экспорта сценария: ${errorText(e)}"
            }
        }
        pendingPortableRestoreScript = null
    }

    val launcherConfigExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destinationUri ->
        val content = pendingLauncherConfigExport
        if (destinationUri != null && content != null) {
            try {
                val output = context.contentResolver.openOutputStream(destinationUri)
                    ?: throw Exception("Не удалось открыть файл для записи")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                status = "Конфигурация Clean Your TV сохранена."
            } catch (e: Exception) {
                status = "Ошибка сохранения конфигурации: ${errorText(e)}"
            }
        }
        pendingLauncherConfigExport = null
    }

    val launcherConfigImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            val host = ip.trim()
            val p = port.toIntOrNull()
            if (host.isBlank() || p == null || p !in 1..65535) {
                status = "Проверь IP и порт ADB"
            } else runTask({
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: throw Exception("Не удалось открыть JSON-файл")
                val jsonText = readBoundedUtf8(input, 1024 * 1024)
                val config = JSONObject(jsonText)
                require(config.length() > 0) { "Конфигурация пустая" }
                connectWithRetry(context, host, p) { adb ->
                    val restart = writeLauncherConfig(adb, config)
                    "Конфигурация Clean Your TV восстановлена. $restart"
                }
            })
        }
    }

    val launcherBackupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { destinationUri ->
        val file = pendingLauncherBackupFile
        if (destinationUri != null && file != null) {
            try {
                val output = context.contentResolver.openOutputStream(destinationUri)
                    ?: throw Exception("Не удалось открыть ZIP-файл для записи")
                file.inputStream().use { input -> output.use { input.copyTo(it) } }
                status = buildString {
                    append("Резервная копия сохранена: config.json")
                    if (pendingLauncherBackupHasWallpaper) append(", текущие обои")
                    else append("; обои не удалось прочитать с телевизора")
                    if (pendingLauncherBackupCollectionCount > 0) append(", коллекция обоев: $pendingLauncherBackupCollectionCount")
                    append(".")
                }
            } catch (e: Exception) {
                status = "Ошибка сохранения ZIP: ${errorText(e)}"
            }
        } else {
            status = "Сохранение резервной копии отменено."
        }
        file?.delete()
        pendingLauncherBackupFile = null
        pendingLauncherBackupHasWallpaper = false
        pendingLauncherBackupCollectionCount = 0
    }

    val launcherBackupImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            val host = ip.trim()
            val p = port.toIntOrNull()
            if (host.isBlank() || p == null || p !in 1..65535) {
                status = "Проверь IP и порт ADB"
            } else runTask({
                connectWithRetry(context, host, p) { adb ->
                    restoreLauncherBackup(context, sourceUri, adb)
                }
            })
        }
    }

    val wallpaperCollectionPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            val host = ip.trim()
            val p = port.toIntOrNull()
            if (host.isBlank() || p == null || p !in 1..65535) {
                status = "Проверь IP и порт ADB"
            } else runTask({
                val files = copyLauncherWallpaperUris(context, selectedUris)
                settings.edit()
                    .putInt("launcher_wallpaper_rotation_minutes", launcherWallpaperRotationMinutes)
                    .putString("launcher_wallpaper_order", launcherWallpaperOrder)
                    .apply()
                connectWithRetry(context, host, p) { adb ->
                    uploadWallpaperCollection(adb, files, launcherWallpaperRotationMinutes, launcherWallpaperOrder)
                }
            })
        }
    }

    val customWallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            runTask({
                val file = prepareCustomWallpaper(context, sourceUri)
                val name = context.contentResolver.query(sourceUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "Собственные обои"
                activity.runOnUiThread {
                    customWallpaperFile = file
                    customWallpaperName = name
                }
                "Изображение подготовлено: $name (${file.length() / 1024} КБ). Теперь можно применить его к Launcher."
            })
        }
    }

    val undoBackupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { destinationUri ->
        val source = pendingUndoBackupExport
        pendingUndoBackupExport = null
        if (destinationUri != null && source != null) {
            runTask({
                context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw Exception("Не удалось экспортировать ZIP-файл")
                "Резервная копия " + undoBackupPackage(source) + " экспортирована"
            })
        }
    }

    val backupRestorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) pendingRestoreUri = sourceUri
    }

    val backupSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { destinationUri ->
        if (destinationUri != null) {
            val host = ip.trim()
            val p = port.toIntOrNull()
            if (host.isBlank() || p == null || p !in 1..65535) {
                status = "Сначала укажи IP и порт ADB"
            } else {
                runTask({
                    val archivePath = connectWithRetry(context, host, p) { adb ->
                        createTvBackup(adb, context).absolutePath
                    }
                    val archive = File(archivePath)
                    context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                        archive.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw Exception("Не удалось сохранить ZIP-файл")
                    archive.delete()
                    "Резервная копия сохранена. APK и список приложений упакованы в ZIP."
                })
            }
        }
    }


    val installCleanYourTvModules: () -> Unit = {
        val host = ip.trim()
        val p = port.toIntOrNull()
        if (host.isBlank() || p == null || p !in 1..65535) {
            status = "Сначала укажи IP и порт ADB и подключись к телевизору"
        } else {
            runTask({
                fun copyPinnedModule(
                    assetName: String,
                    outputName: String,
                    expectedSize: Long,
                    expectedSha256: String,
                    expectedPackage: String,
                    expectedSignerSha256: String
                ): File {
                    val outputFile = File(context.cacheDir, outputName)
                    context.assets.open("cleanyourtv-modules/$assetName").use { input ->
                        outputFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    require(outputFile.length() == expectedSize) {
                        "Размер модуля $assetName не совпадает с доверенным значением"
                    }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(outputFile.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    require(digest.equals(expectedSha256, ignoreCase = true)) {
                        "Проверка SHA-256 не пройдена для $assetName. Установка отменена."
                    }

                    val packageInfo = context.packageManager.getPackageArchiveInfo(
                        outputFile.absolutePath,
                        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                        else PackageManager.GET_SIGNATURES
                    ) ?: throw Exception("Android не смог прочитать подпись $assetName")
                    require(packageInfo.packageName == expectedPackage) {
                        "Неверный package name у $assetName: ${packageInfo.packageName}"
                    }
                    @Suppress("DEPRECATION")
                    val signatures = if (Build.VERSION.SDK_INT >= 28) {
                        packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
                    } else {
                        packageInfo.signatures?.toList().orEmpty()
                    }
                    val signerDigests = signatures.map { signature ->
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(signature.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                    }
                    require(signerDigests.any { it.equals(expectedSignerSha256, ignoreCase = true) }) {
                        "Подпись разработчика $assetName не совпадает с закреплённым сертификатом. Установка отменена."
                    }
                    return outputFile
                }

                val launcherApk = copyPinnedModule(
                    "cleanyourtv-launcher.apk",
                    "cleanyourtv-launcher.apk",
                    540541L,
                    "9fcc21c0eb81548c2432b3bf89c1d9c6b6ad17dcf7f7fc2e8d90d7ccbea7d60e",
                    "com.gathin.tvhome",
                    "576418154bec6505bfc709ccaa6ae85b6e09d98eb89f4a25147ca1b4b0505132"
                )
                val curtainApk = copyPinnedModule(
                    "cleanyourtv-curtain.apk",
                    "cleanyourtv-curtain.apk",
                    65955L,
                    "eb21e112c97121a3ed7f4db1c7fbf2169bc172a14bb3f8ca781079de4651662e",
                    "tv.cleanyour.curtain",
                    "576418154bec6505bfc709ccaa6ae85b6e09d98eb89f4a25147ca1b4b0505132"
                )
                connectWithRetry(context, host, p) { adb ->
                    adb.install(launcherApk)
                    adb.install(curtainApk)
                    val launcher = shellText(adb, "pm path com.gathin.tvhome")
                    val curtain = shellText(adb, "pm path tv.cleanyour.curtain")
                    if (!launcher.contains("package:") || !curtain.contains("package:")) {
                        throw Exception("Проверка установки не пройдена. Launcher: $launcher; Curtain: $curtain")
                    }
                    "Проверенные модули Clean Your TV установлены: Launcher и Curtain. SHA-256 обоих файлов подтверждён. Штатный экран телевизора не отключался."
                }
            })
        }
    }

    fun saveCurrentDevice() {
        val host = ip.trim()
        val p = port.toIntOrNull()

        if (host.isBlank() || p == null || p !in 1..65535) {
            status = "Проверь IP и порт"
            return
        }

        settings.edit()
            .putString("ip", host)
            .putString("port", p.toString())
            .apply()

        val device = TvDevice(
            tvName.ifBlank { host },
            host,
            p
        )

        devices.removeAll {
            it.ip == host && it.port == p
        }
        devices.add(device)
        saveDevices(context, devices)
        status = "Телевизор сохранён"
    }

    MaterialTheme(
        colorScheme = if (isDarkTheme) darkColorScheme(
            background = Bg, onBackground = TextMain, surface = Panel, onSurface = TextMain,
            surfaceVariant = Color(0xFF292216), onSurfaceVariant = TextMuted,
            primary = Accent, onPrimary = Color(0xFF171006), secondary = GoldLight,
            onSecondary = Color(0xFF171006), outline = Color(0xFF6B542B)
        ) else lightColorScheme(
            background = Bg, onBackground = TextMain, surface = Panel, onSurface = TextMain,
            surfaceVariant = Color(0xFFECE5D7), onSurfaceVariant = TextMuted,
            primary = Accent, onPrimary = Color(0xFF171006), secondary = GoldLight,
            onSecondary = Color.White, outline = Color(0xFF9A7A3E)
        )
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Panel, drawerContentColor = TextMain) {
                    Text("TV APK INSTALLER", modifier = Modifier.padding(20.dp), color = GoldLight, style = MaterialTheme.typography.titleLarge)
                    Text("ОСНОВНЫЕ РАЗДЕЛЫ", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    NavigationDrawerItem(label = { Text("Главная") }, selected = !showSettings && activeSection == null,
                        icon = { Icon(Icons.Default.Tv, contentDescription = null) },
                        onClick = { showSettings = false; activeSection = null; drawerScope.launch { drawerState.close() } })
                    NavigationDrawerItem(label = { Text("Wi-Fi ADB") }, selected = activeSection == "wifi",
                        icon = { Text("📶") },
                        onClick = { showSettings = false; activeSection = "wifi"; drawerScope.launch { drawerState.close() } })
                    NavigationDrawerItem(label = { Text("USB OTG") }, selected = activeSection == "usb",
                        icon = { Text("🔌") },
                        onClick = { showSettings = false; activeSection = "usb"; drawerScope.launch { drawerState.close() } })
                    NavigationDrawerItem(label = { Text("Clean Your TV") }, selected = activeSection == "clean",
                        icon = { Text("🧹") },
                        onClick = { showSettings = false; activeSection = "clean"; drawerScope.launch { drawerState.close() } })
                    NavigationDrawerItem(label = { Text("Модули Clean Your TV") }, selected = activeSection == "modules",
                        icon = { Text("🧩") },
                        onClick = { showSettings = false; activeSection = "modules"; drawerScope.launch { drawerState.close() } })
                    NavigationDrawerItem(label = { Text("Оформление Launcher") }, selected = activeSection == "appearance",
                        icon = { Text("🎨") },
                        onClick = { showSettings = false; activeSection = "appearance"; drawerScope.launch { drawerState.close() } })
                    NavigationDrawerItem(label = { Text("Пульт TV") }, selected = activeSection == "remote",
                        icon = { Text("🎮") },
                        onClick = { showSettings = false; activeSection = "remote"; drawerScope.launch { drawerState.close() } })
                    NavigationDrawerItem(label = { Text("Установить APK") }, selected = activeSection == "apk",
                        icon = { Text("📦") },
                        onClick = { showSettings = false; activeSection = "apk"; drawerScope.launch { drawerState.close() } })
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.DarkGray)
                    NavigationDrawerItem(label = { Text("Настройки и обновления") }, selected = showSettings,
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = { showSettings = true; activeSection = null; drawerScope.launch { drawerState.close() } })
                }
            }
        ) {
        Scaffold(containerColor = Bg) { padding ->
        if (showSettings) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { drawerScope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, contentDescription = "Меню") }
                    Text("Настройки", style = MaterialTheme.typography.headlineSmall, color = GoldLight)
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Тёмная тема", color = TextMain, style = MaterialTheme.typography.titleMedium)
                            Text(if (isDarkTheme) "Чёрно-золотое оформление" else "Светлое оформление", color = TextMuted, fontSize = 12.sp)
                        }
                        Switch(checked = isDarkTheme, onCheckedChange = { enabled ->
                            isDarkTheme = enabled
                            settings.edit().putBoolean("dark_theme", enabled).apply()
                        })
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Резервная копия настроек", style = MaterialTheme.typography.titleMedium, color = TextMain)
                        Text("Сохраняет список телевизоров, IP и порт, тему, историю установок и настройки Clean Your TV. APK-файлы и данные телевизора не включаются.", color = TextMuted)
                        OutlinedButton(onClick = { settingsBackupExporter.launch("tv-apk-installer-settings.json") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Создать резервную копию")
                        }
                        Button(onClick = { settingsBackupImporter.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Восстановить настройки")
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Обратная связь", style = MaterialTheme.typography.titleMedium, color = TextMain)
                        Text("Сообщить об ошибке или предложить улучшение.", color = TextMuted)
                        OutlinedButton(onClick = { uriHandler.openUri("https://github.com/1StMaGe911/Tv-apk-installer/issues/new") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Feedback, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Написать отзыв")
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Связь с автором", style = MaterialTheme.typography.titleMedium, color = TextMain)
                        Text("Открыть страницу автора проекта на GitHub.", color = TextMuted)
                        OutlinedButton(onClick = { uriHandler.openUri("https://github.com/1StMaGe911") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Person, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Связаться с автором")
                        }
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Обновления приложения",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(
                            "Текущая версия: $APP_VERSION",
                            color = Color.LightGray
                        )

                        OutlinedButton(
                            onClick = { showChangelog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Что нового в обновлениях")
                        }

                        if (showChangelog) {
                            AlertDialog(
                                onDismissRequest = { showChangelog = false },
                                title = { Text("История обновлений") },
                                text = {
                                    Column(
                                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text("v0.9.11 • Диагностика совместимости ADB", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("После определения телевизора приложение проверяет доступность Package Manager, команд отключения пакетов, cmd package, системных настроек и сведений о питании. Результат показывается отдельно и не считается гарантией поддержки всех функций прошивки.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.10 • Предварительный план очистки", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Перед выполнением можно просмотреть точный список пакетов, разрешённых профилем модели, и отдельно увидеть пропущенные правилами безопасности. Фактическое отключение и восстановление подтверждаются через Android Package Manager.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.9 • Проверка результата очистки", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("После каждой команды отключения и восстановления приложение дополнительно проверяет фактическое состояние пакета через Android Package Manager. Успешный код ADB сам по себе больше не считается подтверждением изменения.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.8 • Журнал безопасной очистки и откат незавершённой операции", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Перед отключением создаётся локальный журнал с идентификатором телевизора и планом. Каждый успешно отключённый пакет записывается сразу; после аварийного закрытия приложение предлагает восстановить только эти компоненты и только на том же телевизоре.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.7 • Встроенные модули Clean Your TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Launcher и Curtain теперь встроены в APK приложения и устанавливаются после проверки размера и SHA-256. Отдельный APK импортировать не нужно.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.6 • Сезонная тема Diwali", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлена праздничная тема Diwali из актуального каталога Clean Your TV. Она показывается только до даты окончания, указанной в исходной конфигурации темы.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.5 • Полная тема Quiet", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлена недостающая официальная тема Quiet — чистый чёрный фон без обоев и с настройками для спокойной работы Launcher. Исправлена загрузка темы, которая в исходном проекте намеренно не содержит wallpaper.jpg.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.4 • Аудит приватности и каталог профилей", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен локальный аудит установленных пакетов по категориям справочника Clean Your TV, подробные описания и просмотр каталога профилей устройств.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.3 • Исправление передачи Home", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Очищается временная запись отката, если Clean Your TV Launcher не запускается до изменения штатного Home. Неуспешное переключение не оставляет ложный статус активного отката.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.2 • Безопасное переключение домашнего экрана", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлено проверяемое назначение Clean Your TV Launcher главным экраном: сначала тестируется запуск, сохраняется точный штатный Home, затем отключается только распознанный системный пакет и проверяется результат. При сбое выполняется автоматический возврат; отдельная кнопка позволяет восстановить штатный экран позже или после обрыва ADB.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.1 • Расширенная защита Android TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Расширен список критически важных пакетов Android и TV middleware по правилам Clean Your TV. Ручное отключение системного пакета теперь повторно проверяет защиту и профиль конкретной модели непосредственно перед ADB-командой; компоненты neverDisable заблокированы даже при ручном выборе.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.9.0 • Переносимый сценарий восстановления", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен экспорт Bash-сценария для восстановления с компьютера через ADB. Сценарий содержит только изменения, отслеживаемые TV APK Installer, проверяет производителя, модель, устройство и отпечаток прошивки до выполнения команд, а ошибки показывает отдельно.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.8.9 • Быстрое восстановление системных изменений", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлена отдельная команда для восстановления всех системных пакетов и компонентов автозапуска, отключённых самим приложением. Перед действием показывается подтверждение; успешные элементы удаляются из списка ожидания, а ошибки остаются доступными для повторной попытки.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.8.8 • Расширенная точка восстановления", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Точка сохраняет не только отключённые пользовательские приложения, но и изменения, выполненные самим TV APK Installer: отключённые системные пакеты и компоненты автозапуска. При восстановлении сравнивается модель и отпечаток прошивки; защищённые системные компоненты не отключаются. Старые точки без полного снимка нужно создать заново.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.8.7 • Безопасное восстановление и сохранение настроек", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Точки восстановления привязаны к модели и отпечатку прошивки, поэтому приложение останавливает восстановление при несовпадении устройства. При применении темы сохраняются персональные виджеты, параметры входов и запуска ТВ; неизвестные ключи конфигурации, отсутствующие в старой теме, также сохраняются.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.8.6 • Выбор рекомендаций очистки", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен выбор компонентов, проверенных для точного профиля модели (ask=false), и пакетное отключение после подтверждения. Перед отключением список повторно сверяется с телевизором; защищённые компоненты и neverDisable исключаются. Отключённые пакеты можно восстановить.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.8.5 • Возврат оформления", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Перед установкой готовой темы автоматически сохраняется ZIP-копия прежней конфигурации, обоев и локальной коллекции. Добавлена кнопка возврата к состоянию до последней темы.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.8.4 • Сохранение персональных настроек", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Установка готовой темы сохраняет закреплённые и скрытые приложения, назначения кнопок, настройки часов и параметры ротации обоев. Если текущую конфигурацию прочитать не удалось, установка прекращается без перезаписи настроек.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.8.2 • USB ADB и управление коллекцией обоев", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлено прямое USB ADB-подключение для устройств, которые предоставляют ADB USB-интерфейс, с системным запросом разрешения, RSA-авторизацией и установкой выбранного APK по USB. Также добавлено безопасное удаление коллекции wallpapers с телевизора с подтверждением; локальные копии на телефоне сохраняются.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.8.1 • Настройка долгого нажатия Home", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлена настройка home_long_press_home для HomeKeyService: можно направить длительное нажатие кнопки Home в Clean Your TV. Настройка включается отдельно и требует HomeKeyService.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.8.0 • Полная копия коллекции обоев", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("ZIP-резервная копия теперь включает локальную коллекцию wallpapers, а восстановление возвращает изображения на телевизор и телефон вместе с config.json и активными обоями. Коллекции, добавленные вне приложения, могут не попасть в архив.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.9 • Коллекция и ротация обоев", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен выбор и загрузка до 100 изображений в wallpapers Launcher, локальное хранение коллекции на телефоне, интервал ротации и случайный/последовательный порядок. Повторная загрузка заменяет коллекцию на телевизоре.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.8 • Поддержка Home / All Apps", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлена отдельная опциональная активация HomeKeyService для пультов, которые отправляют KEYCODE_ALL_APPS вместо обычной кнопки Home. Включение требует подтверждения и не меняет остальные службы специальных возможностей.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.7 • Полная резервная копия Launcher", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлена ZIP-резервная копия config.json и текущего wallpaper.jpg, если файл доступен через ThemeProvider. Восстановление возвращает конфигурацию и активные обои; папка ротации пока не включена.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.6 • Резервная копия конфигурации Launcher", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлены экспорт и восстановление config.json Clean Your TV через системный выбор файлов. Резервная копия включает настройки темы, дока, скрытых приложений, часов и назначений кнопок; изображения обоев сохраняются отдельно.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.5 • Назначение кнопок пульта", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен редактор app_keys: назначение клавиш и scancode на приложения, действия и входы ТВ. Добавлено включение InputKeyService через ADB с подтверждением, поддержка автозапуска на TCL и очистка назначений без удаления других служб специальных возможностей.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.4 • Часы и системная заставка", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлены настройки автоматических часов, обоев и затемнения на экране часов, свечения, формата времени и действия кнопки Назад. Можно установить или отключить системную заставку ClockDream Clean Your TV с сохранением других заставок.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.3 • Управление доком и приложениями", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлено чтение TV-приложений, выбор приложений в док, скрытие плиток на главном экране и настройка заполнения свободных мест. Изменения записываются в текущий config.json без удаления или отключения приложений.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.2 • Собственные обои и ThemeProvider", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен выбор изображения из галереи, подготовка обоев с ограничением разрешения и размера, настройка затемнения и применение через ThemeProvider Clean Your TV. Исправлена передача только поддерживаемых файлов config.json и wallpaper.jpg.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.1 • Готовые темы и обои", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлена загрузка готовых тем Clean Your TV (Cinema, Arcade, Console, Horizon, Library, Mist, Quiet) с передачей конфигурации и обоев на телевизор через ADB и ThemeProvider. После применения Launcher перезапускается.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.7.0 • Настройка внешнего вида Clean Your TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлены настройки сетки, дока, анимаций, эффектов, компактной верхней панели и библиотеки приложений. Параметры записываются в config.json Launcher через ThemeProvider/ADB и применяются после перезапуска.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.9 • Управление модулями Clean Your TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлено безопасное удаление модулей Launcher и Curtain с подтверждением, проверкой установленных пакетов и отчётом об остатках.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.8 • Профили и безопасность Clean Your TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Показываются сведения о тестировании профиля и прошивке. Для пакетов с флагом ручной оценки добавлено отдельное предупреждение; защищённые компоненты исключены из кандидатов.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.7 • Профили моделей", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлено распознавание 173 профилей устройств Clean Your TV и дополнительные списки пакетов с защитой neverDisable.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.6 • Справочник Clean Your TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен локальный справочник из 1 113 описаний пакетов Clean Your TV. Раздел очистки показывает назначение и категорию известных совпадений. Подсказки информационные: ничего не отключается автоматически.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.5 • Модули Clean Your TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен импорт исходного APK Clean Your TV 0.4.8: извлечение и установка Launcher и Curtain, проверка пакетов, запуск Launcher и переход к системному выбору домашнего экрана. Штатный Launcher не отключается автоматически.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.4 • Навигация и USB OTG", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Кнопка в заголовке каждого раздела открывает левое меню. Во вкладке USB OTG убраны IP, порт, подключение по ADB и список сохранённых телевизоров.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.3 • Левое меню разделов", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Все инструменты перенесены в левое боковое меню. Для каждого рабочего раздела добавлен отдельный экран подключения к телевизору и выбор сохранённых устройств.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.2 • Отдельные экраны разделов", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Плитки открывают отдельные экраны подключения и меню выбранного инструмента. Добавлен выбор сохранённого телевизора.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.1 • Главный экран-плитки", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Главный экран переработан: основные функции собраны в крупные интерактивные карточки быстрого доступа.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.6.0 • Центр управления и пульт", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлены расширенный паспорт телевизора, анализ хранилища, управление пользовательскими приложениями (информация и удаление с подтверждением), а также экранный ADB-пульт с навигацией, громкостью, меню и системными клавишами.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.5.6 • Управление автозапуском", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен поиск компонентов автозапуска пользовательских приложений и их включение/отключение через ADB с подтверждением.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.5.5 • Резервная копия настроек", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлены экспорт и восстановление настроек приложения. Проверка обновлений перенесена в раздел настроек.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.5.4 • Меню и темы", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлены боковое меню, настройки и переключение светлой/тёмной темы.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.5.3 • История обновлений", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлен этот раздел с описанием изменений версий прямо в приложении.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.5.2 • Запуск приложений", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("В Clean Your TV добавлена кнопка запуска установленных пользовательских приложений через Wi-Fi ADB.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.5.1 • Управление телевизором", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлены команды открытия главного экрана, настроек Android TV и перезагрузки телевизора с подтверждением.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.5.0 • Диагностика", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Добавлена диагностика оперативной памяти, хранилища, батареи и времени работы устройства.", color = TextMain)
                                        HorizontalDivider(color = Color.DarkGray)
                                        Text("v0.4.9 • Резервное копирование", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                        Text("Улучшено создание ZIP-резервной копии: сведения об устройстве, список приложений и APK пользовательских приложений, включая split APK.", color = TextMain)
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showChangelog = false }) { Text("Закрыть") }
                                }
                            )
                        }

                        Text(
                            versionStatus,
                            color = Color.LightGray
                        )

                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true

                                Thread {
                                    val result = try {
                                        checkLatestVersion()
                                    } catch (e: Exception) {
                                        UpdateInfo(
                                            "Ошибка: ${errorText(e)}",
                                            null,
                                            null,
                                            0L
                                        )
                                    }

                                    activity.runOnUiThread {
                                        versionStatus = result.status
                                        releaseUrl = result.releaseUrl
                                        updateApkUrl = result.apkUrl
                                        updateApkSha256 = result.expectedSha256
                                        downloadedUpdatePath = null
                                        status = result.status
                                        busy = false
                                    }
                                }.start()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Проверить обновления")
                        }

                        if (updateApkUrl != null) {
                            Text(
                                if (versionStatus.contains("Доступно обновление"))
                                    "APK доступен для загрузки • ${if (downloadedUpdatePath == null) "ещё не скачан" else "скачан"}"
                                else "",
                                color = GoldLight,
                                fontSize = 12.sp
                            )
                            Button(
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val url = updateApkUrl ?: return@Button
                                    val sha256 = updateApkSha256 ?: run {
                                        status = "Нет проверенной SHA-256. Загрузка отменена."
                                        return@Button
                                    }
                                    runTask(
                                        action = { downloadUpdateApk(context, url, sha256).absolutePath },
                                        onFinished = { result ->
                                            if (!result.startsWith("Ошибка")) {
                                                downloadedUpdatePath = result
                                                status = "Обновление скачано. Нажми «Установить обновление»."
                                            }
                                        }
                                    )
                                }
                            ) {
                                Text(if (busy) "Загрузка..." else "Скачать обновление")
                            }
                        }

                        if (downloadedUpdatePath != null) {
                            Button(
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val path = downloadedUpdatePath ?: return@Button
                                    val apk = File(path)
                                    if (!apk.exists()) {
                                        downloadedUpdatePath = null
                                        status = "Файл обновления не найден. Скачай его заново."
                                    } else if (android.os.Build.VERSION.SDK_INT >= 26 &&
                                        !activity.packageManager.canRequestPackageInstalls()
                                    ) {
                                        status = "Разреши установку неизвестных приложений для TV APK Installer и вернись сюда."
                                        activity.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                AndroidUri.parse("package:${activity.packageName}")
                                            )
                                        )
                                    } else {
                                        try {
                                            val contentUri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                apk
                                            )
                                            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(contentUri, "application/vnd.android.package-archive")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            activity.startActivity(installIntent)
                                        } catch (e: Exception) {
                                            status = "Не удалось открыть установщик: ${errorText(e)}"
                                        }
                                    }
                                }
                            ) {
                                Text("Установить обновление")
                            }
                        }

                        if (releaseUrl != null) {
                            OutlinedButton(
                                onClick = {
                                    releaseUrl?.let { uriHandler.openUri(it) }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Открыть страницу релиза")
                            }
                        }
                    }
                }

            }

        } else if (activeSection != null) {
            val section = activeSection ?: "wifi"
            val sectionTitle = when (section) {
                "wifi" -> "Wi-Fi ADB"
                "usb" -> "USB OTG"
                "clean" -> "Clean Your TV"
                "remote" -> "Пульт TV"
                "modules" -> "Модули Clean Your TV"
                "appearance" -> "Оформление Clean Your TV"
                "apk" -> "Установка APK"
                else -> "Раздел"
            }
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Открыть меню разделов", tint = GoldLight)
                    }
                    Text(sectionTitle, color = GoldLight, style = MaterialTheme.typography.headlineSmall)
                }
                if (section == "usb") {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("USB OTG / USB ADB", color = GoldLight, style = MaterialTheme.typography.titleMedium)
                            Text("Подключи Android TV или приставку кабелем OTG к телефону. Для прямого ADB телевизор должен работать как USB-устройство и предоставлять интерфейс ADB (FF/42/01). Большинство обычных USB-портов телевизора работают только как HOST и не поддерживают такое подключение.", color = TextMuted, fontSize = 12.sp)
                            OutlinedButton(enabled = !busy, onClick = {
                                val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                                usbDevices = manager.deviceList.values.toList()
                                status = if (usbDevices.isEmpty()) "USB-устройства не обнаружены. Проверь OTG-кабель и поддержку USB Host телефоном."
                                else "Обнаружено USB-устройств: ${usbDevices.size}. Проверь, есть ли у нужного устройства интерфейс ADB."
                            }, modifier = Modifier.fillMaxWidth()) { Text("Найти USB-устройства") }
                            if (usbDevices.isEmpty()) {
                                Text("Сначала выполни поиск USB-устройств.", color = TextMuted, fontSize = 12.sp)
                            }
                            usbDevices.forEach { device ->
                                Card(colors = CardDefaults.cardColors(containerColor = Bg), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(device.productName?.toString()?.takeIf { it.isNotBlank() } ?: device.deviceName, color = TextMain, style = MaterialTheme.typography.titleSmall)
                                        Text("VID:PID ${"%04X".format(device.vendorId)}:${"%04X".format(device.productId)} • Device ID: ${device.deviceId}", color = TextMuted, fontSize = 11.sp)
                                        val hasAdb = UsbDadb.hasAdbInterface(device)
                                        Text(
                                            if (hasAdb) "Найден ADB USB-интерфейс (FF/42/01)"
                                            else "ADB USB-интерфейс не найден",
                                            color = if (hasAdb) GoldLight else Color(0xFFFFB4A9),
                                            fontSize = 12.sp
                                        )
                                        if (hasAdb) {
                                            OutlinedButton(enabled = !busy, onClick = {
                                                val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                                                if (!manager.hasPermission(device)) {
                                                    val permissionIntent = PendingIntent.getBroadcast(
                                                        context,
                                                        device.deviceId,
                                                        Intent("${context.packageName}.USB_PERMISSION").setPackage(context.packageName),
                                                        PendingIntent.FLAG_UPDATE_CURRENT or
                                                            (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                                                    )
                                                    manager.requestPermission(device, permissionIntent)
                                                    status = "Подтверди доступ к USB в системном окне, затем снова нажми «Подключиться по USB»."
                                                } else {
                                                    runTask({
                                                        getAdbKey(context)
                                                        val keyFile = File(File(context.filesDir, "adb"), "adbkey")
                                                        UsbDadb.connect(context, device, keyFile).use { adb ->
                                                            val model = shellText(adb, "getprop ro.product.model").trim().ifBlank { "Android TV" }
                                                            val manufacturer = shellText(adb, "getprop ro.product.manufacturer").trim()
                                                            "USB ADB подключён: ${manufacturer} ${model}".trim()
                                                        }
                                                    })
                                                }
                                            }, modifier = Modifier.fillMaxWidth()) { Text("Подключиться по USB") }
                                            OutlinedButton(enabled = !busy && selectedUri != null, onClick = {
                                                val uri = selectedUri
                                                if (uri == null) {
                                                    status = "Сначала выбери APK в разделе «Установка APK»."
                                                } else {
                                                    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                                                    if (!manager.hasPermission(device)) {
                                                        val permissionIntent = PendingIntent.getBroadcast(
                                                            context,
                                                            device.deviceId,
                                                            Intent("${context.packageName}.USB_PERMISSION"),
                                                            PendingIntent.FLAG_UPDATE_CURRENT or
                                                                (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                                                        )
                                                        manager.requestPermission(device, permissionIntent)
                                                        status = "Подтверди доступ к USB, затем снова нажми «Установить выбранный APK по USB»."
                                                    } else {
                                                        runTask({
                                                            val apkFile = File(context.cacheDir, "selected_usb_app.apk")
                                                            context.contentResolver.openInputStream(uri)?.use { input ->
                                                                apkFile.outputStream().use { output -> input.copyTo(output) }
                                                            } ?: throw Exception("Не удалось прочитать выбранный APK")
                                                            getAdbKey(context)
                                                            val keyFile = File(File(context.filesDir, "adb"), "adbkey")
                                                            UsbDadb.connect(context, device, keyFile).use { adb ->
                                                                when (val result = adb.install(apkFile)) {
                                                                    is InstallResult.Success -> "APK успешно установлен через USB"
                                                                    is InstallResult.Failure -> throw Exception(result.reason)
                                                                }
                                                            }
                                                        }, onFinished = { result ->
                                                            if (result == "APK успешно установлен через USB") {
                                                                history.add(0, "$selectedName — USB ${device.productName ?: device.deviceName}")
                                                                while (history.size > 30) history.removeAt(history.lastIndex)
                                                                saveHistory(context, history)
                                                            }
                                                        })
                                                    }
                                                }
                                            }, modifier = Modifier.fillMaxWidth()) { Text("Установить выбранный APK по USB") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("ПОДКЛЮЧЕНИЕ К ТЕЛЕВИЗОРУ", color = GoldLight, style = MaterialTheme.typography.titleMedium)
                            Text("Этот инструмент подключается к выбранному Android TV через ADB. Включи «Отладку по сети» в настройках телевизора.", color = TextMuted, fontSize = 12.sp)
                            OutlinedTextField(value = tvName, onValueChange = { tvName = it }, label = { Text("Название телевизора") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP телевизора") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Порт ADB") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) {
                                    status = "Проверь IP и порт"
                                } else {
                                    settings.edit().putString("ip", host).putString("port", p.toString()).apply()
                                    runTask(action = {
                                        connectWithRetry(context, host, p) { "ADB подключён: $host:$p" }
                                    }, onFinished = { result ->
                                        if (!result.startsWith("Ошибка")) {
                                            if (section == "clean") showCleanYourTv = true
                                            if (section == "remote") showRemoteControl = true
                                            if (section != "modules" && section != "appearance") activeSection = null
                                        }
                                    })
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (busy) "Подключение..." else "Подключиться")
                            }
                            OutlinedButton(enabled = !busy, onClick = { saveCurrentDevice() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Сохранить этот телевизор")
                            }
                            if (section == "apk") {
                                HorizontalDivider(color = Color.DarkGray)
                                Text("После подключения можно выбрать APK и установить его на телевизор.", color = TextMuted, fontSize = 12.sp)
                                OutlinedButton(onClick = { activeSection = null; picker.launch("application/vnd.android.package-archive") }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Выбрать APK-файл")
                                }
                            }
                        }
                    }
                }
                if (section == "modules") {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("МОДУЛИ CLEAN YOUR TV", color = GoldLight, style = MaterialTheme.typography.titleMedium)
                            Text("TV APK Installer уже содержит проверенные модули Clean Your TV. Приложение само проверит их контрольные суммы и установит Launcher и Curtain на подключённый Android TV — импортировать отдельный APK не нужно.", color = TextMuted, fontSize = 12.sp)
                            Button(enabled = !busy, onClick = { installCleanYourTvModules() }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (busy) "Установка модулей..." else "Установить / обновить модули автоматически")
                            }
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim(); val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Укажи IP и порт ADB"
                                else runTask({ connectWithRetry(context, host, p) { adb ->
                                    val launcher = shellText(adb, "pm path com.gathin.tvhome")
                                    val curtain = shellText(adb, "pm path tv.cleanyour.curtain")
                                    "Launcher: ${if (launcher.contains("package:")) "установлен" else "не найден"}; Curtain: ${if (curtain.contains("package:")) "установлен" else "не найден"}"
                                } })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Проверить установленные модули") }
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim(); val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Укажи IP и порт ADB"
                                else runTask({ connectWithRetry(context, host, p) { adb ->
                                    shellText(adb, "monkey -p com.gathin.tvhome -c android.intent.category.LAUNCHER 1")
                                    "Запрос запуска Clean Your TV Launcher отправлен на телевизор."
                                } })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Открыть Launcher") }
                            Button(enabled = !busy, onClick = { pendingLauncherHandover = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Безопасно назначить Clean Your TV Launcher главным")
                            }
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim(); val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Укажи IP и порт ADB"
                                else runTask({ connectWithRetry(context, host, p) { adb ->
                                    shellText(adb, "am start -a android.settings.HOME_SETTINGS")
                                    "Открыты системные настройки выбора домашнего экрана. Штатный Launcher не отключён."
                                } })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Выбор домашнего экрана вручную") }
                            if (hasLauncherHandover) {
                                OutlinedButton(enabled = !busy, onClick = {
                                    val host = ip.trim(); val p = port.toIntOrNull()
                                    if (host.isBlank() || p == null || p !in 1..65535) status = "Укажи IP и порт ADB"
                                    else runTask({ connectWithRetry(context, host, p) { adb ->
                                        restoreStockHomeAfterHandover(context, adb)
                                    } }, { result ->
                                        if (result.startsWith("Штатный домашний экран восстановлен")) hasLauncherHandover = false
                                    })
                                }, modifier = Modifier.fillMaxWidth()) { Text("Аварийно вернуть штатный домашний экран") }
                            }
                            if (pendingLauncherHandover) {
                                AlertDialog(
                                    onDismissRequest = { pendingLauncherHandover = false },
                                    title = { Text("Переключить главный экран?") },
                                    text = { Text("Сначала приложение проверит запуск Clean Your TV Launcher. Затем сохранит компонент штатного Home, отключит только распознанный пакет штатного Launcher и проверит, что Android действительно назначил новый экран. При ошибке будет предпринята попытка автоматического отката. Продолжить?") },
                                    confirmButton = {
                                        TextButton(enabled = !busy, onClick = {
                                            pendingLauncherHandover = false
                                            val host = ip.trim(); val p = port.toIntOrNull()
                                            if (host.isBlank() || p == null || p !in 1..65535) status = "Укажи IP и порт ADB"
                                            else runTask({ connectWithRetry(context, host, p) { adb ->
                                                handOverToCleanYourTvLauncher(context, adb)
                                            } }, { result ->
                                                hasLauncherHandover = settings.getString("launcher_handover_stock_package", null) != null
                                            })
                                        }) { Text("Проверить и переключить") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { pendingLauncherHandover = false }) { Text("Отмена") }
                                    }
                                )
                            }
                            Text("Автоматическое переключение работает только с распознанными штатными пакетами Google TV / Android TV / Xiaomi TV. Перед отключением проверяется запуск нового Launcher, исходный компонент сохраняется для отката, а результат назначения Home проверяется повторно. Если производитель использует неизвестный Launcher, автоматическое переключение заблокировано — используй системный выбор Home.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            OutlinedButton(enabled = !busy, onClick = { pendingModuleRemoval = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить модули Clean Your TV") }
                            Text("Удаление затронет только пакеты Clean Your TV для пользователя телевизора. Перед удалением выбери штатный домашний экран в системных настройках.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            Text("В исходном APK Clean Your TV 0.4.8 доступны два извлекаемых Android-модуля: Launcher и Curtain. Профили устройств, справочник пакетов, локальный аудит приватности, резервирование конфигурации и просмотр профилей доступны в отдельных разделах приложения. Другие дополнительные APK-модули не входят в выбранный исходный APK и не устанавливаются автоматически.", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    if (pendingModuleRemoval) {
                        AlertDialog(
                            onDismissRequest = { pendingModuleRemoval = false },
                            title = { Text("Удалить модули Clean Your TV?") },
                            text = { Text("Будут удалены только пакеты com.gathin.tvhome и tv.cleanyour.curtain для пользователя телевизора. TV APK Installer и другие приложения не затрагиваются. Сначала выбери штатный домашний экран в системных настройках. Продолжить?") },
                            confirmButton = {
                                TextButton(enabled = !busy, onClick = {
                                    pendingModuleRemoval = false
                                    val host = ip.trim()
                                    val p = port.toIntOrNull()
                                    if (host.isBlank() || p == null || p !in 1..65535) {
                                        status = "Сначала укажи IP и порт ADB"
                                    } else runTask({ connectWithRetry(context, host, p) { adb ->
                                        val removed = mutableListOf<String>()
                                        val errors = mutableListOf<String>()
                                        listOf("tv.cleanyour.curtain", "com.gathin.tvhome").forEach { pkg ->
                                            val installed = try { shellText(adb, "pm path $pkg").contains("package:") } catch (_: Exception) { false }
                                            if (installed) {
                                                try {
                                                    val result = shellText(adb, "pm uninstall --user 0 $pkg")
                                                    if (result.contains("Success", ignoreCase = true)) removed.add(pkg) else errors.add("$pkg: $result")
                                                } catch (e: Exception) { errors.add("$pkg: ${e.message}") }
                                            }
                                        }
                                        val left = listOf("com.gathin.tvhome", "tv.cleanyour.curtain").filter { pkg ->
                                            try { shellText(adb, "pm path $pkg").contains("package:") } catch (_: Exception) { false }
                                        }
                                        "Удалено модулей: ${removed.size}. Осталось установлено: ${left.size}." +
                                            if (errors.isEmpty() && left.isEmpty()) " Clean Your TV удалён." else "\nПроблемы: ${(errors + left.map { "$it всё ещё установлен" }).joinToString("; ")}"
                                    } })
                                }) { Text("Удалить") }
                            },
                            dismissButton = { TextButton(onClick = { pendingModuleRemoval = false }) { Text("Отмена") } }
                        )
                    }
                }
                if (section == "appearance") {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("ВНЕШНИЙ ВИД CLEAN YOUR TV", color = GoldLight, style = MaterialTheme.typography.titleMedium)
                            Text("Настройки записываются в config.json установленного Clean Your TV Launcher. После применения Launcher перезапустится. Если модуль ещё не установлен, сначала открой раздел «Модули Clean Your TV».", color = TextMuted, fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Приложений в ряду: $launcherColumns", color = TextMain)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { launcherColumns = (launcherColumns - 1).coerceAtLeast(4) }) { Text("−") }
                                    OutlinedButton(onClick = { launcherColumns = (launcherColumns + 1).coerceAtMost(8) }) { Text("+") }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Рядов под доком: $launcherRowsBelowDock", color = TextMain)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { launcherRowsBelowDock = (launcherRowsBelowDock - 1).coerceAtLeast(0) }) { Text("−") }
                                    OutlinedButton(onClick = { launcherRowsBelowDock = (launcherRowsBelowDock + 1).coerceAtMost(3) }) { Text("+") }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Название выбранного приложения", color = TextMain)
                                    Text("Показывать название над доком", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherDockTitle, onCheckedChange = { launcherDockTitle = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Отдельная полоса дока", color = TextMain)
                                    Text("Отключи, чтобы приложения были одной сеткой", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherDockRow, onCheckedChange = { launcherDockRow = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Анимации", color = TextMain)
                                    Text("Плавное перемещение фокуса", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherAnimations, onCheckedChange = { launcherAnimations = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Расширенные эффекты", color = TextMain)
                                    Text("Увеличение и подсветка плиток", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherAdvancedEffects, onCheckedChange = { launcherAdvancedEffects = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Библиотека приложений", color = TextMain)
                                    Text("Дополнительный список всех установленных приложений", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherAppLibrary, onCheckedChange = { launcherAppLibrary = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Компактные кнопки верхней панели", color = TextMain)
                                    Text("Только значки вместо подписей", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherIconOnlyButtons, onCheckedChange = { launcherIconOnlyButtons = it })
                            }
                            Button(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) {
                                    status = "Проверь IP и порт ADB"
                                } else {
                                    settings.edit()
                                        .putInt("launcher_columns", launcherColumns)
                                        .putInt("launcher_rows_below_dock", launcherRowsBelowDock)
                                        .putBoolean("launcher_dock_title", launcherDockTitle)
                                        .putBoolean("launcher_dock_row", launcherDockRow)
                                        .putBoolean("launcher_animations", launcherAnimations)
                                        .putBoolean("launcher_advanced_effects", launcherAdvancedEffects)
                                        .putBoolean("launcher_app_library", launcherAppLibrary)
                                        .putBoolean("launcher_icon_only_buttons", launcherIconOnlyButtons)
                                        .apply()
                                    runTask({
                                        connectWithRetry(context, host, p) { adb ->
                                            applyLauncherAppearance(adb, launcherColumns, launcherRowsBelowDock, launcherDockTitle, launcherDockRow,
                                                launcherAnimations, launcherAdvancedEffects, launcherAppLibrary, launcherIconOnlyButtons)
                                        }
                                    })
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (busy) "Применение..." else "Применить оформление на телевизоре")
                            }
                            Text("СВОИ ОБОИ", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Text("Выбери фотографию или изображение из галереи. Оно будет уменьшено до разумного разрешения и передано в Clean Your TV Launcher как wallpaper.jpg.", color = TextMuted, fontSize = 11.sp)
                            OutlinedButton(enabled = !busy, onClick = { customWallpaperPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Выбрать изображение из галереи")
                            }
                            Text(customWallpaperName, color = TextMain, fontSize = 12.sp)
                            Text("Затемнение обоев: ${customWallpaperDim.toInt()}%", color = TextMain)
                            Slider(value = customWallpaperDim, onValueChange = { customWallpaperDim = it }, valueRange = 0f..80f, steps = 7)
                            Button(enabled = !busy && customWallpaperFile != null, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) {
                                    status = "Проверь IP и порт ADB"
                                } else runTask({
                                    val base = downloadCleanYourTvTheme(context, "cinema")
                                    val config = makeCustomWallpaperConfig(context, base.first, customWallpaperDim.toInt())
                                    connectWithRetry(context, host, p) { adb ->
                                        pushLauncherTheme(adb, "custom", config, customWallpaperFile!!)
                                    }
                                })
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (busy) "Применение..." else "Применить мои обои")
                            }
                            Text("Собственные обои используют оформление Cinema как основу. Для возврата к готовому оформлению выбери любую готовую тему ниже.", color = TextMuted, fontSize = 11.sp)
                            Text("ПРИЛОЖЕНИЯ И ДОК", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Text("Настрой порядок приложений в доке и скрой ненужные плитки только на главном экране Clean Your TV. Приложения не удаляются и не отключаются.", color = TextMuted, fontSize = 11.sp)
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else runTask({
                                    connectWithRetry(context, host, p) { adb ->
                                        val found = readLauncherApps(adb)
                                        val current = try { readCurrentLauncherConfig(adb) } catch (_: Exception) { null }
                                        val dockText = current?.optJSONArray("dock_apps")?.let { array ->
                                            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }.joinToString(", ")
                                        } ?: ""
                                        val hiddenText = current?.optJSONArray("hidden_apps")?.let { array ->
                                            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }.joinToString(", ")
                                        } ?: ""
                                        val fill = current?.optBoolean("fill_dock", true) ?: true
                                        activity.runOnUiThread {
                                            launcherAvailablePackages = found
                                            if (current != null) {
                                                launcherDockPackages = dockText
                                                launcherHiddenPackages = hiddenText
                                                launcherFillDock = fill
                                                launcherIdleClockMinutes = current.optInt("home_idle_clock_minutes", 0).coerceIn(0, 60)
                                                launcherClockWallpaper = current.optBoolean("clock_wallpaper", false)
                                                launcherClockWallpaperDim = current.optInt("clock_wallpaper_dim", 50).coerceIn(0, 90).toFloat()
                                                launcherClockGlow = current.optBoolean("clock_glow", true)
                                                launcherBackOpensClock = current.optBoolean("back_opens_clock", false)
                                                launcherClockFormat = current.optString("clock_format", "system").takeIf { it in setOf("system", "12", "24") } ?: "system"
                                                launcherWallpaperRotationMinutes = current.optInt("wallpaper_rotation_minutes", 0).coerceIn(0, 120)
                                                launcherWallpaperOrder = current.optString("wallpaper_order", "shuffle").takeIf { it in setOf("sequential", "shuffle") } ?: "shuffle"
                                                launcherHomeLongPressHome = current.optBoolean("home_long_press_home", false)
                                                launcherRemoteKeyMappings = current.optJSONArray("app_keys")?.let { array ->
                                                    (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }.joinToString("\n")
                                                } ?: ""
                                                settings.edit()
                                                    .putString("launcher_dock_packages", dockText)
                                                    .putString("launcher_hidden_packages", hiddenText)
                                                    .putBoolean("launcher_fill_dock", fill)
                                                    .putInt("launcher_idle_clock_minutes", launcherIdleClockMinutes)
                                                    .putBoolean("launcher_clock_wallpaper", launcherClockWallpaper)
                                                    .putInt("launcher_clock_wallpaper_dim", launcherClockWallpaperDim.toInt())
                                                    .putBoolean("launcher_clock_glow", launcherClockGlow)
                                                    .putBoolean("launcher_back_opens_clock", launcherBackOpensClock)
                                                    .putString("launcher_clock_format", launcherClockFormat)
                                                    .putInt("launcher_wallpaper_rotation_minutes", launcherWallpaperRotationMinutes)
                                                    .putString("launcher_wallpaper_order", launcherWallpaperOrder)
                                                    .putBoolean("launcher_home_long_press_home", launcherHomeLongPressHome)
                                                    .putString("launcher_remote_key_mappings", launcherRemoteKeyMappings)
                                                    .apply()
                                            }
                                        }
                                        "Найдено приложений с TV-запуском: ${found.size}. " +
                                            if (current != null) "Текущие настройки дока и скрытых приложений загружены."
                                            else "Список получен, но config.json прочитать не удалось — существующие настройки не заменены."
                                    }
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Загрузить приложения с телевизора") }
                            OutlinedTextField(
                                value = launcherAppFilter,
                                onValueChange = { launcherAppFilter = it },
                                label = { Text("Поиск приложения") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            if (launcherAvailablePackages.isNotEmpty()) {
                                Text("Найденные приложения • нажми «В док» или «Скрыть»", color = TextMuted, fontSize = 11.sp)
                                launcherAvailablePackages.filter { launcherAppFilter.isBlank() || it.contains(launcherAppFilter.trim(), ignoreCase = true) }.take(60).forEach { pkg ->
                                    val dockSet = launcherDockPackages.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.toSet()
                                    val hiddenSet = launcherHiddenPackages.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.toSet()
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text(pkg, color = TextMain, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            val next = if (pkg in dockSet) dockSet - pkg else dockSet + pkg
                                            launcherDockPackages = next.joinToString(", ")
                                        }) { Text(if (pkg in dockSet) "Убрать" else "В док") }
                                        TextButton(onClick = {
                                            val next = if (pkg in hiddenSet) hiddenSet - pkg else hiddenSet + pkg
                                            launcherHiddenPackages = next.joinToString(", ")
                                        }) { Text(if (pkg in hiddenSet) "Показать" else "Скрыть") }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = launcherDockPackages,
                                onValueChange = { launcherDockPackages = it },
                                label = { Text("Порядок дока — package names через запятую") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4
                            )
                            OutlinedTextField(
                                value = launcherHiddenPackages,
                                onValueChange = { launcherHiddenPackages = it },
                                label = { Text("Скрытые приложения — package names") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Заполнять свободные места дока", color = TextMain)
                                    Text("Свободные слоты займут приложения из общей сетки", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherFillDock, onCheckedChange = { launcherFillDock = it })
                            }
                            Button(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) {
                                    status = "Проверь IP и порт ADB"
                                } else {
                                    val dock = try { parseLauncherPackageList(launcherDockPackages) } catch (e: Exception) { status = errorText(e); return@Button }
                                    val hidden = try { parseLauncherPackageList(launcherHiddenPackages) } catch (e: Exception) { status = errorText(e); return@Button }
                                    settings.edit()
                                        .putString("launcher_dock_packages", launcherDockPackages)
                                        .putString("launcher_hidden_packages", launcherHiddenPackages)
                                        .putBoolean("launcher_fill_dock", launcherFillDock)
                                        .apply()
                                    runTask({
                                        connectWithRetry(context, host, p) { adb ->
                                            applyLauncherAppLists(adb, dock, hidden, launcherFillDock)
                                        }
                                    })
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Применение..." else "Применить док и список приложений") }
                            Text("Список сохраняется в текущей конфигурации Launcher. Очистка поля дока возвращает док из темы; чтобы вернуть конкретную плитку, удали её package name из поля скрытых приложений.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            Text("РОТАЦИЯ ОБОЕВ", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Text("Выбери несколько изображений — они сохранятся в приложении на телефоне и загрузятся в папку wallpapers Launcher. Поддерживаются JPEG, PNG и WebP: до 100 файлов, 10 МБ каждый, 50 МБ суммарно.", color = TextMuted, fontSize = 11.sp)
                            Text("Менять обои каждые: ${if (launcherWallpaperRotationMinutes <= 0) "выключено" else "$launcherWallpaperRotationMinutes мин."}", color = TextMain)
                            Slider(
                                value = launcherWallpaperRotationMinutes.toFloat(),
                                onValueChange = { launcherWallpaperRotationMinutes = ((it / 5f).toInt() * 5).coerceIn(0, 120) },
                                valueRange = 0f..120f,
                                steps = 23
                            )
                            Text("Порядок показа", color = TextMain)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { launcherWallpaperOrder = "shuffle" }, modifier = Modifier.weight(1f)) {
                                    Text(if (launcherWallpaperOrder == "shuffle") "✓ Случайный" else "Случайный")
                                }
                                OutlinedButton(onClick = { launcherWallpaperOrder = "sequential" }, modifier = Modifier.weight(1f)) {
                                    Text(if (launcherWallpaperOrder == "sequential") "✓ По порядку" else "По порядку")
                                }
                            }
                            Button(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else {
                                    settings.edit()
                                        .putInt("launcher_wallpaper_rotation_minutes", launcherWallpaperRotationMinutes)
                                        .putString("launcher_wallpaper_order", launcherWallpaperOrder)
                                        .apply()
                                    runTask({
                                        connectWithRetry(context, host, p) { adb ->
                                            applyWallpaperRotationSettings(adb, launcherWallpaperRotationMinutes, launcherWallpaperOrder)
                                        }
                                    })
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Применить интервал и порядок обоев") }
                            OutlinedButton(enabled = !busy, onClick = { wallpaperCollectionPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                                Text("Выбрать и загрузить изображения")
                            }
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else runTask({
                                    val directory = File(context.filesDir, "launcher_wallpapers")
                                    val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
                                    connectWithRetry(context, host, p) { adb ->
                                        uploadWallpaperCollection(adb, files, launcherWallpaperRotationMinutes, launcherWallpaperOrder)
                                    }
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Повторно загрузить сохранённую коллекцию") }
                            OutlinedButton(enabled = !busy, onClick = { pendingWallpaperCollectionClear = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Очистить коллекцию на телевизоре")
                            }
                            if (pendingWallpaperCollectionClear) {
                                AlertDialog(
                                    onDismissRequest = { pendingWallpaperCollectionClear = false },
                                    title = { Text("Удалить коллекцию обоев с ТВ?") },
                                    text = { Text("Будут удалены изображения из папки wallpapers на телевизоре и выключена автоматическая ротация. Копии изображений на телефоне останутся — их можно будет загрузить снова. Продолжить?") },
                                    confirmButton = {
                                        TextButton(enabled = !busy, onClick = {
                                            val host = ip.trim()
                                            val p = port.toIntOrNull()
                                            if (host.isBlank() || p == null || p !in 1..65535) {
                                                status = "Проверь IP и порт ADB"
                                                pendingWallpaperCollectionClear = false
                                            } else {
                                                pendingWallpaperCollectionClear = false
                                                runTask({
                                                    connectWithRetry(context, host, p) { adb -> clearWallpaperCollectionOnTv(adb) }
                                                })
                                            }
                                        }) { Text("Удалить") }
                                    },
                                    dismissButton = { TextButton(onClick = { pendingWallpaperCollectionClear = false }) { Text("Отмена") } }
                                )
                            }
                            Text("Для ротации требуется коллекция изображений и интервал больше нуля. При повторной загрузке коллекция на телевизоре заменяется выбранной на телефоне.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            Text("ЧАСЫ И ЗАСТАВКА", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Text("Настрой часы на главном экране и системную заставку Clean Your TV. Авточасы появляются после бездействия, а системная заставка запускается по таймеру Android TV.", color = TextMuted, fontSize = 11.sp)
                            Text("Показывать часы после бездействия: ${if (launcherIdleClockMinutes <= 0) "выключено" else "через $launcherIdleClockMinutes мин."}", color = TextMain)
                            Slider(
                                value = launcherIdleClockMinutes.toFloat(),
                                onValueChange = { launcherIdleClockMinutes = ((it / 5f).toInt() * 5).coerceIn(0, 60) },
                                valueRange = 0f..60f,
                                steps = 11
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Показывать обои на экране часов", color = TextMain)
                                    Text("Использовать текущие обои Launcher", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherClockWallpaper, onCheckedChange = { launcherClockWallpaper = it })
                            }
                            Text("Затемнение обоев часов: ${launcherClockWallpaperDim.toInt()}%", color = TextMain)
                            Slider(
                                value = launcherClockWallpaperDim,
                                onValueChange = { launcherClockWallpaperDim = it },
                                valueRange = 0f..90f,
                                steps = 8,
                                enabled = launcherClockWallpaper
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Цветное свечение часов", color = TextMain)
                                    Text("На OLED можно отключить для чистого чёрного фона", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherClockGlow, onCheckedChange = { launcherClockGlow = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Кнопка Назад открывает часы", color = TextMain)
                                    Text("Только на главном экране Launcher", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherBackOpensClock, onCheckedChange = { launcherBackOpensClock = it })
                            }
                            Text("Формат времени", color = TextMain)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { launcherClockFormat = "system" }, modifier = Modifier.weight(1f)) { Text(if (launcherClockFormat == "system") "✓ Системный" else "Системный") }
                                OutlinedButton(onClick = { launcherClockFormat = "12" }, modifier = Modifier.weight(1f)) { Text(if (launcherClockFormat == "12") "✓ 12 ч" else "12 ч") }
                                OutlinedButton(onClick = { launcherClockFormat = "24" }, modifier = Modifier.weight(1f)) { Text(if (launcherClockFormat == "24") "✓ 24 ч" else "24 ч") }
                            }
                            Button(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else {
                                    settings.edit()
                                        .putInt("launcher_idle_clock_minutes", launcherIdleClockMinutes)
                                        .putBoolean("launcher_clock_wallpaper", launcherClockWallpaper)
                                        .putInt("launcher_clock_wallpaper_dim", launcherClockWallpaperDim.toInt())
                                        .putBoolean("launcher_clock_glow", launcherClockGlow)
                                        .putBoolean("launcher_back_opens_clock", launcherBackOpensClock)
                                        .putString("launcher_clock_format", launcherClockFormat)
                                        .apply()
                                    runTask({
                                        connectWithRetry(context, host, p) { adb ->
                                            applyLauncherClockSettings(adb, launcherIdleClockMinutes, launcherClockWallpaper,
                                                launcherClockWallpaperDim.toInt(), launcherClockGlow, launcherBackOpensClock, launcherClockFormat)
                                        }
                                    })
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Применение..." else "Применить настройки часов") }
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else runTask({
                                    connectWithRetry(context, host, p) { adb -> configureClockDream(adb, true) }
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Установить системную заставку Clean Your TV") }
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else runTask({
                                    connectWithRetry(context, host, p) { adb -> configureClockDream(adb, false) }
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Отключить заставку Clean Your TV") }
                            Text("Системная заставка использует тайм-аут Android TV. После включения откроются настройки заставки, где можно выбрать время запуска. Другие заставки не удаляются.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            Text("ПОЛНАЯ РЕЗЕРВНАЯ КОПИЯ LAUNCHER", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Text("ZIP содержит config.json, текущий wallpaper.jpg (если доступен) и коллекцию ротации, сохранённую этим приложением на телефоне. Обои из сторонних источников, не сохранённые здесь, могут не попасть в архив.", color = TextMuted, fontSize = 11.sp)
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else runTask({
                                    connectWithRetry(context, host, p) { adb ->
                                        val (archive, hasWallpaper, collectionCount) = exportLauncherBackup(context, adb)
                                        activity.runOnUiThread {
                                            pendingLauncherBackupFile = archive
                                            pendingLauncherBackupHasWallpaper = hasWallpaper
                                            pendingLauncherBackupCollectionCount = collectionCount
                                        }
                                        "ZIP подготовлен: конфигурация, ${if (hasWallpaper) "текущие обои" else "без файла обоев"}, коллекция: $collectionCount."
                                    }
                                }, onFinished = { result ->
                                    if (result.startsWith("Ошибка:")) {
                                        status = result
                                    } else {
                                        status = "Выбери место для сохранения ZIP-резервной копии."
                                        launcherBackupExporter.launch("cleanyourtv-backup.zip")
                                    }
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Создать полную ZIP-копию") }
                            OutlinedButton(enabled = !busy, onClick = {
                                launcherBackupImporter.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            }, modifier = Modifier.fillMaxWidth()) { Text("Восстановить ZIP-копию") }
                            Text("Восстановление заменит config.json, текущий wallpaper.jpg и коллекцию ротации, если она есть в архиве. Если коллекции в архиве нет, существующая на телевизоре коллекция не меняется.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            Text("РЕЗЕРВНАЯ КОПИЯ CONFIG.JSON", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Text("Экспорт сохраняет настройки темы, дока, скрытых приложений, часов и кнопок. Изображение обоев в этот JSON не входит — для него используй выбор обоев отдельно.", color = TextMuted, fontSize = 11.sp)
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else runTask({
                                    connectWithRetry(context, host, p) { adb -> readCurrentLauncherConfig(adb).toString(2) }
                                }, onFinished = { result ->
                                    if (result.startsWith("Ошибка:")) {
                                        status = result
                                    } else {
                                        pendingLauncherConfigExport = result
                                        status = "Выбери место для сохранения config.json."
                                        launcherConfigExporter.launch("cleanyourtv-config.json")
                                    }
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Экспортировать конфигурацию Launcher") }
                            OutlinedButton(enabled = !busy, onClick = {
                                launcherConfigImporter.launch(arrayOf("application/json", "text/*"))
                            }, modifier = Modifier.fillMaxWidth()) { Text("Восстановить конфигурацию из JSON") }
                            Text("Перед восстановлением можно экспортировать текущую конфигурацию. Импорт заменит config.json на телевизоре и перезапустит Launcher.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            Text("КНОПКИ ПУЛЬТА", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Text("Одна запись на строку. Можно назначить кнопку на приложение, действие или вход телевизора. Список текущих назначений загружается вместе с настройками Launcher.", color = TextMuted, fontSize = 11.sp)
                            OutlinedTextField(
                                value = launcherRemoteKeyMappings,
                                onValueChange = { launcherRemoteKeyMappings = it },
                                label = { Text("Назначения кнопок") },
                                placeholder = { Text("KEYCODE_BUTTON_3:com.google.android.youtube.tv\nhold:KEYCODE_BUTTON_8:org.xbmc.kodi\nKEYCODE_PROG_RED:settings") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 8
                            )
                            Text("Форматы: KEYCODE_NAME:package, 190:package, scan:490:package, hold:KEYCODE_NAME:package. Действия: back, home, recents, mute, sleep, screensaver, settings, wifi, library, inputs. Также поддерживаются input:<id>, guide:<package> и package/.Activity.", color = TextMuted, fontSize = 10.sp)
                            Button(enabled = !busy, onClick = { pendingRemoteKeyEnable = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Сохранить и включить обработку кнопок")
                            }
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else runTask({
                                    connectWithRetry(context, host, p) { adb -> disableLauncherRemoteMappings(adb) }
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Очистить назначения кнопок") }
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else runTask({
                                    connectWithRetry(context, host, p) { adb ->
                                        shellText(adb, "am start -a android.settings.ACCESSIBILITY_SETTINGS")
                                        "Открыты настройки специальных возможностей телевизора."
                                    }
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки специальных возможностей") }
                            OutlinedButton(enabled = !busy, onClick = { pendingHomeKeyServiceEnable = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Включить HomeKeyService для кнопки Home / All Apps")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Долгое нажатие Home открывает Clean Your TV", color = TextMain)
                                    Text("Требуется HomeKeyService; меняет обработку удержания кнопки Home", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(checked = launcherHomeLongPressHome, onCheckedChange = { launcherHomeLongPressHome = it })
                            }
                            Button(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Проверь IP и порт ADB"
                                else {
                                    settings.edit().putBoolean("launcher_home_long_press_home", launcherHomeLongPressHome).apply()
                                    runTask({
                                        connectWithRetry(context, host, p) { adb ->
                                            applyLauncherHomeKeySettings(adb, launcherHomeLongPressHome)
                                        }
                                    })
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Применить поведение кнопки Home") }
                            if (pendingHomeKeyServiceEnable) {
                                AlertDialog(
                                    onDismissRequest = { pendingHomeKeyServiceEnable = false },
                                    title = { Text("Включить HomeKeyService?") },
                                    text = { Text("Дополнительная служба нужна некоторым пультам, например Xiaomi, которые отправляют KEYCODE_ALL_APPS вместо обычного Home. Она может изменить поведение кнопки Home. Включай её только если обычное назначение кнопок не срабатывает. Отключить службу можно в настройках специальных возможностей.") },
                                    confirmButton = {
                                        TextButton(enabled = !busy, onClick = {
                                            val host = ip.trim()
                                            val p = port.toIntOrNull()
                                            if (host.isBlank() || p == null || p !in 1..65535) {
                                                status = "Проверь IP и порт ADB"
                                                pendingHomeKeyServiceEnable = false
                                            } else {
                                                pendingHomeKeyServiceEnable = false
                                                runTask({
                                                    connectWithRetry(context, host, p) { adb -> enableHomeKeyService(adb) }
                                                })
                                            }
                                        }) { Text("Включить") }
                                    },
                                    dismissButton = { TextButton(onClick = { pendingHomeKeyServiceEnable = false }) { Text("Отмена") } }
                                )
                            }
                            Text("InputKeyService получает только события нажатий кнопок пульта. На некоторых прошивках, включая отдельные версии TCL Android 12, фильтрация может влиять на удержание кнопок громкости. Если это произойдёт, отключи InputKeyService в настройках специальных возможностей.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            if (pendingRemoteKeyEnable) {
                                AlertDialog(
                                    onDismissRequest = { pendingRemoteKeyEnable = false },
                                    title = { Text("Включить обработку кнопок пульта?") },
                                    text = { Text("Будет включён InputKeyService — служба специальных возможностей Clean Your TV. Она обрабатывает события кнопок и запускает назначенные приложения или действия, не запрашивая содержимое экрана. На некоторых прошивках может измениться поведение удержания клавиш громкости. Продолжить?") },
                                    confirmButton = {
                                        TextButton(enabled = !busy, onClick = {
                                            val mappings = try { parseLauncherRemoteMappings(launcherRemoteKeyMappings) } catch (e: Exception) {
                                                status = errorText(e)
                                                pendingRemoteKeyEnable = false
                                                return@TextButton
                                            }
                                            val host = ip.trim()
                                            val p = port.toIntOrNull()
                                            if (host.isBlank() || p == null || p !in 1..65535) {
                                                status = "Проверь IP и порт ADB"
                                                pendingRemoteKeyEnable = false
                                            } else {
                                                pendingRemoteKeyEnable = false
                                                settings.edit().putString("launcher_remote_key_mappings", launcherRemoteKeyMappings).apply()
                                                runTask({
                                                    connectWithRetry(context, host, p) { adb ->
                                                        applyLauncherRemoteMappings(adb, launcherRemoteKeyMappings)
                                                    }
                                                })
                                            }
                                        }) { Text("Включить") }
                                    },
                                    dismissButton = { TextButton(onClick = { pendingRemoteKeyEnable = false }) { Text("Отмена") } }
                                )
                            }
                            Text("ГОТОВЫЕ ТЕМЫ CLEAN YOUR TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Text("Темы загружаются из официального открытого проекта Clean Your TV. Для установки нужны интернет на телефоне и подключение к телевизору.", color = TextMuted, fontSize = 11.sp)
                            (listOf("cinema" to "Кино", "arcade" to "Аркада", "console" to "Консоль", "horizon" to "Горизонт", "library" to "Библиотека", "mist" to "Туман", "quiet" to "Тихий чёрный экран") +
                                if (java.time.LocalDate.now().toString() <= "2026-11-10") listOf("diwali" to "Дивали (до 10 ноября)") else emptyList()
                            ).forEach { (themeId, label) ->
                                OutlinedButton(enabled = !busy, onClick = {
                                    val host = ip.trim()
                                    val p = port.toIntOrNull()
                                    if (host.isBlank() || p == null || p !in 1..65535) {
                                        status = "Проверь IP и порт ADB"
                                    } else runTask({
                                        val files = downloadCleanYourTvTheme(context, themeId)
                                        connectWithRetry(context, host, p) { adb ->
                                            val snapshot = saveLauncherThemeRollback(context, adb)
                                            "$snapshot ${pushLauncherTheme(adb, themeId, files.first, files.second)}"
                                        }
                                    })
                                }, modifier = Modifier.fillMaxWidth()) { Text("Применить тему: $label") }
                            }
                            Text("Для установки темы передаются config.json и wallpaper.jpg. Тема меняет визуальное оформление, сохраняя закреплённые и скрытые приложения, назначения кнопок, настройки часов и параметры ротации обоев.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim()
                                val p = port.toIntOrNull()
                                val snapshot = File(context.filesDir, "launcher-theme-rollback.zip")
                                if (host.isBlank() || p == null || p !in 1..65535) {
                                    status = "Проверь IP и порт ADB"
                                } else if (!snapshot.isFile) {
                                    status = "Автоматической копии пока нет. Сначала примени любую тему — копия создаётся перед её установкой."
                                } else runTask({
                                    connectWithRetry(context, host, p) { adb ->
                                        restoreLauncherBackupFile(context, snapshot, adb)
                                    }
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Вернуть оформление до последней темы") }
                            Text("Перед каждой установкой темы приложение автоматически сохраняет полную ZIP-копию текущей конфигурации, обоев и доступной коллекции. Кнопка выше возвращает состояние до последней установленной темы.", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
                if (section != "usb" && devices.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("СОХРАНЁННЫЕ ТЕЛЕВИЗОРЫ", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            devices.toList().forEach { device ->
                                OutlinedButton(onClick = {
                                    tvName = device.name
                                    ip = device.ip
                                    port = device.port.toString()
                                    status = "Выбран ${device.name}"
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("${device.name} • ${device.ip}:${device.port}")
                                }
                            }
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Text(status, modifier = Modifier.padding(16.dp), color = if (status.startsWith("Ошибка")) Color(0xFFFF7777) else GoldLight)
                }
            }
        } else {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Открыть меню", tint = GoldLight)
                    }
                    Icon(
                        Icons.Default.Tv,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("TV APK Installer", fontSize = 24.sp)
                        Text(
                            "Управление Android TV",
                            color = Color.LightGray
                        )
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("ЦЕНТР УПРАВЛЕНИЯ", color = GoldLight, style = MaterialTheme.typography.titleMedium)
                        Text("Все инструменты находятся в левом боковом меню. Открой меню ☰ и выбери нужный раздел.", color = TextMuted)
                        Text("Для Wi-Fi ADB, Clean Your TV, пульта и установки APK предусмотрено отдельное подключение к телевизору.", color = TextMuted, fontSize = 12.sp)
                        Button(onClick = { drawerScope.launch { drawerState.open() } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Menu, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Открыть меню разделов")
                        }
                    }
                }

                if (showRemoteControl) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("ПУЛЬТ УПРАВЛЕНИЯ", color = GoldLight, style = MaterialTheme.typography.titleMedium)
                            Text("Команды отправляются через Wi-Fi ADB на выбранный IP и порт. Сначала включи отладку и подключи телевизор.", color = TextMuted, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Spacer(Modifier.weight(1f))
                                OutlinedButton(enabled = !busy, onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 19); "Пульт: вверх" } }) }) { Text("▲") }
                                Spacer(Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 21); "Пульт: влево" } }) }) { Text("◀") }
                                Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 23); "Пульт: OK" } }) }) { Text("OK") }
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 22); "Пульт: вправо" } }) }) { Text("▶") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Spacer(Modifier.weight(1f))
                                OutlinedButton(enabled = !busy, onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 20); "Пульт: вниз" } }) }) { Text("▼") }
                                Spacer(Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 4); "Пульт: назад" } }) }) { Text("Назад") }
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 3); "Пульт: домой" } }) }) { Text("Домой") }
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 82); "Пульт: меню" } }) }) { Text("Меню") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 25); "Громкость уменьшена" } }) }) { Text("Громкость −") }
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 24); "Громкость увеличена" } }) }) { Text("Громкость +") }
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 164); "Звук переключён" } }) }) { Text("Mute") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 223); "Команда сна отправлена (если поддерживается)" } }) }) { Text("Сон") }
                                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { runTask({ connectWithRetry(context, ip.trim(), port.toIntOrNull() ?: 5555) { adb -> sendTvKey(adb, 26); "Команда питания отправлена (если поддерживается)" } }) }) { Text("Питание") }
                            }
                            Text("Поддержка клавиш зависит от прошивки. Команды «Сон» и «Питание» могут быть заблокированы производителем.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Мои телевизоры",
                            style = MaterialTheme.typography.titleMedium
                        )

                        if (devices.isEmpty()) {
                            Text(
                                "Сохранённых телевизоров пока нет",
                                color = Color.LightGray
                            )
                        }

                        devices.toList().forEach { device ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.name)
                                    Text(
                                        "${device.ip}:${device.port}",
                                        color = Color.LightGray,
                                        fontSize = 12.sp
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        tvName = device.name
                                        ip = device.ip
                                        port = device.port.toString()
                                        usbMode = false
                                        status = "Выбран ${device.name}"
                                    }
                                ) {
                                    Text("Выбрать")
                                }

                                IconButton(
                                    onClick = {
                                        devices.remove(device)
                                        saveDevices(context, devices)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Удалить"
                                    )
                                }
                            }
                            HorizontalDivider(color = Color.DarkGray)
                        }
                    }
                }

                if (showCleanYourTv) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Clean Your TV — управление устройством", style = MaterialTheme.typography.titleMedium)
                        Text(tvDetails, color = Color.LightGray, fontSize = 13.sp)
                        Text("Профиль устройства: ${detectTvProfile(tvDetails)}", color = GoldLight, fontSize = 13.sp)
                        if (matchedTvProfile != null) {
                            val matched = matchedTvProfile.profile
                            Text("Профиль Clean Your TV: ${tvProfileDisplayName(matched.id)}", color = GoldLight, fontSize = 13.sp)
                            Text("Платформа: ${matched.platform} • ${matchedTvProfile.matchLabel}. Пакетов: ${matched.packages.size}; требуют ручной оценки: ${matched.packages.count { it.value.ask }}; защищено: ${matched.neverDisable.size}.", color = TextMuted, fontSize = 11.sp)
                            Text("Данные профиля: ${matched.testedBy}; прошивка ${matched.testedFirmware}; проверка перезагрузки: ${if (matched.rebootTested) "да" else "нет"}.", color = TextMuted, fontSize = 10.sp)
                        } else {
                            Text("Точная модель в каталоге Clean Your TV не найдена. Используется общий справочник без модельных рекомендаций.", color = TextMuted, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { profileCatalogExpanded = !profileCatalogExpanded }
                        ) {
                            Text(if (profileCatalogExpanded) "Скрыть каталог профилей (${tvModelProfiles.size})" else "Каталог профилей устройств (${tvModelProfiles.size})")
                        }
                        if (profileCatalogExpanded) {
                            tvModelProfiles.sortedBy { it.id }.forEach { profile ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(tvProfileDisplayName(profile.id), color = GoldLight, fontSize = 12.sp)
                                    Text(
                                        "${profile.platform} • моделей: ${profile.models.size} • пакетов: ${profile.packages.size} • защищено: ${profile.neverDisable.size}",
                                        color = TextMain, fontSize = 10.sp
                                    )
                                    Text(
                                        "Бренд: ${profile.brand.ifBlank { "не указан" }} • SDK: ${profile.sdkMin?.toString() ?: "—"}–${profile.sdkMax?.toString() ?: "—"} • проверено: ${profile.testedBy} / ${profile.testedFirmware}",
                                        color = TextMuted, fontSize = 10.sp
                                    )
                                }
                                HorizontalDivider(color = Color.DarkGray)
                            }
                            Text("Профили показывают данные и правила из открытого каталога. Если устройство не совпадает с условиями профиля, его правила не применяются автоматически.", color = Color(0xFFFFD98A), fontSize = 10.sp)
                        }
                        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            val host = ip.trim()
                            val p = port.toIntOrNull()
                            if (host.isBlank() || p == null || p !in 1..65535) {
                                status = "Сначала подключись к телевизору по Wi-Fi ADB"
                            } else runTask({
                                connectWithRetry(context, host, p) { adb ->
                                    val report = readTvDiagnostics(adb)
                                    activity.runOnUiThread { diagnostics = report }
                                    "Диагностика завершена"
                                }
                            })
                        }) { Text("Диагностика: память, хранилище, батарея") }
                        Text(diagnostics, color = Color.LightGray, fontSize = 11.sp)

                        HorizontalDivider(color = Color.DarkGray)
                        Text("АВТОЗАПУСК ПРИ ВКЛЮЧЕНИИ TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                        Text("Поиск получателей BOOT_COMPLETED и LOCKED_BOOT_COMPLETED среди пользовательских приложений. Системные пакеты не изменяются.", color = Color.LightGray, fontSize = 11.sp)
                        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            val host = ip.trim()
                            val p = port.toIntOrNull() ?: 5555
                            runTask({
                                connectWithRetry(context, host, p) { adb ->
                                    val found = findAutoStartReceivers(adb)
                                    activity.runOnUiThread {
                                        autoStartReceivers = found
                                        autoStartLoaded = true
                                    }
                                    "Найдено компонентов автозапуска: ${found.size}"
                                }
                            })
                        }) { Text(if (autoStartLoaded) "Обновить список автозапуска" else "Найти автозапуск") }
                        if (!autoStartLoaded) {
                            Text("Сначала подключись к телевизору и нажми «Найти автозапуск».", color = TextMuted, fontSize = 11.sp)
                        } else if (autoStartReceivers.isEmpty() && disabledAutoStart.isEmpty()) {
                            Text("Получатели автозапуска не найдены или команда не поддерживается этой прошивкой.", color = TextMuted, fontSize = 11.sp)
                        }
                        autoStartReceivers.forEach { receiver ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(receiver.packageName, color = TextMain, fontSize = 12.sp)
                                Text(receiver.component.substringAfter("/"), color = TextMuted, fontSize = 11.sp)
                                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                    pendingAutoStartDisable = receiver.component
                                }) { Text("Отключить автозапуск этого компонента") }
                            }
                            HorizontalDivider(color = Color.DarkGray)
                        }
                        if (disabledAutoStart.isNotEmpty()) {
                            Text("Отключено в TV APK Installer: ${disabledAutoStart.size}", color = GoldLight)
                            disabledAutoStart.sorted().forEach { component ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(component, color = TextMuted, fontSize = 11.sp)
                                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                        val host = ip.trim()
                                        val p = port.toIntOrNull() ?: 5555
                                        runTask({
                                            connectWithRetry(context, host, p) { adb ->
                                                setAutoStartReceiverEnabled(adb, component, true)
                                                "Автозапуск восстановлен: $component"
                                            }
                                        }, { result ->
                                            if (!result.startsWith("Ошибка")) {
                                                disabledAutoStart = disabledAutoStart - component
                                                settings.edit().putStringSet("disabled_autostart_components", disabledAutoStart).apply()
                                                autoStartReceivers = autoStartReceivers + AutoStartReceiver(component, component.substringBefore("/"))
                                            }
                                        })
                                    }) { Text("Восстановить автозапуск") }
                                }
                            }
                        }
                        if (pendingAutoStartDisable != null) {
                            AlertDialog(
                                onDismissRequest = { pendingAutoStartDisable = null },
                                title = { Text("Отключить автозапуск?") },
                                text = { Text("Компонент: ${pendingAutoStartDisable}\n\nПриложение может перестать выполнять фоновые задачи после включения телевизора. Само приложение не удаляется. Продолжить?") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val component = pendingAutoStartDisable ?: return@TextButton
                                        pendingAutoStartDisable = null
                                        val host = ip.trim()
                                        val p = port.toIntOrNull() ?: 5555
                                        runTask({
                                            connectWithRetry(context, host, p) { adb ->
                                                setAutoStartReceiverEnabled(adb, component, false)
                                                "Автозапуск отключён: $component"
                                            }
                                        }, { result ->
                                            if (!result.startsWith("Ошибка")) {
                                                disabledAutoStart = disabledAutoStart + component
                                                settings.edit().putStringSet("disabled_autostart_components", disabledAutoStart).apply()
                                                autoStartReceivers = autoStartReceivers.filterNot { it.component == component }
                                            }
                                        })
                                    }) { Text("Отключить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingAutoStartDisable = null }) { Text("Отмена") }
                                }
                            )
                        }

                        Text("УПРАВЛЕНИЕ ТЕЛЕВИЗОРОМ", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            val host = ip.trim()
                            val p = port.toIntOrNull() ?: 5555
                            runTask({
                                connectWithRetry(context, host, p) { adb ->
                                    shellText(adb, "input keyevent 3")
                                    "На телевизоре открыт главный экран"
                                }
                            })
                        }) { Text("Открыть главный экран TV") }
                        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            val host = ip.trim()
                            val p = port.toIntOrNull() ?: 5555
                            runTask({
                                connectWithRetry(context, host, p) { adb ->
                                    shellText(adb, "am start -a android.settings.SETTINGS")
                                    "Открыты настройки телевизора"
                                }
                            })
                        }) { Text("Открыть настройки Android TV") }
                        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            showRebootConfirm = true
                        }) { Text("Перезагрузить телевизор") }
                        Text("Команды выполняются через Wi-Fi ADB. Перезагрузка требует отдельного подтверждения.", color = Color.LightGray, fontSize = 11.sp)

                        if (showRebootConfirm) {
                            AlertDialog(
                                onDismissRequest = { showRebootConfirm = false },
                                title = { Text("Перезагрузить телевизор?") },
                                text = { Text("Текущая ADB-сессия будет разорвана, телевизор перезапустится. После загрузки подключись снова.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showRebootConfirm = false
                                        val host = ip.trim()
                                        val p = port.toIntOrNull() ?: 5555
                                        runTask({
                                            connectWithRetry(context, host, p) { adb ->
                                                adb.shell("reboot")
                                                "Команда перезагрузки отправлена"
                                            }
                                        })
                                    }) { Text("Перезагрузить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showRebootConfirm = false }) { Text("Отмена") }
                                }
                            )
                        }

                        val activeProfile = matchedTvProfile?.profile
                        val cleanupCandidates = findCleanupCandidates(allPackages, packageFacts, activeProfile)
                        val verifiedCleanupCandidates = cleanupCandidates.filter {
                            packageDisableSupported &&
                                activeProfile?.packages?.get(it)?.ask == false &&
                                it !in (activeProfile?.neverDisable ?: emptySet()) &&
                                it !in disabledOnDevice &&
                                !isProtectedSystemPackage(it)
                        }
                        val knownCandidates = cleanupCandidates.count { packageFacts.containsKey(it) || activeProfile?.packages?.containsKey(it) == true }
                        val privacyAudit = allPackages.mapNotNull { pkg ->
                            val fact = packageFacts[pkg]
                            val profileFact = activeProfile?.packages?.get(pkg)
                            val category = fact?.category ?: profileFact?.category ?: return@mapNotNull null
                            val name = fact?.name ?: pkg
                            val description = fact?.description ?: "Компонент указан в профиле этой модели; назначение следует проверить."
                            Triple(pkg, category, "$name — $description")
                        }
                        val privacyGroups = privacyAudit.groupBy { it.second }.toSortedMap()
                        val privacySensitive = privacyAudit.count { it.second in setOf("ads", "tracking", "voice", "brand") }
                        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("АУДИТ ПРИВАТНОСТИ", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (allPackages.isEmpty()) "Сначала определи устройство: аудит читает список установленных пакетов через ADB."
                                    else "Локальная проверка без отправки данных в интернет. Найдено ${privacyAudit.size} известных компонентов из ${allPackages.size}; рекламные, аналитические, голосовые и брендовые — ${privacySensitive}.",
                                    color = TextMuted, fontSize = 11.sp
                                )
                                privacyGroups.forEach { (category, entries) ->
                                    Text("${cleanupCategoryLabel(category)}: ${entries.size}", color = TextMain, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    enabled = allPackages.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { privacyAuditExpanded = !privacyAuditExpanded }
                                ) { Text(if (privacyAuditExpanded) "Скрыть результаты аудита" else "Подробный отчёт по пакетам") }
                                if (privacyAuditExpanded) {
                                    privacyGroups.forEach { (category, entries) ->
                                        Text(cleanupCategoryLabel(category).uppercase(), color = GoldLight, style = MaterialTheme.typography.labelMedium)
                                        entries.sortedBy { it.first }.forEach { (pkg, _, detail) ->
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(pkg, color = TextMain, fontSize = 11.sp)
                                                Text(detail, color = TextMuted, fontSize = 10.sp)
                                                if (pkg in disabledOnDevice) Text("Отключён на телевизоре", color = Color(0xFFFFD98A), fontSize = 10.sp)
                                            }
                                        }
                                    }
                                    Text("Категории и описания взяты из открытого справочника Clean Your TV и профиля устройства. Это инвентаризация известных пакетов, а не доказательство передачи данных конкретным приложением и не заключение о безопасности.", color = Color(0xFFFFD98A), fontSize = 10.sp)
                                }
                            }
                        }

                        cleanupJournal?.let { journal ->
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("НЕЗАВЕРШЁННАЯ ОЧИСТКА", color = Color(0xFFFFD98A), style = MaterialTheme.typography.titleSmall)
                                    Text("Журнал от ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(journal.createdAt))}. Успешно отключено: ${journal.completedPackages.size + if (journal.inFlightPackage != null) 1 else 0} из ${journal.plannedPackages.size}.", color = TextMain, fontSize = 12.sp)
                                    Text("Восстановление разрешено только на исходном телевизоре (проверяется производитель, модель, устройство и отпечаток прошивки).", color = TextMuted, fontSize = 11.sp)
                                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { pendingCleanupRollback = true }) {
                                        Text("Откатить незавершённую очистку")
                                    }
                                    if (pendingCleanupRollback) {
                                        AlertDialog(
                                            onDismissRequest = { pendingCleanupRollback = false },
                                            title = { Text("Восстановить отключённые пакеты?") },
                                            text = { Text("Будут включены только пакеты, которые эта операция успела отключить: ${journal.completedPackages.joinToString()}. Сначала будет проверено, что подключён именно исходный телевизор. Продолжить?") },
                                            confirmButton = {
                                                TextButton(enabled = !busy, onClick = {
                                                    pendingCleanupRollback = false
                                                    val host = journal.host
                                                    val p = journal.port
                                                    if (host.isBlank() || p !in 1..65535) {
                                                        status = "В журнале нет корректного адреса исходного телевизора"
                                                    } else runTask({
                                                        connectWithRetry(context, host, p) { adb ->
                                                            require(readTvIdentity(adb) == journal.deviceIdentity) {
                                                                "Это не тот телевизор, на котором началась очистка. Откат отменён."
                                                            }
                                                            val installed = readAllPackages(adb).toSet()
                                                            val disabledNowOnTv = readDisabledPackages(adb)
                                                            val rollbackCandidates = (journal.completedPackages + listOfNotNull(journal.inFlightPackage?.takeIf { it in disabledNowOnTv })).distinct()
                                                            // Version 1 journals predate snapshots; preserve their existing rollback behavior.
                                                            // Version 2 restores only packages that were enabled before this cleanup.
                                                            val rollbackPackages = if (journal.schemaVersion >= 2) {
                                                                rollbackCandidates.filter { it !in journal.baselineDisabledPackages }
                                                            } else rollbackCandidates
                                                            val restored = mutableListOf<String>()
                                                            val failures = mutableListOf<String>()
                                                            rollbackPackages.forEach { pkg ->
                                                                if (pkg !in installed) {
                                                                    failures.add("$pkg (пакет отсутствует)")
                                                                } else {
                                                                    try {
                                                                        val before = readDisabledPackages(adb)
                                                                        if (pkg in before) changePackageStateVerified(adb, pkg, true)
                                                                    } catch (_: Exception) {
                                                                        // ADB may report an error after the device applied the change.
                                                                    }
                                                                    val verifiedDisabled = try { readDisabledPackages(adb) } catch (_: Exception) { null }
                                                                    if (verifiedDisabled != null && pkg !in verifiedDisabled) {
                                                                        restored.add(pkg)
                                                                    } else {
                                                                        failures.add(pkg + if (verifiedDisabled == null) " (не удалось проверить состояние)" else " (остался отключён)")
                                                                    }
                                                                }
                                                                val stillPending = rollbackPackages - restored.toSet()
                                                                val nextJournal = journal.copy(
                                                                    completedPackages = stillPending,
                                                                    inFlightPackage = journal.inFlightPackage?.takeIf { it in stillPending },
                                                                    restoredPackages = (journal.restoredPackages + restored).distinct()
                                                                )
                                                                saveCleanupJournal(context, nextJournal)
                                                            }
                                                            val remaining = rollbackPackages - restored.toSet()
                                                            val actualDisabledAfterRestore = readDisabledPackages(adb)
                                                            activity.runOnUiThread {
                                                                cleanupJournal = if (remaining.isEmpty()) null else journal.copy(
                                                                    completedPackages = remaining,
                                                                    inFlightPackage = journal.inFlightPackage?.takeIf { it in remaining },
                                                                    restoredPackages = (journal.restoredPackages + restored).distinct()
                                                                )
                                                                disabledOnDevice = actualDisabledAfterRestore
                                                                disabledPackages = disabledPackages - restored.toSet()
                                                                managedSystemDisabledPackages = managedSystemDisabledPackages - restored.toSet()
                                                                settings.edit().putStringSet("disabled_packages", disabledPackages)
                                                                    .putStringSet("managed_system_disabled_packages", managedSystemDisabledPackages).apply()
                                                            }
                                                            if (remaining.isEmpty()) {
                                                                appendCleanupHistory(context, journal, "Восстановлено", (journal.restoredPackages + restored).distinct())
                                                                clearCleanupJournal(context)
                                                                activity.runOnUiThread { cleanupHistory = loadCleanupHistory(context) }
                                                                "Откат завершён: восстановлено ${restored.size} пакетов."
                                                            } else {
                                                                "Откат частичный: восстановлено ${restored.size}. Осталось: ${remaining.joinToString()}. Ошибки: ${failures.joinToString()}"
                                                            }
                                                        }
                                                    }, { cleanupJournal = loadCleanupJournal(context) })
                                                }) { Text("Восстановить") }
                                            },
                                            dismissButton = { TextButton(onClick = { pendingCleanupRollback = false }) { Text("Отмена") } }
                                        )
                                    }
                                }
                            }
                        }
                        if (cleanupHistory.isNotEmpty()) {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ИСТОРИЯ ОЧИСТКИ", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                    OutlinedButton(
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { cleanupHistoryExporter.launch("tv-apk-cleanup-history.json") }
                                    ) { Text("Экспортировать историю (JSON)") }
                                    cleanupHistory.forEach { entry ->
                                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(
                                                "${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.createdAt))} · ${entry.outcome}",
                                                color = TextMain, fontSize = 12.sp
                                            )
                                            Text(
                                                "Изменено: ${entry.changedPackages.size} из ${entry.plannedCount}; до операции отключено: ${entry.baselineDisabledCount}",
                                                color = TextMuted, fontSize = 10.sp
                                            )
                                            if (entry.changedPackages.isNotEmpty()) {
                                                Text(entry.changedPackages.joinToString(), color = TextMuted, fontSize = 10.sp)
                                                if (entry.outcome.contains("Очистка", ignoreCase = true)) {
                                                    OutlinedButton(
                                                        enabled = !busy,
                                                        modifier = Modifier.fillMaxWidth(),
                                                        onClick = { pendingCleanupHistoryUndo = entry }
                                                    ) { Text("Отменить эту очистку") }
                                                }
                                            }
                                            if (pendingCleanupHistoryUndo?.createdAt == entry.createdAt) {
                                                val undoEntry = entry
                                                AlertDialog(
                                                    onDismissRequest = { pendingCleanupHistoryUndo = null },
                                                    title = { Text("Отменить выбранную очистку?") },
                                                    text = {
                                                        Column(
                                                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            Text("Будут включены только пакеты, которые эта операция отключила и которые сейчас всё ещё отключены.", color = TextMain)
                                                            Text("Перед изменениями приложение проверит, что подключён тот же телевизор.", color = TextMuted, fontSize = 11.sp)
                                                            undoEntry.changedPackages.forEach { Text("• $it", color = TextMuted, fontSize = 11.sp) }
                                                        }
                                                    },
                                                    confirmButton = {
                                                        TextButton(enabled = !busy, onClick = {
                                                            pendingCleanupHistoryUndo = null
                                                            val host = ip.trim()
                                                            val p = port.toIntOrNull()
                                                            if (host.isBlank() || p == null || p !in 1..65535) {
                                                                status = "Проверь IP и порт ADB"
                                                            } else {
                                                                runTask({
                                                                    connectWithRetry(context, host, p) { adb ->
                                                                        require(readTvIdentity(adb) == undoEntry.deviceIdentity) {
                                                                            "История относится к другому телевизору или другой прошивке. Ничего не изменено."
                                                                        }
                                                                        val installedNow = readAllPackages(adb).toSet()
                                                                        val disabledNow = readDisabledPackages(adb)
                                                                        val missing = undoEntry.changedPackages.filter { it !in installedNow }
                                                                        val candidates = undoEntry.changedPackages.filter { it in installedNow && it in disabledNow }
                                                                        val failures = mutableListOf<String>()
                                                                        candidates.forEach { pkg ->
                                                                            try {
                                                                                changePackageStateVerified(adb, pkg, true)
                                                                            } catch (e: Exception) {
                                                                                failures.add("$pkg (${errorText(e)})")
                                                                            }
                                                                        }
                                                                        val verified = readDisabledPackages(adb)
                                                                        val restored = candidates.filter { it !in verified }
                                                                        val stillDisabled = candidates - restored.toSet()
                                                                        activity.runOnUiThread {
                                                                            disabledOnDevice = verified
                                                                            disabledPackages = disabledPackages - restored.toSet()
                                                                            managedSystemDisabledPackages = managedSystemDisabledPackages - restored.toSet()
                                                                            settings.edit()
                                                                                .putStringSet("disabled_packages", disabledPackages)
                                                                                .putStringSet("managed_system_disabled_packages", managedSystemDisabledPackages)
                                                                                .apply()
                                                                        }
                                                                        "Отмена очистки: включено ${restored.size} пакетов. Уже включены: ${undoEntry.changedPackages.size - candidates.size}. Не установлены: ${missing.size}. Остались отключёнными: ${stillDisabled.size}. Ошибки: ${failures.size}" +
                                                                            if (failures.isNotEmpty()) "\n" + failures.joinToString("\n") else ""
                                                                    }
                                                                })
                                                            }
                                                        }) { Text("Подтвердить отмену") }
                                                    },
                                                    dismissButton = {
                                                        TextButton(onClick = { pendingCleanupHistoryUndo = null }) { Text("Назад") }
                                                    }
                                                )
                                            }
                                            Divider(color = Color.DarkGray)
                                        }
                                    }
                                    Text("Хранится до 30 последних завершённых операций на этом устройстве.", color = TextMuted, fontSize = 10.sp)
                                }
                            }
                        }

                        Text("Проверка всего списка: " + allPackages.size + " пакетов. Совпадений: " + cleanupCandidates.size + ", из них в справочнике: " + knownCandidates + ".", color = Color.LightGray, fontSize = 12.sp)
                        Text("Справочник Clean Your TV: " + packageFacts.size + " общих пакетов" + (activeProfile?.let { " + ${it.packages.size} по модели" } ?: "") + ". Подсказки не являются командой на отключение.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                        if (allPackages.isEmpty()) Text("Полный список пакетов ещё не считан. Нажми «Определить устройство».", color = Color.LightGray, fontSize = 11.sp)
                        if (verifiedCleanupCandidates.isNotEmpty()) {
                            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                selectedCleanupPackages = verifiedCleanupCandidates.toSet()
                            }) { Text("Выбрать проверенные для этой модели (${verifiedCleanupCandidates.size})") }
                        }
                        if (selectedCleanupPackages.isNotEmpty()) {
                            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                showCleanupPlan = true
                            }) { Text("Сформировать план очистки (${selectedCleanupPackages.size})") }
                        }
                        if (showCleanupPlan) {
                            val planPackages = selectedCleanupPackages.sorted()
                            val allowedPlan = planPackages.filter { it in verifiedCleanupCandidates }
                            val blockedPlan = planPackages - allowedPlan.toSet()
                            AlertDialog(
                                onDismissRequest = { showCleanupPlan = false },
                                title = { Text("План очистки") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                                        Text("Будет отключено: ${allowedPlan.size}. Пропущено по правилам безопасности: ${blockedPlan.size}.", color = TextMain)
                                        if (allowedPlan.isNotEmpty()) {
                                            Text("РАЗРЕШЕНО ПРОФИЛЕМ ЭТОЙ МОДЕЛИ", color = GoldLight, style = MaterialTheme.typography.labelMedium)
                                            allowedPlan.forEach { Text("• $it", color = TextMain, fontSize = 11.sp) }
                                        }
                                        if (blockedPlan.isNotEmpty()) {
                                            Text("НЕ БУДУТ ИЗМЕНЕНЫ", color = Color(0xFFFFD98A), style = MaterialTheme.typography.labelMedium)
                                            blockedPlan.forEach { Text("• $it", color = TextMuted, fontSize = 11.sp) }
                                        }
                                        Text("План предварительный: перед выполнением приложение повторно сверит пакеты, профиль телевизора и защитные правила. Пакеты будут отключены, а не удалены.", color = TextMuted, fontSize = 11.sp)
                                    }
                                },
                                confirmButton = {
                                    TextButton(enabled = !busy && allowedPlan.isNotEmpty(), onClick = {
                                        pendingCleanupPackages = allowedPlan
                                        showCleanupPlan = false
                                    }) { Text("Перейти к подтверждению") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showCleanupPlan = false }) { Text("Закрыть") }
                                }
                            )
                        }
                        cleanupCandidates.forEach { candidate ->
                            val profileRule = activeProfile?.packages?.get(candidate)
                            val fact = packageFacts[candidate] ?: profileRule?.let { profileFact ->
                                PackageFact(candidate, candidate, "Рекомендация из профиля ${activeProfile.id}. Проверь назначение компонента перед действием.", profileFact.category)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (fact != null) {
                                    Checkbox(
                                        checked = candidate in selectedCleanupPackages,
                                        onCheckedChange = { checked ->
                                            selectedCleanupPackages = if (checked) selectedCleanupPackages + candidate
                                            else selectedCleanupPackages - candidate
                                        },
                                        enabled = !busy && candidate !in disabledOnDevice && profileRule?.ask == false && candidate !in (activeProfile?.neverDisable ?: emptySet())
                                    )
                                } else {
                                    Spacer(Modifier.width(48.dp))
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                                    Text(candidate, color = GoldLight, fontSize = 12.sp)
                                    if (candidate in disabledOnDevice) Text("Уже отключено на телевизоре.", color = Color(0xFFFFD98A), fontSize = 10.sp)
                                    if (fact != null) {
                                        Text(fact.name + " • " + cleanupCategoryLabel(fact.category), color = TextMain, fontSize = 11.sp)
                                        if (profileRule?.ask == true) Text("В профиле Clean Your TV: требуется ручная оценка перед отключением.", color = Color(0xFFFFD98A), fontSize = 10.sp)
                                        Text(fact.description, color = Color.LightGray, fontSize = 10.sp)
                                    } else {
                                        Text("Совпадение по названию пакета; назначение не подтверждено справочником.", color = Color.LightGray, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        if (cleanupCandidates.isNotEmpty()) Text(if (packageDisableSupported) "Для пакетного отключения доступны только компоненты точного профиля модели с правилом ask=false. Остальные рекомендации справочные. Перед каждой операцией результат проверяется через Package Manager." else "Пакетная очистка заблокирована: устройство ещё не подтвердило поддержку команды отключения и чтения фактического состояния пакетов. Повтори определение устройства.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                        if (pendingCleanupPackages.isNotEmpty()) {
                            AlertDialog(
                                onDismissRequest = { pendingCleanupPackages = emptyList() },
                                title = { Text("Отключить выбранные компоненты?") },
                                text = {
                                    Text("Будут отключены, но не удалены, только эти пакеты:\n\n${pendingCleanupPackages.joinToString("\n")}\n\nСистемные функции телевизора могут зависеть от этих компонентов. Их можно будет восстановить кнопкой «Восстановить все отключённые». Продолжить?")
                                },
                                confirmButton = {
                                    TextButton(enabled = !busy, onClick = {
                                        val selected = pendingCleanupPackages
                                        pendingCleanupPackages = emptyList()
                                        val host = ip.trim()
                                        val p = port.toIntOrNull()
                                        if (host.isBlank() || p == null || p !in 1..65535) {
                                            status = "Сначала укажи IP и порт ADB"
                                        } else runTask({
                                            connectWithRetry(context, host, p) { adb ->
                                                require(readPackageDisableCapability(adb)) {
                                                    "Прошивка не подтвердила поддержку отключения пакетов и чтения их состояния. Очистка отменена без изменений."
                                                }
                                                val installed = readAllPackages(adb).toSet()
                                                val freshDetails = readTvDetails(adb)
                                                val freshProfile = matchTvModelProfile(freshDetails, tvModelProfiles)?.profile
                                                val freshCandidates = findCleanupCandidates(installed.toList(), packageFacts, freshProfile).toSet()
                                                val alreadyDisabled = readDisabledPackages(adb)
                                                val safeSelected = selected.filter { pkg ->
                                                    pkg in installed && pkg !in alreadyDisabled && pkg in freshCandidates &&
                                                        freshProfile?.packages?.get(pkg)?.ask == false &&
                                                        !isProtectedSystemPackage(pkg) && pkg !in (freshProfile?.neverDisable ?: emptySet())
                                                }
                                                require(safeSelected.size == selected.size) {
                                                    "Список пакетов телевизора изменился или часть компонентов защищена. Повтори определение устройства и проверь рекомендации."
                                                }
                                                val deviceIdentity = readTvIdentity(adb)
                                                // Persist the exact pre-cleanup disabled-package state before changing anything.
                                                val baselineDisabled = readDisabledPackages(adb).toList().sorted()
                                                var journal = CleanupJournal(
                                                    deviceIdentity = deviceIdentity,
                                                    host = host,
                                                    port = p,
                                                    plannedPackages = safeSelected,
                                                    completedPackages = emptyList(),
                                                    createdAt = System.currentTimeMillis(),
                                                    baselineDisabledPackages = baselineDisabled,
                                                    schemaVersion = 2
                                                )
                                                saveCleanupJournal(context, journal)
                                                activity.runOnUiThread { cleanupJournal = journal }
                                                val disabledNow = mutableListOf<String>()
                                                val failures = mutableListOf<String>()
                                                safeSelected.forEach { pkg ->
                                                    journal = journal.copy(inFlightPackage = pkg)
                                                    saveCleanupJournal(context, journal)
                                                    try {
                                                        changePackageStateVerified(adb, pkg, false)
                                                        disabledNow.add(pkg)
                                                        journal = journal.copy(completedPackages = disabledNow.toList(), inFlightPackage = null)
                                                        saveCleanupJournal(context, journal)
                                                    } catch (_: Exception) {
                                                        if (pkg in readDisabledPackages(adb)) disabledNow.add(pkg)
                                                        else failures.add(pkg)
                                                        journal = journal.copy(completedPackages = disabledNow.toList(), inFlightPackage = null)
                                                        saveCleanupJournal(context, journal)
                                                    }
                                                }
                                                if (failures.isEmpty()) {
                                                    appendCleanupHistory(context, journal, "Очистка завершена", disabledNow)
                                                    clearCleanupJournal(context)
                                                    activity.runOnUiThread {
                                                        cleanupJournal = null
                                                        cleanupHistory = loadCleanupHistory(context)
                                                    }
                                                } else {
                                                    activity.runOnUiThread { cleanupJournal = journal }
                                                }
                                                activity.runOnUiThread {
                                                    if (disabledNow.isNotEmpty()) {
                                                        val userDisabledNow = disabledNow.filter { it in userPackages }.toSet()
                                                        val systemDisabledNow = disabledNow.filter { it !in userPackages }.toSet()
                                                        disabledPackages = disabledPackages + userDisabledNow
                                                        managedSystemDisabledPackages = managedSystemDisabledPackages + systemDisabledNow
                                                        disabledOnDevice = disabledOnDevice + disabledNow
                                                        selectedCleanupPackages = selectedCleanupPackages - disabledNow.toSet()
                                                        settings.edit()
                                                            .putStringSet("disabled_packages", disabledPackages)
                                                            .putStringSet("managed_system_disabled_packages", managedSystemDisabledPackages)
                                                            .apply()
                                                    }
                                                }
                                                "Отключено: ${disabledNow.size} из ${safeSelected.size}. Ошибок: ${failures.size}" +
                                                    if (failures.isEmpty()) "" else "\nНе удалось отключить: ${failures.joinToString()}"
                                            }
                                        })
                                    }) { Text("Отключить выбранные") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingCleanupPackages = emptyList() }) { Text("Отмена") }
                                }
                            )
                        }
                        OutlinedButton(
                            enabled = !busy && restorePointExists && restorePointSchemaVersion >= 2 && !restorePointDeviceFingerprint.isNullOrBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                try {
                                    val fingerprint = restorePointDeviceFingerprint
                                        ?: throw Exception("Создай точку восстановления заново")
                                    pendingPortableRestoreScript = buildPortableRestoreScript(
                                        fingerprint = fingerprint,
                                        managedUsers = disabledPackages,
                                        snapshotUsers = restorePointManagedUserPackages,
                                        managedSystems = managedSystemDisabledPackages,
                                        snapshotSystems = restorePointManagedSystemPackages,
                                        managedAutoStart = disabledAutoStart,
                                        snapshotAutoStart = restorePointAutoStartComponents
                                    )
                                    portableRestoreScriptExporter.launch("tv-apk-installer-restore.sh")
                                } catch (e: Exception) {
                                    status = "Не удалось подготовить сценарий: ${errorText(e)}"
                                }
                            }
                        ) { Text("Экспортировать сценарий восстановления ADB") }
                        Text("Сценарий возвращает только изменения, отслеживаемые TV APK Installer, и проверяет модель и отпечаток прошивки перед командами. Для запуска на компьютере нужны Bash и Android platform-tools.", color = TextMuted, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !busy && restorePointExists && restorePointSchemaVersion >= 2 && !restorePointDeviceFingerprint.isNullOrBlank(),
                                modifier = Modifier.weight(1f),
                                onClick = { restorePointJsonExporter.launch("tv-apk-installer-restore-point.json") }
                            ) { Text("Экспорт точки JSON") }
                            OutlinedButton(
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onClick = { restorePointJsonImporter.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                            ) { Text("Импорт точки JSON") }
                        }
                        Text("JSON-файл переносит саму точку между установками приложения. Импорт не меняет телевизор; при запуске восстановления отпечаток модели и прошивки сверяется с подключённым устройством.", color = TextMuted, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !busy && restorePointExists && restorePointSchemaVersion >= 2 && !restorePointDeviceFingerprint.isNullOrBlank(),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    try {
                                        val fingerprint = restorePointDeviceFingerprint ?: throw Exception("Создай точку заново")
                                        val json = buildRestorePointBackupJson(
                                            fingerprint, restorePoint, restorePointManagedUserPackages,
                                            restorePointManagedSystemPackages, restorePointAutoStartComponents
                                        )
                                        val bash = buildPortableRestoreScript(
                                            fingerprint, disabledPackages, restorePointManagedUserPackages,
                                            managedSystemDisabledPackages, restorePointManagedSystemPackages,
                                            disabledAutoStart, restorePointAutoStartComponents
                                        )
                                        val powershell = buildPortableRestorePowerShell(
                                            fingerprint, disabledPackages, restorePointManagedUserPackages,
                                            managedSystemDisabledPackages, restorePointManagedSystemPackages,
                                            disabledAutoStart, restorePointAutoStartComponents
                                        )
                                        pendingPortableRestoreBundle = buildPortableRestoreBundleZip(json, bash, powershell)
                                        portableRestoreBundleExporter.launch("tv-apk-installer-restore-bundle.zip")
                                    } catch (e: Exception) {
                                        status = "Не удалось подготовить ZIP: ${errorText(e)}"
                                    }
                                }
                            ) { Text("Экспорт ZIP-пакета") }
                            OutlinedButton(
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onClick = { portableRestoreBundleImporter.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                            ) { Text("Импорт ZIP-пакета") }
                        }
                        Text("ZIP содержит манифест, контрольные суммы SHA-256, снимок и сценарии для Bash и PowerShell. Архив проверяется перед импортом; сценарии не запускаются автоматически.", color = TextMuted, fontSize = 11.sp)

                        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            val host = ip.trim(); val p = port.toIntOrNull()
                            if (host.isBlank() || p == null) status = "Сначала укажи IP и порт ADB"
                            else runTask({
                                connectWithRetry(context, host, p) { adb ->
                                    val details = readTvDetails(adb)
                                    val compatibility = readTvCompatibilityReport(adb)
                                    val packageDisable = readPackageDisableCapability(adb)
                                    val packages = readUserPackages(adb)
                                    val installed = readAllPackages(adb)
                                    val disabled = readDisabledPackages(adb)
                                    activity.runOnUiThread {
                                        tvDetails = details
                                        tvCompatibilityReport = compatibility
                                        packageDisableSupported = packageDisable
                                        userPackages = packages
                                        allPackages = installed
                                        disabledOnDevice = disabled
                                    }
                                    "Устройство определено. Пользовательских приложений: ${packages.size}; всего пакетов: ${installed.size}; отключено на устройстве: ${disabled.size}"
                                }
                            })
                        }) { Text("Определить устройство и найти приложения") }
                        if (tvCompatibilityReport.isNotBlank()) {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("СОВМЕСТИМОСТЬ УСТРОЙСТВА", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                                    Text(tvCompatibilityReport, color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                        OutlinedTextField(value = packageFilter, onValueChange = { packageFilter = it },
                            label = { Text("Поиск приложения по имени пакета") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                                val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                runTask({
                                    connectWithRetry(context, host, p) { adb ->
                                        val snapshot = readDisabledUserPackages(adb)
                                        val actualDisabled = readDisabledPackages(adb)
                                        val deviceFingerprint = readTvIdentity(adb)
                                        val managedUsers = disabledPackages.intersect(actualDisabled)
                                        val managedSystems = managedSystemDisabledPackages.intersect(actualDisabled)
                                        val managedAutoStart = disabledAutoStart.toSet()
                                        activity.runOnUiThread {
                                            restorePoint = snapshot
                                            restorePointManagedUserPackages = managedUsers
                                            restorePointManagedSystemPackages = managedSystems
                                            restorePointAutoStartComponents = managedAutoStart
                                            restorePointExists = true
                                            restorePointSchemaVersion = 2
                                            restorePointDeviceFingerprint = deviceFingerprint
                                            settings.edit()
                                                .putStringSet("restore_point_user_disabled", snapshot)
                                                .putStringSet("restore_point_managed_user_packages", managedUsers)
                                                .putStringSet("restore_point_managed_system_packages", managedSystems)
                                                .putStringSet("restore_point_autostart_components", managedAutoStart)
                                                .putString("restore_point_device_fingerprint", deviceFingerprint)
                                                .putInt("restore_point_schema_version", 2)
                                                .putBoolean("restore_point_exists", true).apply()
                                        }
                                        "Точка создана. Отключено пользовательских: ${snapshot.size}; под управлением приложения: ${managedUsers.size + managedSystems.size}; автозапуск: ${managedAutoStart.size}"
                                    }
                                })
                            }) { Text("Создать точку") }
                            OutlinedButton(enabled = !busy && restorePointExists, modifier = Modifier.weight(1f), onClick = {
                                val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                val snapshot = restorePoint
                                runTask({
                                    connectWithRetry(context, host, p) { adb ->
                                        require(restorePointSchemaVersion >= 2) {
                                            "Эта точка создана старой версией приложения и не содержит полного списка изменений. Создай новую точку перед восстановлением."
                                        }
                                        val savedFingerprint = restorePointDeviceFingerprint
                                            ?: throw Exception("Эта старая точка не привязана к конкретному телевизору. Создай новую точку перед восстановлением.")
                                        val currentFingerprint = readTvIdentity(adb)
                                        require(currentFingerprint == savedFingerprint) {
                                            "Точка создана для другого телевизора или другой сборки прошивки. Восстановление остановлено; создай новую точку на этом устройстве."
                                        }
                                        val currentUserDisabled = readDisabledUserPackages(adb)
                                        val currentDisabledAll = readDisabledPackages(adb)
                                        val installed = readAllPackages(adb).toSet()
                                        val currentManagedUsers = disabledPackages.toSet()
                                        val currentManagedSystems = managedSystemDisabledPackages.toSet()
                                        val currentManagedAutoStart = disabledAutoStart.toSet()
                                        val failures = mutableListOf<String>()
                                        (currentUserDisabled - snapshot).forEach { pkg ->
                                            try { changePackageStateVerified(adb, pkg, true) } catch (_: Exception) { failures.add(pkg) }
                                        }
                                        (snapshot - currentUserDisabled).forEach { pkg ->
                                            if (pkg in installed) try { changePackageStateVerified(adb, pkg, false) } catch (_: Exception) { failures.add(pkg) }
                                            else failures.add("${pkg} (не установлен)")
                                        }
                                        val managedUserUniverse = currentManagedUsers + restorePointManagedUserPackages
                                        managedUserUniverse.forEach { pkg ->
                                            val shouldBeDisabled = pkg in restorePointManagedUserPackages
                                            val isDisabledNow = pkg in currentUserDisabled
                                            if (pkg !in installed && shouldBeDisabled) failures.add("${pkg} (не установлен)")
                                            else if (pkg in installed && shouldBeDisabled && !isDisabledNow) {
                                                try { changePackageStateVerified(adb, pkg, false) } catch (_: Exception) { failures.add(pkg) }
                                            } else if (pkg in installed && !shouldBeDisabled && isDisabledNow) {
                                                try { changePackageStateVerified(adb, pkg, true) } catch (_: Exception) { failures.add(pkg) }
                                            }
                                        }
                                        val managedSystemUniverse = currentManagedSystems + restorePointManagedSystemPackages
                                        managedSystemUniverse.forEach { pkg ->
                                            val shouldBeDisabled = pkg in restorePointManagedSystemPackages
                                            val isDisabledNow = pkg in currentDisabledAll
                                            if (pkg !in installed && shouldBeDisabled) failures.add("${pkg} (не установлен)")
                                            else if (isProtectedSystemPackage(pkg)) failures.add("${pkg} (защищённый компонент, пропущен)")
                                            else if (pkg in installed && shouldBeDisabled && !isDisabledNow) {
                                                try { changePackageStateVerified(adb, pkg, false) } catch (_: Exception) { failures.add(pkg) }
                                            } else if (pkg in installed && !shouldBeDisabled && isDisabledNow) {
                                                try { changePackageStateVerified(adb, pkg, true) } catch (_: Exception) { failures.add(pkg) }
                                            }
                                        }
                                        (currentManagedAutoStart - restorePointAutoStartComponents).forEach { component ->
                                            if (component.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+"))) try { setAutoStartReceiverEnabled(adb, component, true) } catch (_: Exception) { failures.add(component) }
                                        }
                                        (restorePointAutoStartComponents - currentManagedAutoStart).forEach { component ->
                                            if (component.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+"))) try { setAutoStartReceiverEnabled(adb, component, false) } catch (_: Exception) { failures.add(component) }
                                        }
                                        
                                        // Verify the resulting package state, not just shell command completion.
                                        val verifiedUserDisabled = readDisabledUserPackages(adb)
                                        val expectedUserDisabled = snapshot.intersect(installed)
                                        (expectedUserDisabled - verifiedUserDisabled).forEach { failures.add("$it (не удалось подтвердить отключение)") }
                                        (verifiedUserDisabled - expectedUserDisabled).forEach { failures.add("$it (остался отключён)") }
                                        val verifiedAllDisabled = readDisabledPackages(adb)
                                        managedSystemUniverse.forEach { pkg ->
                                            val shouldBeDisabled = pkg in restorePointManagedSystemPackages
                                            if ((pkg in verifiedAllDisabled) != shouldBeDisabled) {
                                                failures.add("$pkg (состояние системного пакета не подтверждено)")
                                            }
                                        }
val actualAfterRestore = readDisabledPackages(adb)
                                        activity.runOnUiThread {
                                            disabledOnDevice = actualAfterRestore
                                            if (failures.isEmpty()) {
                                                disabledPackages = restorePointManagedUserPackages
                                                managedSystemDisabledPackages = restorePointManagedSystemPackages
                                                disabledAutoStart = restorePointAutoStartComponents
                                                settings.edit()
                                                    .putStringSet("disabled_packages", disabledPackages)
                                                    .putStringSet("managed_system_disabled_packages", managedSystemDisabledPackages)
                                                    .putStringSet("disabled_autostart_components", disabledAutoStart)
                                                    .apply()
                                            }
                                        }
                                        if (failures.isEmpty()) "Состояние приложений и автозапуска восстановлено по точке"
                                        else "Восстановление выполнено частично. Можно повторить; не удалось: ${failures.joinToString()}"
                                    }
                                })
                            }) { Text("Восстановить точку") }
                        }
                        Text(
                            if (restorePointExists) "Точка сохранена: отключённых пользовательских приложений — ${restorePoint.size}; изменения TV APK Installer — ${restorePointManagedUserPackages.size + restorePointManagedSystemPackages.size}; автозапуск — ${restorePointAutoStartComponents.size}"
                            else "Точка восстановления ещё не создана",
                            color = Color.LightGray, fontSize = 11.sp
                        )

                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                                backupSaver.launch("TV-backup-$stamp.zip")
                            }
                        ) {
                            Text(if (busy) "Создание копии..." else "Создать резервную копию APK")
                        }

                        OutlinedButton(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { backupRestorePicker.launch(arrayOf("application/zip", "application/octet-stream")) }
                        ) { Text("Восстановить приложения из ZIP") }
                        Text(
                            "В ZIP сохраняются APK пользовательских приложений (в том числе split APK), список пакетов и сведения об устройстве. Данные внутри приложений и системные настройки без root не копируются.",
                            color = Color(0xFFFFD98A), fontSize = 11.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Локальные копии перед удалением", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { localUndoBackups = listUndoBackups(context) }) { Text("Обновить") }
                        }
                        if (localUndoBackups.isEmpty()) {
                            Text("Локальных копий пока нет. При удалении пользовательского приложения APK автоматически сохраняются здесь.", color = TextMuted, fontSize = 11.sp)
                        } else {
                            Text("Сохранено копий: " + localUndoBackups.size, color = TextMuted, fontSize = 11.sp)
                            localUndoBackups.forEach { backup ->
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(undoBackupPackage(backup), color = TextMain, fontSize = 12.sp)
                                    Text(undoBackupDate(backup) + " • " + (backup.length() / 1024) + " КБ", color = TextMuted, fontSize = 10.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { pendingUndoBackupRestore = backup }) { Text("Восстановить") }
                                        OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                                            pendingUndoBackupExport = backup
                                            undoBackupExporter.launch(backup.name)
                                        }) { Text("Экспорт") }
                                        OutlinedButton(enabled = !busy, onClick = { pendingUndoBackupDelete = backup }) { Text("Удалить") }
                                    }
                                }
                                HorizontalDivider(color = Color.DarkGray)
                            }
                        }
                        Text("Пользовательские приложения: ${userPackages.size}", color = GoldLight)
                        if (userPackages.isEmpty()) Text("Сначала выполни определение устройства. Системные пакеты здесь не показываются.", color = Color.LightGray, fontSize = 12.sp)
                        userPackages.filter { it.contains(packageFilter, ignoreCase = true) }.forEach { pkg ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(pkg, fontSize = 12.sp, color = TextMain)
                                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                    val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                    runTask({ connectWithRetry(context, host, p) { adb ->
                                        launchTvApp(adb, pkg)
                                        "Запущено приложение: $pkg"
                                    } })
                                }) { Text("Запустить приложение") }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                                        val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                        runTask({ connectWithRetry(context, host, p) { adb ->
                                            openTvAppInfo(adb, pkg)
                                            "Открыта информация о приложении: $pkg"
                                        } })
                                    }) { Text("Информация") }
                                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                                        pendingAppUninstall = pkg
                                    }) { Text("Удалить") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = !busy && pkg !in disabledPackages, onClick = {
                                        val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                        runTask({ connectWithRetry(context, host, p) { adb -> changePackageState(adb, pkg, false) } }, { result ->
                                            if (!result.startsWith("Ошибка")) {
                                                disabledPackages = disabledPackages + pkg
                                                settings.edit().putStringSet("disabled_packages", disabledPackages).apply()
                                            }
                                        })
                                    }) { Text("Отключить") }
                                    OutlinedButton(enabled = !busy && pkg in disabledPackages, onClick = {
                                        val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                        runTask({ connectWithRetry(context, host, p) { adb -> changePackageState(adb, pkg, true) } }, { result ->
                                            if (!result.startsWith("Ошибка")) {
                                                disabledPackages = disabledPackages - pkg
                                                settings.edit().putStringSet("disabled_packages", disabledPackages).apply()
                                            }
                                        })
                                    }) { Text("Восстановить") }
                                }
                            }
                            HorizontalDivider(color = Color.DarkGray)
                        }
                        if (pendingUndoBackupRestore != null) {
                            AlertDialog(
                                onDismissRequest = { pendingUndoBackupRestore = null },
                                title = { Text("Восстановить приложение?") },
                                text = { Text("Будет установлена копия APK приложения " + (pendingUndoBackupRestore?.let { undoBackupPackage(it) } ?: "") + ". Данные приложения не восстанавливаются. Архив останется сохранённым.") },
                                confirmButton = {
                                    TextButton(enabled = !busy, onClick = {
                                        val backup = pendingUndoBackupRestore ?: return@TextButton
                                        pendingUndoBackupRestore = null
                                        val host = ip.trim()
                                        val p = port.toIntOrNull() ?: 5555
                                        if (host.isBlank() || p !in 1..65535) status = "Сначала укажи IP и порт ADB"
                                        else runTask({
                                            connectWithRetry(context, host, p) { adb -> restoreTvBackup(adb, context, backup) }
                                        })
                                    }) { Text("Восстановить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingUndoBackupRestore = null }) { Text("Отмена") }
                                }
                            )
                        }
                        if (pendingUndoBackupDelete != null) {
                            AlertDialog(
                                onDismissRequest = { pendingUndoBackupDelete = null },
                                title = { Text("Удалить резервную копию?") },
                                text = { Text("Архив " + (pendingUndoBackupDelete?.name ?: "") + " будет удалён без возможности восстановления.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val backup = pendingUndoBackupDelete ?: return@TextButton
                                        pendingUndoBackupDelete = null
                                        if (backup.parentFile?.canonicalFile == File(context.filesDir, "backups").canonicalFile && backup.delete()) {
                                            localUndoBackups = listUndoBackups(context)
                                            status = "Резервная копия удалена"
                                        } else status = "Ошибка: не удалось удалить резервную копию"
                                    }) { Text("Удалить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingUndoBackupDelete = null }) { Text("Отмена") }
                                }
                            )
                        }
                        if (pendingRestoreUri != null) {
                            AlertDialog(
                                onDismissRequest = { pendingRestoreUri = null },
                                title = { Text("Восстановить приложения?") },
                                text = { Text("Будут установлены APK из выбранной резервной копии. Приложения могут обновиться или заменить текущие версии. Данные приложений и системные настройки в архив не входят.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val uri = pendingRestoreUri ?: return@TextButton
                                        pendingRestoreUri = null
                                        val host = ip.trim()
                                        val p = port.toIntOrNull()
                                        if (host.isBlank() || p == null || p !in 1..65535) {
                                            status = "Сначала укажи IP и порт ADB"
                                        } else {
                                            runTask({
                                                val archive = File(context.cacheDir, "tv-backup-restore.zip")
                                                context.contentResolver.openInputStream(uri)?.use { input ->
                                                    archive.outputStream().use { output -> input.copyTo(output) }
                                                } ?: throw Exception("Не удалось прочитать ZIP-архив")
                                                try {
                                                    connectWithRetry(context, host, p) { adb -> restoreTvBackup(adb, context, archive) }
                                                } finally {
                                                    archive.delete()
                                                }
                                            }, { result ->
                                                if (!result.startsWith("Ошибка")) {
                                                    userPackages = emptyList()
                                                    allPackages = emptyList()
                                                    status = result + ". Повтори определение устройства для обновления списка."
                                                }
                                            })
                                        }
                                    }) { Text("Восстановить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingRestoreUri = null }) { Text("Отмена") }
                                }
                            )
                        }
                        if (pendingAppUninstall != null) {
                            AlertDialog(
                                onDismissRequest = { pendingAppUninstall = null },
                                title = { Text("Удалить приложение?") },
                                text = { Text("Будет удалено пользовательское приложение: $pendingAppUninstall. Системные приложения этим действием не затрагиваются. Продолжить?") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val pkg = pendingAppUninstall ?: return@TextButton
                                        pendingAppUninstall = null
                                        val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                        runTask({ connectWithRetry(context, host, p) { adb ->
                                            uninstallUserApp(adb, pkg, context)
                                            "Приложение удалено: $pkg"
                                        } }, { result ->
                                            if (!result.startsWith("Ошибка")) {
                                                userPackages = userPackages - pkg
                                                allPackages = allPackages - pkg
                                                disabledPackages = disabledPackages - pkg
                                                settings.edit().putStringSet("disabled_packages", disabledPackages).apply()
                                            }
                                        })
                                    }) { Text("Удалить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingAppUninstall = null }) { Text("Отмена") }
                                }
                            )
                        }
                        Text("ЦЕНТР УПРАВЛЕНИЯ ПРИЛОЖЕНИЯМИ", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                        Text("Найдено пользовательских приложений: ${userPackages.size}. Доступны запуск, информация, отключение, восстановление и удаление с подтверждением.", color = TextMuted, fontSize = 11.sp)
                        Text("Системные приложения: ${(allPackages - userPackages.toSet()).size}", color = GoldLight)
                        Text("Осторожно: отключение системных компонентов может нарушить запуск телевизора, звук, сеть или обновления. Защищённые компоненты отключить нельзя.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                        OutlinedTextField(
                            value = systemPackageFilter,
                            onValueChange = { systemPackageFilter = it },
                            label = { Text("Поиск системного пакета") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        (allPackages - userPackages.toSet())
                            .filter { it.contains(systemPackageFilter, ignoreCase = true) }
                            .take(150)
                            .forEach { pkg ->
                                val protectedByProfile = pkg in (matchedTvProfile?.profile?.neverDisable ?: emptySet())
                                val protected = isProtectedSystemPackage(pkg) || protectedByProfile
                                val isDisabled = pkg in disabledOnDevice
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(pkg, fontSize = 12.sp, color = TextMain)
                                    if (protected) {
                                        Text(if (protectedByProfile && !isProtectedSystemPackage(pkg)) "Защищённый компонент профиля этой модели" else "Критически важный системный компонент", color = GoldLight, fontSize = 11.sp)
                                    } else {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (isDisabled) {
                                                OutlinedButton(enabled = !busy, onClick = {
                                                    val host = ip.trim()
                                                    val p = port.toIntOrNull() ?: 5555
                                                    runTask({
                                                        connectWithRetry(context, host, p) { adb ->
                                                            changePackageState(adb, pkg, true)
                                                            "Системный пакет восстановлен: $pkg"
                                                        }
                                                    }, { result ->
                                                        if (!result.startsWith("Ошибка")) {
                                                    disabledOnDevice = disabledOnDevice - pkg
                                                    managedSystemDisabledPackages = managedSystemDisabledPackages - pkg
                                                    settings.edit().putStringSet("managed_system_disabled_packages", managedSystemDisabledPackages).apply()
                                                }
                                                    })
                                                }) { Text("Восстановить") }
                                            } else {
                                                OutlinedButton(enabled = !busy, onClick = {
                                                    pendingSystemDisable = pkg
                                                }) { Text("Отключить") }
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = Color.DarkGray)
                            }
                        if ((allPackages - userPackages.toSet()).size > 150) {
                            Text("Показаны первые 150 совпадений. Уточни поиск, чтобы найти остальные пакеты.", color = Color.LightGray, fontSize = 11.sp)
                        }
                        if (pendingSystemDisable != null) {
                            AlertDialog(
                                onDismissRequest = { pendingSystemDisable = null },
                                title = { Text("Отключить системное приложение?") },
                                text = {
                                    Text("Пакет: ${pendingSystemDisable}\n\nОтключение может нарушить работу телевизора. Продолжай только если точно знаешь назначение пакета.")
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val pkg = pendingSystemDisable ?: return@TextButton
                                        pendingSystemDisable = null
                                        val host = ip.trim()
                                        val p = port.toIntOrNull() ?: 5555
                                        runTask({
                                            connectWithRetry(context, host, p) { adb ->
                                                require(!isProtectedSystemPackage(pkg)) {
                                                    "Этот пакет относится к критически важным компонентам Android/TV и не может быть отключён."
                                                }
                                                val freshDetails = readTvDetails(adb)
                                                val freshProfile = matchTvModelProfile(freshDetails, tvModelProfiles)?.profile
                                                require(pkg !in (freshProfile?.neverDisable ?: emptySet())) {
                                                    "Профиль этой модели запрещает отключать $pkg. Команда отменена."
                                                }
                                                changePackageState(adb, pkg, false)
                                                "Системный пакет отключён: $pkg"
                                            }
                                        }, { result ->
                                            if (!result.startsWith("Ошибка")) {
                                                disabledOnDevice = disabledOnDevice + pkg
                                                managedSystemDisabledPackages = managedSystemDisabledPackages + pkg
                                                settings.edit().putStringSet("managed_system_disabled_packages", managedSystemDisabledPackages).apply()
                                            }
                                        })
                                    }) { Text("Отключить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingSystemDisable = null }) { Text("Отмена") }
                                }
                            )
                        }
                        if (managedSystemDisabledPackages.isNotEmpty() || disabledAutoStart.isNotEmpty()) {
                            Text("Изменения системных компонентов под управлением приложения: системных пакетов — ${managedSystemDisabledPackages.size}; автозапуск — ${disabledAutoStart.size}", color = GoldLight, fontSize = 11.sp)
                            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                pendingRestoreManagedChanges = true
                            }) { Text("Восстановить системные компоненты и автозапуск") }
                        }
                        if (pendingRestoreManagedChanges) {
                            AlertDialog(
                                onDismissRequest = { pendingRestoreManagedChanges = false },
                                title = { Text("Восстановить системные изменения?") },
                                text = { Text("Будут включены только системные пакеты и компоненты автозапуска, которые отключало это приложение. Защищённые компоненты не будут отключаться. Продолжить?") },
                                confirmButton = {
                                    TextButton(enabled = !busy, onClick = {
                                        pendingRestoreManagedChanges = false
                                        val systems = managedSystemDisabledPackages.toSet()
                                        val components = disabledAutoStart.toSet()
                                        val host = ip.trim()
                                        val p = port.toIntOrNull() ?: 5555
                                        if (host.isBlank() || p !in 1..65535) {
                                            status = "Сначала укажи IP и порт ADB"
                                        } else runTask({
                                            connectWithRetry(context, host, p) { adb ->
                                                val installed = readAllPackages(adb).toSet()
                                                val restoredSystems = mutableSetOf<String>()
                                                val restoredComponents = mutableSetOf<String>()
                                                val failures = mutableListOf<String>()
                                                systems.forEach { pkg ->
                                                    if (pkg !in installed) failures.add("$pkg (не установлен)")
                                                    else if (isProtectedSystemPackage(pkg)) failures.add("$pkg (защищённый компонент)")
                                                    else try {
                                                        changePackageState(adb, pkg, true)
                                                        restoredSystems.add(pkg)
                                                    } catch (_: Exception) { failures.add(pkg) }
                                                }
                                                components.forEach { component ->
                                                    if (component.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+"))) try {
                                                        setAutoStartReceiverEnabled(adb, component, true)
                                                        restoredComponents.add(component)
                                                    } catch (_: Exception) { failures.add(component) }
                                                    else failures.add("$component (неверный идентификатор)")
                                                }
                                                val actualDisabledAfterRestore = readDisabledPackages(adb)
                                                activity.runOnUiThread {
                                                    managedSystemDisabledPackages = managedSystemDisabledPackages - restoredSystems
                                                    disabledAutoStart = disabledAutoStart - restoredComponents
                                                    disabledOnDevice = actualDisabledAfterRestore
                                                    settings.edit()
                                                        .putStringSet("managed_system_disabled_packages", managedSystemDisabledPackages)
                                                        .putStringSet("disabled_autostart_components", disabledAutoStart)
                                                        .apply()
                                                }
                                                if (failures.isEmpty()) "Системные компоненты и автозапуск восстановлены"
                                                else "Восстановлено частично. Осталось повторить: ${failures.joinToString()}"
                                            }
                                        })
                                    }) { Text("Восстановить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingRestoreManagedChanges = false }) { Text("Отмена") }
                                }
                            )
                        }
                        if (disabledPackages.isNotEmpty()) {
                            Text("Отключено этим приложением: ${disabledPackages.size}", color = GoldLight)
                            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                val restoreList = disabledPackages.toList()
                                runTask({
                                    connectWithRetry(context, host, p) { adb ->
                                        val failed = mutableListOf<String>()
                                        restoreList.forEach { pkg -> try { changePackageState(adb, pkg, true) } catch (_: Exception) { failed.add(pkg) } }
                                        if (failed.isEmpty()) "Все приложения восстановлены" else "Не удалось восстановить: ${failed.joinToString()}"
                                    }
                                }, { result ->
                                    if (result == "Все приложения восстановлены") {
                                        disabledPackages = emptySet()
                                        settings.edit().remove("disabled_packages").apply()
                                    }
                                })
                            }) { Text("Восстановить все отключённые") }
                        }
                        Text("Показываются только пользовательские приложения. Системные службы и лаунчер не затрагиваются. Отключение приложения может нарушить его работу.", color = Color.LightGray, fontSize = 11.sp)
                    }
                }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Android,
                                contentDescription = null,
                                tint = Accent
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Установка APK",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Text(
                            selectedName,
                            color = Color.LightGray
                        )

                        OutlinedButton(
                            onClick = {
                                picker.launch(
                                    "application/vnd.android.package-archive"
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Выбрать APK")
                        }

                        Button(
                            enabled = !busy && selectedUri != null,
                            onClick = {
                                val uri = selectedUri
                                val host = ip.trim()
                                val p = port.toIntOrNull()

                                if (uri == null) {
                                    status = "Сначала выбери APK"
                                } else if (
                                    host.isBlank() || p == null ||
                                    p !in 1..65535
                                ) {
                                    status = "Проверь IP и порт телевизора"
                                } else {
                                    runTask(
                                        action = {
                                            val apkFile = File(
                                                context.cacheDir,
                                                "selected_app.apk"
                                            )

                                            context.contentResolver
                                                .openInputStream(uri)
                                                ?.use { input ->
                                                    apkFile.outputStream().use {
                                                        output ->
                                                        input.copyTo(output)
                                                    }
                                                }
                                                ?: throw Exception(
                                                    "Не удалось прочитать APK"
                                                )

                                            connectWithRetry(
                                                context,
                                                host,
                                                p
                                            ) { adb ->
                                                adb.install(apkFile)
                                                "APK успешно установлен"
                                            }
                                        },
                                        onFinished = { result ->
                                            if (result == "APK успешно установлен") {
                                                val entry =
                                                    "$selectedName — $host:$p"

                                                history.add(0, entry)
                                                while (history.size > 30) {
                                                    history.removeAt(
                                                        history.lastIndex
                                                    )
                                                }
                                                saveHistory(context, history)
                                            }
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (busy) "Выполняется..." else "Установить APK"
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "История установок",
                            style = MaterialTheme.typography.titleMedium
                        )

                        if (history.isEmpty()) {
                            Text(
                                "История пока пуста",
                                color = Color.LightGray
                            )
                        }

                        history.forEach {
                            Text(it, color = Color.LightGray, fontSize = 13.sp)
                            HorizontalDivider(color = Color.DarkGray)
                        }

                        if (history.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    history.clear()
                                    saveHistory(context, history)
                                }
                            ) {
                                Text("Очистить историю")
                            }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Статус",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            status,
                            color = if (status.startsWith("Ошибка")) {
                                Color(0xFFFF7777)
                            } else {
                                Color.LightGray
                            }
                        )
                    }
                }

                Text(
                    "Важно: установка APK поддерживается на Android TV " +
                        "и Android-приставках. Hisense VIDAA не является " +
                        "Android TV и не поддерживает установку Android APK.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )

                Text(
                    "TV APK Installer • $APP_VERSION",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
        }
        }
    }
}
