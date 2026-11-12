package ru.tvapk.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dadb.AdbKeyPair
import dadb.InstallResult
import tv.cleanyour.core.flows.DocumentTvRepository
import tv.cleanyour.core.flows.TvAccess
import tv.cleanyour.core.flows.AccessResult
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.InstallOutcome
import java.io.File
import java.security.MessageDigest

private const val MAX_USER_APK_BYTES = 200L * 1024L * 1024L
private data class PreparedUserApk(val file: File, val blob: BlobRef, val packageName: String, val size: Long)

private suspend fun prepareUserApk(context: Context, uri: Uri): PreparedUserApk = withContext(Dispatchers.IO) {
    val file = File(context.cacheDir, "install-${System.nanoTime()}.apk")
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Не удалось открыть выбранный APK-файл")
        input.use { source ->
            file.outputStream().buffered().use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_USER_APK_BYTES) { "Размер APK превышает 200 МБ" }
                    digest.update(buffer, 0, count)
                    target.write(buffer, 0, count)
                }
            }
        }
        require(total > 0L) { "Выбранный файл пуст" }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IllegalArgumentException("Выбранный файл не является корректным APK")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        PreparedUserApk(file, BlobRef(Sha256(sha), total, file.absolutePath), info.packageName, total)
    } catch (failure: Throwable) {
        file.delete()
        throw failure
    }
}

private fun documentName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "APK-файл"

private fun usbAdbKeyFile(context: Context): File {
    val dir = File(context.filesDir, "adb")
    val privateKey = File(dir, "adbkey")
    if (!privateKey.exists()) {
        dir.mkdirs()
        AdbKeyPair.generate(privateKey, File(dir, "adbkey.pub"))
    }
    return privateKey
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkInstallerScreen(graph: AppGraph, tvIdValue: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tvId = remember(tvIdValue) { TvId(tvIdValue) }
    val tvs by graph.tvs.tvs.collectAsStateWithLifecycle()
    val tv = tvs.firstOrNull { it.id == tvId }

    var mode by remember { mutableStateOf("wifi") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("APK не выбран") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Выбери APK-файл для установки на телевизор.") }
    var progress by remember { mutableStateOf(0f) }
    var usbDevices by remember { mutableStateOf(emptyList<UsbDevice>()) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uri = picked
            name = documentName(context, picked)
            status = "Файл выбран. Можно запускать установку."
            progress = 0f
        }
    }

    fun installWifi() {
        val picked = uri ?: return
        scope.launch {
            busy = true
            progress = 0f
            var prepared: PreparedUserApk? = null
            try {
                status = "Проверяем APK-файл…"
                val apk = prepareUserApk(context, picked)
                prepared = apk
                status = "Подключаемся к сохранённому телевизору…"
                when (val access = graph.access.session(tvId)) {
                    is AccessResult.Ok -> {
                        status = "Устанавливаем ${apk.packageName} по Wi-Fi…"
                        when (val result = access.session.installStream(apk.blob, apk.size) { sent ->
                            mainHandler.post { progress = (sent.toFloat() / apk.size).coerceIn(0f, 1f) }
                        }) {
                            InstallOutcome.Installed -> {
                                progress = 1f
                                status = "Приложение успешно установлено по Wi-Fi: ${apk.packageName}"
                            }
                            is InstallOutcome.Failed -> status = "Телевизор отклонил установку (${result.code}): ${result.detail}"
                        }
                    }
                    is AccessResult.Failed -> status = "Не удалось подключиться к телевизору: ${access.error}"
                    AccessResult.Cancelled -> status = "Подключение отменено."
                    AccessResult.Unknown -> status = "Телевизор не найден в сохранённых устройствах. Вернись и выбери телевизор."
                }
            } catch (failure: Exception) {
                status = "Ошибка установки: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                prepared?.file?.delete()
                busy = false
            }
        }
    }

    fun installUsb(device: UsbDevice) {
        val picked = uri ?: return
        scope.launch {
            busy = true
            progress = 0f
            var prepared: PreparedUserApk? = null
            try {
                val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                if (!manager.hasPermission(device)) {
                    val permissionIntent = PendingIntent.getBroadcast(
                        context, device.deviceId,
                        Intent("${context.packageName}.USB_PERMISSION").setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    )
                    manager.requestPermission(device, permissionIntent)
                    status = "Разреши доступ к USB в системном окне, затем снова нажми «Установить по USB»."
                    return@launch
                }
                status = "Проверяем APK-файл…"
                val apk = prepareUserApk(context, picked)
                prepared = apk
                status = "Подключаемся к USB ADB и устанавливаем ${apk.packageName}…"
                status = withContext(Dispatchers.IO) {
                    UsbDadb.connect(context, device, usbAdbKeyFile(context)).use { adb ->
                        when (val result = adb.install(apk.file)) {
                            is InstallResult.Success -> "Приложение успешно установлено по USB: ${apk.packageName}"
                            is InstallResult.Failure -> throw IllegalStateException("Телевизор отклонил установку: ${result.reason}")
                        }
                    }
                }
                progress = 1f
            } catch (failure: Exception) {
                status = "Ошибка USB-установки: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                prepared?.file?.delete()
                busy = false
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Установка APK") },
            navigationIcon = { IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Телевизор: ${tv?.nickname ?: "не выбран"}", style = MaterialTheme.typography.titleMedium)
            Text("Поиск телевизора уже выполнен в Clean Your TV. Здесь выбирается только APK и способ установки.")
            OutlinedButton(
                enabled = !busy,
                onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (uri == null) "Выбрать APK-файл" else "Выбрать другой APK") }
            Text(name, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = mode == "wifi", onClick = { mode = "wifi" }, label = { Text("Wi-Fi") }, enabled = !busy)
                FilterChip(selected = mode == "usb", onClick = { mode = "usb" }, label = { Text("USB OTG") }, enabled = !busy)
            }
            if (mode == "wifi") {
                Text("Установка на выбранный телевизор через уже настроенное ADB-подключение.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = uri != null && !busy && tv != null, onClick = { installWifi() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Выполняется…" else "Установить по Wi-Fi")
                }
            } else {
                Text(
                    "Подключи телефон к телевизору через OTG. Телевизор должен предоставлять ADB USB-интерфейс (FF/42/01); обычный USB-порт телевизора часто работает только как HOST.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(enabled = !busy, onClick = {
                    usbDevices = (context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.toList()
                    status = if (usbDevices.isEmpty()) "USB-устройства не обнаружены. Проверь OTG-кабель."
                        else "Найдено USB-устройств: ${usbDevices.size}"
                }, modifier = Modifier.fillMaxWidth()) { Text("Обновить список USB-устройств") }
                if (usbDevices.isEmpty()) Text("Нажми «Обновить список USB-устройств».", style = MaterialTheme.typography.bodySmall)
                usbDevices.forEach { device ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(device.productName?.toString()?.takeIf { it.isNotBlank() } ?: device.deviceName,
                                style = MaterialTheme.typography.titleSmall)
                            val hasAdb = UsbDadb.hasAdbInterface(device)
                            Text(if (hasAdb) "ADB USB-интерфейс найден" else "ADB USB-интерфейс не найден",
                                style = MaterialTheme.typography.bodySmall)
                            Button(enabled = hasAdb && uri != null && !busy, onClick = { installUsb(device) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Установить по USB")
                            }
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(status, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
