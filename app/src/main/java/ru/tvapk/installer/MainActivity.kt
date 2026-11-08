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
import androidx.compose.material.icons.filled.FolderOpen
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
import dadb.InstallResult
import java.io.File

private val Bg = Color(0xFF10131B)
private val Panel = Color(0xFF1B2230)
private val Accent = Color(0xFF69B7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvInstallerApp() }
    }
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvInstallerApp() {
    val context = LocalContext.current

    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5555") }
    var status by remember { mutableStateOf("Не подключено") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("APK не выбран") }
    var mode by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

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
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Tv,
                        null,
                        tint = Accent,
                        modifier = Modifier.size(38.dp)
                    )

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text("TV APK Installer", fontSize = 23.sp)
                        Text(
                            "Установка приложений на ТВ",
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
                        Text("Wi-Fi")
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
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        if (mode == 0) {

                            Text(
                                "Подключение по Wi-Fi ADB",
                                style = MaterialTheme.typography.titleMedium
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
                                        runBackground {
                                            val key = getAdbKey(context)

                                            Dadb.create(
                                                host,
                                                p,
                                                key
                                            ).use {
                                                "ADB подключён: $host:$p"
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Подключиться")
                            }

                        } else {

                            Text(
                                "Подключение USB OTG",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Button(
                                enabled = !busy,
                                onClick = {
                                    val manager = context.getSystemService(
                                        Context.USB_SERVICE
                                    ) as UsbManager

                                    val devices = manager.deviceList.values

                                    status = if (devices.isEmpty()) {
                                        "USB-устройства не обнаружены"
                                    } else {
                                        "Найдены устройства: " +
                                            devices.joinToString {
                                                "${it.vendorId}:${it.productId}"
                                            } +
                                            ". ADB-транспорт ещё не подключён."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Найти USB-устройства")
                            }

                            Text(
                                "Для установки по USB потребуется отдельный ADB-транспорт поверх USB Host API.",
                                color = Color.LightGray,
                                fontSize = 12.sp
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
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Выбрать APK")
                        }

                        Button(
                            enabled = !busy &&
                                selectedUri != null &&
                                mode == 0,
                            onClick = {
                                val uri = selectedUri!!

                                runBackground {
                                    val file = File(
                                        context.cacheDir,
                                        "selected_app.apk"
                                    )

                                    context.contentResolver
                                        .openInputStream(uri).use { input ->
                                            requireNotNull(input) {
                                                "Не удалось открыть APK"
                                            }.use { source ->
                                                file.outputStream().use { output ->
                                                    source.copyTo(output)
                                                }
                                            }
                                        }

                                    require(file.length() > 0) {
                                        "APK-файл пустой"
                                    }

                                    val key = getAdbKey(context)

                                    Dadb.create(
                                        ip.trim(),
                                        port.toInt(),
                                        key
                                    ).use { adb ->

                                        when (val result = adb.install(file)) {
                                            is InstallResult.Success ->
                                                "APK успешно установлен!"

                                            is InstallResult.Failure ->
                                                "Ошибка установки: ${result.reason}"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Установить на телевизор")
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
                            "Состояние",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(status, color = Color.LightGray)

                        if (busy) {
                            CircularProgressIndicator()
                        }
                    }
                }

                Text(
                    "ADB поддерживается только совместимыми Android TV и приставками. VIDAA не поддерживает APK.",
                    color = Color(0xFFFFC66D),
                    fontSize = 12.sp
                )

                Text(
                    "TV APK Installer • версия 0.2.0",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        }
    }
    

