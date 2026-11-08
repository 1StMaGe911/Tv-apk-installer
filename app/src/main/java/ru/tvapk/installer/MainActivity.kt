
package ru.tvapk.installer

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val APP_VERSION = "0.3.0"
private const val GITHUB_RELEASES =
    "https://api.github.com/repos/1StMaGe911/Tv-apk-installer/releases/latest"

private val Bg = Color(0xFF10131B)
private val Panel = Color(0xFF1B2230)
private val Accent = Color(0xFF69B7FF)

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

private fun getPrefs(context: Context) =
    context.getSharedPreferences("tv_installer", Context.MODE_PRIVATE)

private fun getAdbKey(context: Context): AdbKeyPair {
    val directory = File(context.filesDir, "adb")
    val privateKey = File(directory, "adbkey")
    val publicKey = File(directory, "adbkey.pub")

    if (!privateKey.exists()) {
        directory.mkdirs()
        AdbKeyPair.generate(privateKey, publicKey)
    }

    return AdbKeyPair.read(privateKey, publicKey)
}

private fun describeError(e: Throwable): String {
    return generateSequence(e) { it.cause }
        .joinToString(" → ") {
            "${it.javaClass.simpleName}: ${it.message ?: "без сообщения"}"
        }
}

private fun loadDevices(context: Context): List<TvDevice> {
    return try {
        val json = JSONArray(
            getPrefs(context).getString("devices", "[]")
        )

        (0 until json.length()).map {
            val item = json.getJSONObject(it)
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
    val json = JSONArray()

    devices.forEach {
        json.put(
            JSONObject()
                .put("name", it.name)
                .put("ip", it.ip)
                .put("port", it.port)
        )
    }

    getPrefs(context).edit()
        .putString("devices", json.toString())
        .apply()
}

private fun loadHistory(context: Context): List<String> {
    return getPrefs(context)
        .getString("history", "")
        .orEmpty()
        .lines()
        .filter { it.isNotBlank() }
}

private fun saveHistory(context: Context, history: List<String>) {
    getPrefs(context).edit()
        .putString("history", history.take(30).joinToString("\n"))
        .apply()
}

private fun withAdbRetry(
    context: Context,
    host: String,
    port: Int,
    action: (Dadb) -> String
): String {
    var lastError: Throwable? = null

    repeat(3) { attempt ->
        try {
            val key = getAdbKey(context)

            return Dadb.create(host, port, key).use { adb ->
                action(adb)
            }
        } catch (e: Exception) {
            lastError = e
            if (attempt < 2) Thread.sleep(1500)
        }
    }

    throw Exception(
        "Не удалось подключиться после 3 попыток: " +
            describeError(lastError ?: Exception("Неизвестная ошибка"))
    )
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

private fun checkLatestVersion(): Pair<String, String?> {
    val connection =
        URL(GITHUB_RELEASES).openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "TV-APK-Installer")
        connection.setRequestProperty("Accept", "application/vnd.github+json")

        if (connection.responseCode != 200) {
            return Pair(
                "Не удалось проверить обновление. Код: ${connection.responseCode}",
                null
            )
        }

        val response = connection.inputStream.bufferedReader().use {
            it.readText()
        }

        val json = JSONObject(response)
        val tag = json.optString("tag_name")
        val releaseUrl = json.optString("html_url")

        if (tag.isBlank()) {
            return Pair("Не удалось определить версию релиза", null)
        }

        return if (compareVersions(tag, APP_VERSION) > 0) {
            Pair("Доступно обновление: $tag", releaseUrl)
        } else {
            Pair("Установлена актуальная версия $APP_VERSION", null)
        }
    } finally {
        connection.disconnect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvInstallerApp() {
    val context = LocalContext.current
    val prefs = remember { getPrefs(context) }

    var ip by remember {
        mutableStateOf(prefs.getString("ip", "") ?: "")
    }

    var port by remember {
        mutableStateOf(prefs.getString("port", "5555") ?: "5555")
    }

    var tvName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Не подключено") }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("APK не выбран") }

    var mode by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    var releaseUrl by remember { mutableStateOf<String?>(null) }
    var versionStatus by remember {
        mutableStateOf("Проверка ещё не выполнялась")
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

    val uriHandler =
        androidx.compose.ui.platform.LocalUriHandler.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        selectedUri = uri
        selectedName = uri?.lastPathSegment ?: "APK не выбран"
    }

    fun runBackground(action: () -> String) {
        if (busy) return

        busy = true

        Thread {
            val result = try {
                action()
            } catch (e: Exception) {
                "Ошибка: ${describeError(e)}"
            }

            (context as ComponentActivity).runOnUiThread {
                status = result
                busy = false
            }
        }.start()
    }

    fun saveConnection() {
        val host = ip.trim()
        val p = port.toIntOrNull()

        if (host.isBlank() || p == null || p !in 1..65535) {
            status = "Проверь IP и порт"
            return
        }

        prefs.edit()
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
            surface = Panel,
            primary = Accent
        )
    ) {
        Scaffold(containerColor = Bg) { padding ->

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Tv,
                        null,
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

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = mode == 0,
                        onClick = { mode = 0 },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) {
                        Text("Wi-Fi ADB")
                    }

                    SegmentedButton(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) {
                        Text("USB OTG")
                    }
                }

                Card(
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

                        if (mode == 0) {
                            OutlinedTextField(
                                value = tvName,
                                onValueChange = { tvName = it },
                                label = { Text("Название телевизора") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = ip,
                                onValueChange = { ip = it },
                                label = { Text("IP телевизора") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it },
                                label = { Text("Порт ADB") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
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
                                        prefs.edit()
                                            .putString("ip", host)
                                            .putString("port", p.toString())
                                            .apply()

                                        runBackground {
                                            withAdbRetry(context, host, p) {
                                                "ADB подключён: $host:$p"
                                            }
                                        }
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
                                onClick = { saveConnection() },
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
                                        mode = 0
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
                                        "Удалить",
                                        tint = Color.LightGray
                                    )
                                }
                            }

                            HorizontalDivider(color = Color.DarkGray)
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
                                null,
                                tint = Accent
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Установка APK",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Text(selectedName, color = Color.LightGray)

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
                                    status = "Прове
