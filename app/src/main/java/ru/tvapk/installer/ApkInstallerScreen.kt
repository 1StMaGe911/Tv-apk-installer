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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dadb.AdbKeyPair
import dadb.Dadb
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

private fun formatInstallReport(connection: String, results: List<String>): String {
    val succeeded = results.count { it.startsWith("✓ ") }
    val failed = results.size - succeeded
    return buildString {
        append("Установка завершена (").append(connection).append(").\n")
        append("Успешно: ").append(succeeded).append(" из ").append(results.size)
        append("; с ошибками: ").append(failed).append(".")
        if (results.isNotEmpty()) {
            append("\n\n")
            append(results.joinToString("\n"))
        }
    }
}

private fun installErrorMessage(failure: Exception): String {
    val technical = (failure.message ?: failure.javaClass.simpleName)
        .replace('\n', ' ')
        .take(220)
    val message = technical.lowercase()
    val guidance = when {
        message.contains("unauthorized") || message.contains("not authorized") ||
            message.contains("authentication") || message.contains("adb key") ||
            message.contains("auth failed") ->
            "Телевизор не подтвердил ADB-доступ. Подтверди запрос отладки на экране ТВ."
        message.contains("connection refused") || message.contains("econnrefused") ->
            "Телевизор отклонил подключение. Проверь сетевую ADB-отладку и порт."
        message.contains("timeout") || message.contains("timed out") ->
            "Телевизор не ответил вовремя. Проверь Wi-Fi и доступность ТВ."
        message.contains("no route to host") || message.contains("network is unreachable") ||
            message.contains("host is unreachable") ->
            "Телевизор недоступен по сети. Проверь IP-адрес и подключение к одной сети."
        message.contains("install_failed_update_incompatible") ->
            "Не совпадает подпись установленного приложения. Удаление старой версии может привести к потере её данных."
        message.contains("install_failed_version_downgrade") ->
            "На телевизоре уже установлена более новая версия приложения."
        message.contains("install_failed_insufficient_storage") ->
            "На телевизоре недостаточно свободной памяти."
        else -> null
    }
    return if (guidance == null) technical else "$guidance\nТехническая ошибка: $technical"
}

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
fun ApkInstallerScreen(
    tvsRepository: DocumentTvRepository,
    tvAccess: TvAccess,
    tvIdValue: String?,
    onBack: () -> Unit,
    discoverDevices: suspend () -> List<TvDevice>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTvId by remember { mutableStateOf(tvIdValue) }
    var tvMenuExpanded by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf(emptyList<TvDevice>()) }
    var selectedDiscovered by remember { mutableStateOf<TvDevice?>(null) }
    var searching by remember { mutableStateOf(false) }
    val tvs by tvsRepository.tvs.collectAsStateWithLifecycle()
    val tv = tvs.firstOrNull { it.id.value == selectedTvId }

    var mode by remember { mutableStateOf("wifi") }
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Выбери APK-файл для установки на телевизор.") }
    var progress by remember { mutableStateOf(0f) }
    var usbDevices by remember { mutableStateOf(emptyList<UsbDevice>()) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) {
            uris = picked.distinct()
            names = uris.map { documentName(context, it) }
            status = "Выбрано APK-файлов: ${uris.size}. Установка будет выполняться по очереди."
            progress = 0f
        }
    }

    fun installWifi() {
        val files = uris
        if (files.isEmpty()) return
        scope.launch {
            busy = true
            progress = 0f
            val results = mutableListOf<String>()
            try {
                files.forEachIndexed { index, picked ->
                    var prepared: PreparedUserApk? = null
                    var installedPackageName: String? = null
                    var installSucceeded = false
                    var installError: String? = null
                    try {
                        status = "(${index + 1}/${files.size}) Проверяем APK…"
                        val apk = prepareUserApk(context, picked)
                        prepared = apk
                        installedPackageName = apk.packageName
                        val discovered = selectedDiscovered
                        if (discovered != null) {
                            status = "(${index + 1}/${files.size}) Устанавливаем ${apk.packageName} по Wi-Fi…"
                            withContext(Dispatchers.IO) {
                                val privateKey = usbAdbKeyFile(context)
                                val keyPair = AdbKeyPair.read(privateKey, File(privateKey.parentFile, "adbkey.pub"))
                                Dadb.create(discovered.ip, discovered.port, keyPair).use { adb ->
                                    when (val result = adb.install(apk.file)) {
                                        is InstallResult.Success -> Unit
                                        is InstallResult.Failure -> throw IllegalStateException(result.reason)
                                    }
                                }
                            }
                            results += "✓ ${apk.packageName}"
                        } else {
                            val targetTvId = selectedTvId?.let(::TvId)
                                ?: throw IllegalStateException("Сначала выбери или найди телевизор")
                            when (val access = tvAccess.session(targetTvId)) {
                                is AccessResult.Ok -> {
                                    when (val result = access.session.installStream(apk.blob, apk.size) { sent ->
                                        mainHandler.post {
                                            progress = ((index + sent.toFloat() / apk.size) / files.size).coerceIn(0f, 1f)
                                        }
                                    }) {
                                        InstallOutcome.Installed -> results += "✓ ${apk.packageName}"
                                        is InstallOutcome.Failed -> throw IllegalStateException("код ${result.code}: ${result.detail}")
                                    }
                                }
                                is AccessResult.Failed -> throw IllegalStateException("Не удалось подключиться: ${access.error}")
                                AccessResult.Cancelled -> throw IllegalStateException("Подключение отменено")
                                AccessResult.Unknown -> throw IllegalStateException("Телевизор не найден")
                            }
                        }
                        installSucceeded = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        installError = installErrorMessage(failure)
                        results += "✗ ${names.getOrNull(index) ?: "APK"}: ${installError}"
                    } finally {
                        prepared?.file?.delete()
                    }
                    InstallHistoryStore.record(
                        context,
                        InstallHistoryEntry(
                            apkName = names.getOrNull(index) ?: "APK-файл",
                            packageName = installedPackageName,
                            tvName = selectedDiscovered?.let { "${it.name} (${it.ip})" } ?: tv?.nickname ?: "Не указан",
                            connection = "Wi-Fi",
                            success = installSucceeded,
                            error = installError
                        )
                    )
                    progress = (index + 1f) / files.size
                }
                status = formatInstallReport("Wi-Fi", results)
            } finally {
                busy = false
            }
        }
    }

    fun installUsb(device: UsbDevice) {
        val files = uris
        if (files.isEmpty()) return
        scope.launch {
            busy = true
            progress = 0f
            val results = mutableListOf<String>()
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
                files.forEachIndexed { index, picked ->
                    var prepared: PreparedUserApk? = null
                    var installedPackageName: String? = null
                    var installSucceeded = false
                    var installError: String? = null
                    try {
                        status = "(${index + 1}/${files.size}) Проверяем APK…"
                        val apk = prepareUserApk(context, picked)
                        prepared = apk
                        installedPackageName = apk.packageName
                        status = "(${index + 1}/${files.size}) Устанавливаем ${apk.packageName} по USB…"
                        withContext(Dispatchers.IO) {
                            UsbDadb.connect(context, device, usbAdbKeyFile(context)).use { adb ->
                                when (val result = adb.install(apk.file)) {
                                    is InstallResult.Success -> Unit
                                    is InstallResult.Failure -> throw IllegalStateException(result.reason)
                                }
                            }
                        }
                        results += "✓ ${apk.packageName}"
                        installSucceeded = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        installError = installErrorMessage(failure)
                        results += "✗ ${names.getOrNull(index) ?: "APK"}: ${installError}"
                    } finally {
                        prepared?.file?.delete()
                    }
                    InstallHistoryStore.record(
                        context,
                        InstallHistoryEntry(
                            apkName = names.getOrNull(index) ?: "APK-файл",
                            packageName = installedPackageName,
                            tvName = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: device.deviceName,
                            connection = "USB OTG",
                            success = installSucceeded,
                            error = installError
                        )
                    )
                    progress = (index + 1f) / files.size
                }
                status = formatInstallReport("USB", results)
            } finally {
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
            Box {
                TextButton(onClick = { tvMenuExpanded = true }, enabled = tvs.isNotEmpty() && !busy) {
                    Text("Телевизор: ${selectedDiscovered?.name ?: tv?.nickname ?: "не выбран"}  ▾")
                }
                DropdownMenu(expanded = tvMenuExpanded, onDismissRequest = { tvMenuExpanded = false }) {
                    tvs.forEach { savedTv ->
                        DropdownMenuItem(
                            text = { Text(savedTv.nickname) },
                            onClick = { selectedTvId = savedTv.id.value; selectedDiscovered = null; tvMenuExpanded = false }
                        )
                    }
                }
            }
            OutlinedButton(
                enabled = !busy && !searching,
                onClick = {
                    scope.launch {
                        searching = true
                        selectedDiscovered = null
                        status = "Ищем телевизоры с включённой ADB-отладкой в локальной сети…"
                        try {
                            discoveredDevices = discoverDevices()
                            status = if (discoveredDevices.isEmpty()) {
                                "Телевизоры с доступной ADB-отладкой не найдены. Проверь, что телефон и ТВ в одной Wi-Fi-сети и на ТВ включена отладка по сети."
                            } else {
                                "Найдено ADB-устройств: ${discoveredDevices.size}. Выбери телевизор ниже."
                            }
                        } catch (failure: Exception) {
                            status = "Ошибка поиска: ${installErrorMessage(failure)}"
                        } finally {
                            searching = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (searching) "Ищем телевизоры…" else "Найти телевизоры в локальной сети") }
            discoveredDevices.forEach { device ->
                OutlinedButton(
                    enabled = !busy && !searching,
                    onClick = {
                        selectedDiscovered = device
                        selectedTvId = null
                        status = "Выбран найденный телевизор: ${device.name} — ${device.ip}:${device.port}"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        (if (selectedDiscovered == device) "✓ " else "") +
                            "${device.name} — ${device.ip}:${device.port}"
                    )
                }
            }
            if (tvs.isEmpty() && discoveredDevices.isEmpty()) {
                Text("Сохранённых телевизоров пока нет. Нажми «Найти телевизоры в локальной сети» или используй USB OTG.")
            }
            Text("Можно выбрать сохранённый телевизор или найти новый в локальной сети. Для поиска на ТВ должна быть включена ADB-отладка по сети.")
            OutlinedButton(
                enabled = !busy,
                onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (uris.isEmpty()) "Выбрать APK-файлы" else "Выбрать ещё / заменить APK") }
            Text(if (names.isEmpty()) "APK-файлы не выбраны" else "Выбрано файлов: ${names.size}\n" + names.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = mode == "wifi", onClick = { mode = "wifi" }, label = { Text("Wi-Fi") }, enabled = !busy)
                FilterChip(selected = mode == "usb", onClick = { mode = "usb" }, label = { Text("USB OTG") }, enabled = !busy)
            }
            if (mode == "wifi") {
                Text("Установка на выбранный телевизор через уже настроенное ADB-подключение.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = uris.isNotEmpty() && !busy && (tv != null || selectedDiscovered != null), onClick = { installWifi() }, modifier = Modifier.fillMaxWidth()) {
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
                            Button(enabled = hasAdb && uris.isNotEmpty() && !busy, onClick = { installUsb(device) }, modifier = Modifier.fillMaxWidth()) {
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
