
package ru.tvapk.installer

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri as AndroidUri
import android.provider.Settings
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.net.HttpURLConnection
import java.net.URL

private const val APP_VERSION = "0.7.1"
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
    val apkSize: Long
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

        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name")
        val releaseUrl = json.optString("html_url").takeIf { it.isNotBlank() }
        if (tag.isBlank()) return UpdateInfo("Версия релиза не найдена", releaseUrl, null, 0L)

        if (compareVersions(tag, APP_VERSION) <= 0) {
            return UpdateInfo("Установлена актуальная версия $APP_VERSION", releaseUrl, null, 0L)
        }

        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                apkSize = asset.optLong("size", 0L)
                if (apkUrl != null) break
            }
        }

        return if (apkUrl != null) {
            UpdateInfo("Доступно обновление: $tag", releaseUrl, apkUrl, apkSize)
        } else {
            UpdateInfo("Доступно обновление $tag, но APK ещё не прикреплён к релизу", releaseUrl, null, 0L)
        }
    } finally {
        connection.disconnect()
    }
}

private fun downloadUpdateApk(context: Context, downloadUrl: String): File {
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

    val config = JSONObject()
        .put("background_color", "#080808")
        .put("wallpaper", false)
        .put("wallpaper_rotation_minutes", 0)
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
        "o=\$(content write --uri content://$packageName.theme/config.json < /data/local/tmp/tvapk-launcher-config.json 2>&1); " +
        "case \"\$o\" in *Unsupported*|*Error*) mkdir -p $directory 2>/dev/null; cp /data/local/tmp/tvapk-launcher-config.json $directory/config.json;; esac; " +
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
    require(themeName in setOf("arcade", "cinema", "console", "horizon", "library", "mist", "quiet")) {
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
    val wallpaper = download("wallpaper.jpg", 12 * 1024 * 1024L)
    return config to wallpaper
}

private fun pushLauncherTheme(adb: Dadb, themeName: String, configFile: File, wallpaperFile: File): String {
    val packageName = "com.gathin.tvhome"
    require(shellText(adb, "pm path $packageName").contains("package:")) {
        "Clean Your TV Launcher не установлен. Сначала установи модуль в разделе «Модули Clean Your TV»."
    }
    val remoteConfig = "/data/local/tmp/tvapk-$themeName-config.json"
    val remoteWallpaper = "/data/local/tmp/tvapk-$themeName-wallpaper.jpg"
    val remoteRevision = "/data/local/tmp/tvapk-$themeName-revision"
    fun pushFile(local: File, remote: String) {
        when (val result = adb.push(local, remote)) {
            is dadb.SyncResult.Success -> Unit
            is dadb.SyncResult.Failure -> throw Exception("Не удалось передать файл на телевизор: $result")
        }
    }
    pushFile(configFile, remoteConfig)
    pushFile(wallpaperFile, remoteWallpaper)
    val revisionFile = File(configFile.parentFile, "cyt-theme-revision.txt").apply {
        writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
    }
    pushFile(revisionFile, remoteRevision)

    val directory = "/sdcard/Android/data/$packageName/files"
    fun writeFile(fileName: String, remotePath: String) {
        val command =
            "rm -f $directory/$fileName 2>/dev/null; " +
            "o=\$(content write --uri content://$packageName.theme/$fileName < $remotePath 2>&1); " +
            "case \"\$o\" in *Unsupported*|*Error*) mkdir -p $directory 2>/dev/null; cp $remotePath $directory/$fileName;; esac; " +
            "rm -f $remotePath"
        shellText(adb, command)
    }
    writeFile("config.json", remoteConfig)
    writeFile("wallpaper.jpg", remoteWallpaper)
    writeFile("theme_revision", remoteRevision)
    shellText(adb, "am force-stop --user 0 $packageName")
    val restart = shellText(adb, "am start --user 0 -n $packageName/.MainActivity")
    return "Тема «$themeName» передана на телевизор. Конфигурация и обои установлены, Launcher перезапущен. $restart"
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

private fun sendTvKey(adb: Dadb, keyCode: Int): String {
    require(keyCode in 0..300) { "Недопустимый код клавиши" }
    return shellText(adb, "input keyevent $keyCode")
}

private fun openTvAppInfo(adb: Dadb, packageName: String): String {
    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Некорректное имя пакета" }
    return shellText(adb, "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName")
}

private fun uninstallUserApp(adb: Dadb, packageName: String): String {
    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Некорректное имя пакета" }
    require(packageName in readUserPackages(adb)) { "Разрешено удалять только пользовательские приложения" }
    return shellText(adb, "pm uninstall --user 0 $packageName")
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
        addText("applications.txt", packages.joinToString("\\n"))
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
    val restoreDir = File(context.cacheDir, "tv-restore-${System.currentTimeMillis()}")
    if (restoreDir.exists()) restoreDir.deleteRecursively()
    if (!restoreDir.mkdirs()) throw Exception("Не удалось создать временную папку восстановления")
    val grouped = linkedMapOf<String, MutableList<File>>()
    try {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val parts = entry.name.split("/")
                if (!entry.isDirectory && parts.size == 3 && parts[0] == "apks" &&
                    parts[2].endsWith(".apk", ignoreCase = true) &&
                    parts[1].matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
                ) {
                    val packageName = parts[1]
                    val target = File(restoreDir, "${packageName.replace('.', '_')}_${grouped[packageName]?.size ?: 0}.apk")
                    target.outputStream().use { output -> zip.copyTo(output) }
                    if (target.length() > 0L) grouped.getOrPut(packageName) { mutableListOf() }.add(target)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (grouped.isEmpty()) throw Exception("В архиве не найдены APK из резервной копии TV APK Installer")
        var installed = 0
        val failures = mutableListOf<String>()
        grouped.forEach { (packageName, files) ->
            try {
                val result = if (files.size > 1) adb.installMultiple(files) else adb.install(files.first())
                when (result) {
                    is InstallResult.Success -> installed++
                    is InstallResult.Failure -> failures.add("$packageName: ${result.reason}")
                }
            } catch (e: Exception) {
                failures.add("$packageName: ${e.message ?: "ошибка установки"}")
            }
        }
        return "Восстановлено приложений: $installed из ${grouped.size}. Ошибок: ${failures.size}" +
            if (failures.isEmpty()) "" else "\n" + failures.take(10).joinToString("\n")
    } finally {
        restoreDir.deleteRecursively()
    }
}

private fun isProtectedSystemPackage(packageName: String): Boolean {
    val protected = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.settings",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.android.providers.media",
        "com.android.providers.downloads",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gsf.login",
        "com.google.android.webview",
        "com.google.android.tvlauncher",
        "com.google.android.tv",
        "com.android.tv.settings",
        "com.android.vending"
    )
    return packageName in protected ||
        packageName.startsWith("com.android.systemui.") ||
        packageName.startsWith("com.google.android.gms.")
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
    "ads" -> "Реклама"
    "tracking" -> "Слежение и аналитика"
    "demo" -> "Демо-компонент"
    "preloads" -> "Предустановленное приложение"
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
    var disabledPackages by remember { mutableStateOf(settings.getStringSet("disabled_packages", emptySet())?.toSet() ?: emptySet()) }
    var packageFilter by remember { mutableStateOf("") }
    var restorePoint by remember { mutableStateOf(settings.getStringSet("restore_point_user_disabled", emptySet())?.toSet() ?: emptySet()) }
    var restorePointExists by remember { mutableStateOf(settings.getBoolean("restore_point_exists", false)) }


    var usbMode by remember { mutableStateOf(false) }
    var showCleanYourTv by remember { mutableStateOf(false) }
    var showRemoteControl by remember { mutableStateOf(false) }
    var pendingAppUninstall by remember { mutableStateOf<String?>(null) }
    var pendingModuleRemoval by remember { mutableStateOf(false) }
    var launcherColumns by remember { mutableStateOf(settings.getInt("launcher_columns", 6)) }
    var launcherRowsBelowDock by remember { mutableStateOf(settings.getInt("launcher_rows_below_dock", 1)) }
    var launcherDockTitle by remember { mutableStateOf(settings.getBoolean("launcher_dock_title", true)) }
    var launcherDockRow by remember { mutableStateOf(settings.getBoolean("launcher_dock_row", true)) }
    var launcherAnimations by remember { mutableStateOf(settings.getBoolean("launcher_animations", true)) }
    var launcherAdvancedEffects by remember { mutableStateOf(settings.getBoolean("launcher_advanced_effects", false)) }
    var launcherAppLibrary by remember { mutableStateOf(settings.getBoolean("launcher_app_library", false)) }
    var launcherIconOnlyButtons by remember { mutableStateOf(settings.getBoolean("launcher_icon_only_buttons", true)) }
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
                status = "Ошибка экспорта настроек: \${errorText(e)}"
            }
        }
    }

    val settingsBackupImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            try {
                val json = context.contentResolver.openInputStream(sourceUri)?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("Не удалось прочитать файл")
                importAppSettings(context, json)
                status = "Настройки восстановлены. Перезапускаю приложение..."
                activity.recreate()
            } catch (e: Exception) {
                status = "Ошибка восстановления: \${errorText(e)}"
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


    val cleanYourTvModulePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null) {
            val host = ip.trim()
            val p = port.toIntOrNull()
            if (host.isBlank() || p == null || p !in 1..65535) {
                status = "Сначала укажи IP и порт ADB и подключись к телевизору"
            } else {
                runTask({
                    val sourceApk = File(context.cacheDir, "cleanyourtv-source.apk")
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        sourceApk.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("Не удалось прочитать исходный APK")
                    fun extractBundled(entryName: String, outputName: String): File {
                        val outputFile = File(context.cacheDir, outputName)
                        var extracted = false
                        java.util.zip.ZipInputStream(sourceApk.inputStream()).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                if (entry.name == entryName) {
                                    outputFile.outputStream().use { output ->
                                        val buffer = ByteArray(8192)
                                        var total = 0
                                        while (true) {
                                            val count = zip.read(buffer)
                                            if (count <= 0) break
                                            total += count
                                            if (total > 8 * 1024 * 1024) throw Exception("Модуль слишком большой")
                                            output.write(buffer, 0, count)
                                        }
                                    }
                                    extracted = true
                                    break
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                        if (!extracted || !outputFile.exists() || outputFile.length() < 1024) {
                            throw Exception("В APK не найден модуль: $entryName")
                        }
                        return outputFile
                    }
                    val launcherApk = extractBundled("assets/bundled/cleanyourtv-launcher.apk", "cleanyourtv-launcher.apk")
                    val curtainApk = extractBundled("assets/bundled/cleanyourtv-curtain.apk", "cleanyourtv-curtain.apk")
                    connectWithRetry(context, host, p) { adb ->
                        adb.install(launcherApk)
                        adb.install(curtainApk)
                        val launcher = shellText(adb, "pm path com.gathin.tvhome")
                        val curtain = shellText(adb, "pm path tv.cleanyour.curtain")
                        if (!launcher.contains("package:") || !curtain.contains("package:")) {
                            throw Exception("Проверка установки не пройдена. Launcher: $launcher; Curtain: $curtain")
                        }
                        "Модули установлены и проверены: Launcher и Curtain. Штатный экран телевизора не отключался."
                    }
                })
            }
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
                                    runTask(
                                        action = { downloadUpdateApk(context, url).absolutePath },
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
                            Text("USB OTG", color = GoldLight, style = MaterialTheme.typography.titleMedium)
                            Text("Проверка USB-устройств, подключённых непосредственно к телефону или планшету. Подключение к телевизору по IP для этой функции не требуется.", color = TextMuted, fontSize = 12.sp)
                            OutlinedButton(onClick = {
                                val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                                val found = manager.deviceList.values
                                status = if (found.isEmpty()) "USB-устройства не обнаружены" else "Найдены USB-устройства: " + found.joinToString { "${it.vendorId}:${it.productId}" } + ". USB ADB пока не реализован."
                            }, modifier = Modifier.fillMaxWidth()) { Text("Найти USB-устройства") }
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
                            Text("Выбери исходный APK Clean Your TV 0.4.8. TV APK Installer извлечёт из него оригинальные модули Launcher и Curtain и установит их на подключённый Android TV.", color = TextMuted, fontSize = 12.sp)
                            Button(enabled = !busy, onClick = { cleanYourTvModulePicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (busy) "Установка модулей..." else "Выбрать APK Clean Your TV и установить модули")
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
                            OutlinedButton(enabled = !busy, onClick = {
                                val host = ip.trim(); val p = port.toIntOrNull()
                                if (host.isBlank() || p == null || p !in 1..65535) status = "Укажи IP и порт ADB"
                                else runTask({ connectWithRetry(context, host, p) { adb ->
                                    shellText(adb, "am start -a android.settings.HOME_SETTINGS")
                                    "Открыты системные настройки выбора домашнего экрана. Не отключай штатный Launcher."
                                } })
                            }, modifier = Modifier.fillMaxWidth()) { Text("Выбор домашнего экрана телевизора") }
                            Text("Безопасность: новый Launcher не назначается автоматически, а штатный экран не отключается. На части моделей замена домашнего экрана нарушает работу кнопок пульта, трансляции или запуск после перезагрузки. Для возврата выбери штатный Launcher в системных настройках.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            OutlinedButton(enabled = !busy, onClick = { pendingModuleRemoval = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить модули Clean Your TV") }
                            Text("Удаление затронет только пакеты Clean Your TV для пользователя телевизора. Перед удалением выбери штатный домашний экран в системных настройках.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                            Text("Curtain устанавливается как компонент Launcher. Полный откат настроек, профили и каталог модулей оригинала требуют отдельной проверки совместимости и пока не считаются перенесёнными.", color = TextMuted, fontSize = 11.sp)
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
                            Text("ГОТОВЫЕ ТЕМЫ CLEAN YOUR TV", color = GoldLight, style = MaterialTheme.typography.titleSmall)
                            Text("Темы загружаются из официального открытого проекта Clean Your TV. Для установки нужны интернет на телефоне и подключение к телевизору.", color = TextMuted, fontSize = 11.sp)
                            listOf("cinema" to "Кино", "arcade" to "Аркада", "console" to "Консоль", "horizon" to "Горизонт", "library" to "Библиотека", "mist" to "Туман", "quiet" to "Тишина").forEach { (themeId, label) ->
                                OutlinedButton(enabled = !busy, onClick = {
                                    val host = ip.trim()
                                    val p = port.toIntOrNull()
                                    if (host.isBlank() || p == null || p !in 1..65535) {
                                        status = "Проверь IP и порт ADB"
                                    } else runTask({
                                        val files = downloadCleanYourTvTheme(context, themeId)
                                        connectWithRetry(context, host, p) { adb ->
                                            pushLauncherTheme(adb, themeId, files.first, files.second)
                                        }
                                    })
                                }, modifier = Modifier.fillMaxWidth()) { Text("Применить тему: $label") }
                            }
                            Text("Для установки темы передаются config.json и wallpaper.jpg. Тема обновляет параметры Launcher и сбрасывает предыдущие локальные настройки внешнего вида, чтобы новая конфигурация вступила в силу.", color = Color(0xFFFFD98A), fontSize = 11.sp)
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
                        val knownCandidates = cleanupCandidates.count { packageFacts.containsKey(it) || activeProfile?.packages?.containsKey(it) == true }
                        Text("Проверка всего списка: " + allPackages.size + " пакетов. Совпадений: " + cleanupCandidates.size + ", из них в справочнике: " + knownCandidates + ".", color = Color.LightGray, fontSize = 12.sp)
                        Text("Справочник Clean Your TV: " + packageFacts.size + " общих пакетов" + (activeProfile?.let { " + ${it.packages.size} по модели" } ?: "") + ". Подсказки не являются командой на отключение.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                        if (allPackages.isEmpty()) Text("Полный список пакетов ещё не считан. Нажми «Определить устройство».", color = Color.LightGray, fontSize = 11.sp)
                        cleanupCandidates.forEach { candidate ->
                            val profileRule = activeProfile?.packages?.get(candidate)
                            val fact = packageFacts[candidate] ?: profileRule?.let { profileFact ->
                                PackageFact(candidate, candidate, "Рекомендация из профиля ${activeProfile.id}. Проверь назначение компонента перед действием.", profileFact.category)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("• " + candidate, color = GoldLight, fontSize = 12.sp)
                                if (fact != null) {
                                    Text(fact.name + " • " + cleanupCategoryLabel(fact.category), color = TextMain, fontSize = 11.sp)
                                    if (profileRule?.ask == true) Text("В профиле Clean Your TV: требуется ручная оценка перед отключением.", color = Color(0xFFFFD98A), fontSize = 10.sp)
                                    Text(fact.description, color = Color.LightGray, fontSize = 10.sp)
                                } else {
                                    Text("Совпадение по названию пакета; назначение не подтверждено справочником.", color = Color.LightGray, fontSize = 10.sp)
                                }
                            }
                        }
                        if (cleanupCandidates.isNotEmpty()) Text("Ничего не отключается автоматически. Перед любым действием проверяй назначение пакета: отключение системных компонентов может нарушить работу телевизора.", color = Color(0xFFFFD98A), fontSize = 11.sp)
                        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            val host = ip.trim(); val p = port.toIntOrNull()
                            if (host.isBlank() || p == null) status = "Сначала укажи IP и порт ADB"
                            else runTask({
                                connectWithRetry(context, host, p) { adb ->
                                    val details = readTvDetails(adb)
                                    val packages = readUserPackages(adb)
                                    val installed = readAllPackages(adb)
                                    val disabled = readDisabledPackages(adb)
                                    activity.runOnUiThread {
                                        tvDetails = details
                                        userPackages = packages
                                        allPackages = installed
                                        disabledOnDevice = disabled
                                    }
                                    "Устройство определено. Пользовательских приложений: ${packages.size}; всего пакетов: ${installed.size}; отключено на устройстве: ${disabled.size}"
                                }
                            })
                        }) { Text("Определить устройство и найти приложения") }
                        OutlinedTextField(value = packageFilter, onValueChange = { packageFilter = it },
                            label = { Text("Поиск приложения по имени пакета") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                                val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                runTask({
                                    connectWithRetry(context, host, p) { adb ->
                                        val snapshot = readDisabledUserPackages(adb)
                                        activity.runOnUiThread {
                                            restorePoint = snapshot
                                            restorePointExists = true
                                            settings.edit().putStringSet("restore_point_user_disabled", snapshot)
                                                .putBoolean("restore_point_exists", true).apply()
                                        }
                                        "Точка восстановления создана. Отключённых пользовательских приложений: ${snapshot.size}"
                                    }
                                })
                            }) { Text("Создать точку") }
                            OutlinedButton(enabled = !busy && restorePointExists, modifier = Modifier.weight(1f), onClick = {
                                val host = ip.trim(); val p = port.toIntOrNull() ?: 5555
                                val snapshot = restorePoint
                                runTask({
                                    connectWithRetry(context, host, p) { adb ->
                                        val current = readDisabledUserPackages(adb)
                                        val toEnable = current - snapshot
                                        val toDisable = snapshot - current
                                        val failures = mutableListOf<String>()
                                        toEnable.forEach { pkg -> try { changePackageState(adb, pkg, true) } catch (_: Exception) { failures.add(pkg) } }
                                        toDisable.forEach { pkg -> try { changePackageState(adb, pkg, false) } catch (_: Exception) { failures.add(pkg) } }
                                        if (failures.isEmpty()) "Состояние пользовательских приложений восстановлено по точке"
                                        else "Не удалось изменить: ${failures.joinToString()}"
                                    }
                                })
                            }) { Text("Восстановить точку") }
                        }
                        Text(
                            if (restorePointExists) "Точка сохранена: отключённых пользовательских приложений — ${restorePoint.size}"
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
                                            uninstallUserApp(adb, pkg)
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
                                val protected = isProtectedSystemPackage(pkg)
                                val isDisabled = pkg in disabledOnDevice
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(pkg, fontSize = 12.sp, color = TextMain)
                                    if (protected) {
                                        Text("Защищённый системный компонент", color = GoldLight, fontSize = 11.sp)
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
                                                        if (!result.startsWith("Ошибка")) disabledOnDevice = disabledOnDevice - pkg
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
                                                changePackageState(adb, pkg, false)
                                                "Системный пакет отключён: $pkg"
                                            }
                                        }, { result ->
                                            if (!result.startsWith("Ошибка")) disabledOnDevice = disabledOnDevice + pkg
                                        })
                                    }) { Text("Отключить") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingSystemDisable = null }) { Text("Отмена") }
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
