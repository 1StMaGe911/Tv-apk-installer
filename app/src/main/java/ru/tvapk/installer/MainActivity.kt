
package ru.tvapk.installer

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri as AndroidUri
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
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
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val APP_VERSION = "0.4.6"
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvInstallerApp()
        }
    }
}

private fun prefs(context: Context) =
    context.getSharedPreferences("tv_installer", Context.MODE_PRIVATE)

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
private fun readTvDetails(adb: Dadb): String {
    fun prop(name: String) = try { shellText(adb, "getprop $name").ifBlank { "неизвестно" } } catch (_: Exception) { "неизвестно" }
    return "Производитель: ${prop("ro.product.manufacturer")}\nМодель: ${prop("ro.product.model")}\nAndroid: ${prop("ro.build.version.release")}\nТип: ${prop("ro.build.characteristics")}"
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

private fun findCleanupCandidates(packages: List<String>): List<String> {
    val protected = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.tv.settings",
        "com.android.systemui",
        "com.android.providers.settings"
    )
    val markers = listOf("ads", "advert", "tracking", "analytics", "promo", "promotion", "feedback", "demo")
    return packages.filter { pkg ->
        pkg !in protected && markers.any { marker -> pkg.substringAfterLast('.').contains(marker) || pkg.contains(".$marker") }
    }.distinct().sorted()
}

@Composable
fun TvInstallerApp() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val uriHandler = LocalUriHandler.current

    val settings = remember { prefs(context) }

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
    var userPackages by remember { mutableStateOf(listOf<String>()) }
    var allPackages by remember { mutableStateOf(listOf<String>()) }
    var disabledOnDevice by remember { mutableStateOf(emptySet<String>()) }
    var systemPackageFilter by remember { mutableStateOf("") }
    var pendingSystemDisable by remember { mutableStateOf<String?>(null) }
    var disabledPackages by remember { mutableStateOf(settings.getStringSet("disabled_packages", emptySet())?.toSet() ?: emptySet()) }
    var packageFilter by remember { mutableStateOf("") }
    var restorePoint by remember { mutableStateOf(settings.getStringSet("restore_point_user_disabled", emptySet())?.toSet() ?: emptySet()) }
    var restorePointExists by remember { mutableStateOf(settings.getBoolean("restore_point_exists", false)) }


    var usbMode by remember { mutableStateOf(false) }
    var showCleanYourTv by remember { mutableStateOf(false) }

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
        colorScheme = darkColorScheme(
    background = Bg,
    onBackground = TextMain,
    surface = Panel,
    onSurface = TextMain,
    surfaceVariant = Color(0xFF292216),
    onSurfaceVariant = TextMuted,
    primary = Accent,
    onPrimary = Color(0xFF171006),
    secondary = GoldLight,
    onSecondary = Color(0xFF171006),
    outline = Color(0xFF6B542B)
)
    ) {
        Scaffold(containerColor = Bg) { padding ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                                        Button(
                        onClick = { usbMode = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!usbMode) Accent else Panel,
                            contentColor = if (!usbMode) Color(0xFF171006) else GoldLight
                        )
                    ) {
                        Text("Wi-Fi ADB")
                    }

                    Button(
                        onClick = { usbMode = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (usbMode) Accent else Panel,
                            contentColor = if (usbMode) Color(0xFF171006) else GoldLight
                        )
                    ) {
                        Text("USB OTG")
                    }
                }

                Button(
                    onClick = { showCleanYourTv = !showCleanYourTv },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showCleanYourTv) Accent else Panel,
                        contentColor = if (showCleanYourTv) Color(0xFF171006) else GoldLight
                    )
                ) {
                    Text(if (showCleanYourTv) "Скрыть Clean Your TV" else "Clean Your TV")
                }

                Card(
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(
        containerColor = Panel
    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Подключение телевизора",
                            style = MaterialTheme.typography.titleMedium
                        )

                                                                            if (!usbMode) {
                            OutlinedTextField(
                                value = tvName,
                                onValueChange = { tvName = it },
                                label = { Text("Название телевизора") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Color(0xFF6B542B),
                                    focusedLabelColor = Accent,
                                    cursorColor = Accent
                                )
                            )

                            OutlinedTextField(
                                value = ip,
                                onValueChange = { ip = it },
                                label = { Text("IP телевизора") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Color(0xFF6B542B),
                                    focusedLabelColor = Accent,
                                    cursorColor = Accent
                                )
                            )

                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it },
                                label = { Text("Порт ADB") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Color(0xFF6B542B),
                                    focusedLabelColor = Accent,
                                    cursorColor = Accent
                                )
                            )

                            Button(
                                enabled = !busy,
                                onClick = {
                                    val host = ip.trim()
                                    val p = port.toIntOrNull()

                                    if (host.isBlank() || p == null ||
                                        p !in 1..65535
                                    ) {
                                        status = "Проверь IP и порт"
                                    } else {
                                        settings.edit()
                                            .putString("ip", host)
                                            .putString("port", p.toString())
                                            .apply()

                                        runTask(action = {
                                            connectWithRetry(
                                                context,
                                                host,
                                                p
                                            ) {
                                                "ADB подключён: $host:$p"
                                            }
                                        })
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Подключиться")
                            }

                            OutlinedButton(
                                enabled = !busy,
                                onClick = { saveCurrentDevice() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Сохранить телевизор")
                            }
                        } else {
                            Button(
                                onClick = {
                                    val manager = context.getSystemService(
                                        Context.USB_SERVICE
                                    ) as UsbManager

                                    val found = manager.deviceList.values

                                    status = if (found.isEmpty()) {
                                        "USB-устройства не обнаружены"
                                    } else {
                                        "Найдены USB-устройства: " +
                                            found.joinToString {
                                                "${it.vendorId}:${it.productId}"
                                            } +
                                            ". ADB через USB пока не реализован."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Найти USB-устройства")
                            }
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
                        val cleanupCandidates = findCleanupCandidates(allPackages)
                        Text("Проверка всего списка: ${allPackages.size} пакетов. Возможных рекламных или диагностических совпадений: ${cleanupCandidates.size}", color = Color.LightGray, fontSize = 12.sp)
                        if (allPackages.isEmpty()) Text("Полный список пакетов ещё не считан. Нажми «Определить устройство».", color = Color.LightGray, fontSize = 11.sp)
                        cleanupCandidates.forEach { candidate ->
                            Text("• $candidate", color = GoldLight, fontSize = 12.sp)
                        }
                        if (cleanupCandidates.isNotEmpty()) Text("В список могут попасть системные компоненты. Это только поисковые совпадения: приложение не отключает и не удаляет их. Проверяй назначение каждого пакета отдельно.", color = Color(0xFFFFD98A), fontSize = 11.sp)
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
                        Text("Пользовательские приложения: ${userPackages.size}", color = GoldLight)
                        if (userPackages.isEmpty()) Text("Сначала выполни определение устройства. Системные пакеты здесь не показываются.", color = Color.LightGray, fontSize = 12.sp)
                        userPackages.filter { it.contains(packageFilter, ignoreCase = true) }.forEach { pkg ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(pkg, fontSize = 12.sp, color = TextMain)
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
