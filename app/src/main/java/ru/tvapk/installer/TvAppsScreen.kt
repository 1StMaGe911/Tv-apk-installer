package ru.tvapk.installer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import dadb.AdbKeyPair
import dadb.Dadb
import dadb.UninstallResult
import dadb.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun tvAppsKeyPair(context: Context): AdbKeyPair {
    val directory = File(context.filesDir, "adb")
    val privateKey = File(directory, "adbkey")
    val publicKey = File(directory, "adbkey.pub")
    if (!privateKey.exists() || !publicKey.exists()) {
        directory.mkdirs()
        AdbKeyPair.generate(privateKey, publicKey)
    }
    return AdbKeyPair.read(privateKey, publicKey)
}

data class TvInstalledApp(
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    val apkSizeBytes: Long?
)

private fun parseUserPackages(output: String): List<String> =
    output.lineSequence().map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }
        .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
        .distinct().sorted().toList()

internal fun parseInstalledApp(packageName: String, details: String, sizeOutput: String): TvInstalledApp {
    val versionName = Regex("""versionName=(\S+)""").find(details)?.groupValues?.get(1)
    val versionCode = Regex("""versionCode=(\d+)""").find(details)?.groupValues?.get(1)
    val sizeKb = sizeOutput.lineSequence().mapNotNull { line ->
        line.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
    }.sum().takeIf { it > 0L }
    return TvInstalledApp(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        apkSizeBytes = sizeKb?.times(1024L)
    )
}

private fun formatApkSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "Размер неизвестен"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(java.util.Locale.getDefault(), "%.1f МБ", mb)
    else "${bytes / 1024L} КБ"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvAppsScreen(onBack: () -> Unit, discoverDevices: suspend () -> List<TvDevice>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(emptyList<TvDevice>()) }
    var selectedDevice by remember { mutableStateOf<TvDevice?>(null) }
    var packages by remember { mutableStateOf(emptyList<TvInstalledApp>()) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var backupFolder by remember { mutableStateOf<Uri?>(null) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Найди телевизор с включённой ADB-отладкой по сети.") }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            backupFolder = uri
            status = "Папка для резервных копий выбрана. Теперь нажми «Сохранить APK»."
        }
    }

    fun refreshPackages(device: TvDevice) {
        scope.launch {
            busy = true
            status = "Получаем список приложений с телевизора…"
            try {
                val result = withContext(Dispatchers.IO) {
                    Dadb.create(device.ip, device.port, tvAppsKeyPair(context)).use { adb ->
                        val response = adb.shell("pm list packages -3")
                        if (response.exitCode != 0) throw IllegalStateException(
                            response.allOutput.ifBlank { "Команда ADB завершилась с ошибкой ${response.exitCode}" }
                        )
                        val packageNames = parseUserPackages(response.output)
                        packageNames.mapIndexed { index, packageName ->
                            withContext(Dispatchers.Main) {
                                status = "Получаем сведения: ${index + 1}/${packageNames.size}"
                            }
                            val details = adb.shell("dumpsys package \$packageName").output
                            val sizes = adb.shell(
                                "for p in $(pm path \$packageName | sed 's/^package://'); do du -k \"\$p\"; done"
                            ).output
                            parseInstalledApp(packageName, details, sizes)
                        }
                    }
                }
                packages = result
                status = "Найдено пользовательских приложений: ${result.size}. Системные приложения скрыты."
            } catch (failure: Exception) {
                status = "Не удалось получить список: ${failure.message ?: failure.javaClass.simpleName}"
            } finally { busy = false }
        }
    }

    fun backupSelectedApps() {
        val device = selectedDevice ?: return
        val folder = backupFolder ?: run { folderPicker.launch(null); return }
        val targets = packages.filter { it.packageName in selectedPackages }
        if (targets.isEmpty()) return
        scope.launch {
            busy = true
            val manifest = StringBuilder("Clean Your TV — резервная копия APK\nТелевизор: ${device.name} (${device.ip})\nДата: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n\n")
            var saved = 0
            try {
                withContext(Dispatchers.IO) {
                    Dadb.create(device.ip, device.port, tvAppsKeyPair(context)).use { adb ->
                        targets.forEachIndexed { index, app ->
                            withContext(Dispatchers.Main) { status = "Копируем ${index + 1}/${targets.size}: ${app.packageName}" }
                            val pathsResponse = adb.shell("pm path ${app.packageName}")
                            if (pathsResponse.exitCode != 0) throw IllegalStateException(pathsResponse.allOutput.ifBlank { "Не удалось получить APK для ${app.packageName}" })
                            val remotePaths = pathsResponse.output.lineSequence().map { it.trim() }
                                .filter { it.startsWith("package:") }
                                .map { it.removePrefix("package:").trim() }
                                .filter { it.startsWith("/") }.toList()
                            if (remotePaths.isEmpty()) throw IllegalStateException("APK-файлы не найдены: ${app.packageName}")
                            remotePaths.forEachIndexed { part, remotePath ->
                                val local = File(context.cacheDir, "tv-backup-${System.nanoTime()}.apk")
                                try {
                                    when (val result = adb.pull(local, remotePath)) {
                                        SyncResult.Success -> Unit
                                        is SyncResult.Failure -> throw IllegalStateException(result.reason)
                                    }
                                    val base = remotePath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                                    val name = "${app.packageName}_${part + 1}_$base"
                                    val destination = DocumentsContract.createDocument(
                                        context.contentResolver, folder,
                                        "application/vnd.android.package-archive", name
                                    ) ?: throw IllegalStateException("Не удалось создать файл $name в выбранной папке")
                                    context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                                        local.inputStream().use { input -> input.copyTo(output) }
                                    } ?: throw IllegalStateException("Не удалось записать $name")
                                    saved++
                                    manifest.append("${app.packageName}\t${app.versionName ?: "?"}\t$name\n")
                                } finally { local.delete() }
                            }
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    val manifestUri = DocumentsContract.createDocument(context.contentResolver, folder, "text/plain", "clean-your-tv-backup-manifest.txt")
                    if (manifestUri != null) context.contentResolver.openOutputStream(manifestUri, "w")?.use { it.write(manifest.toString().toByteArray(Charsets.UTF_8)) }
                }
                status = "Резервная копия готова: сохранено APK-файлов $saved для ${targets.size} приложений."
                selectedPackages = emptySet()
            } catch (failure: Exception) {
                status = "Ошибка резервного копирования: ${failure.message ?: failure.javaClass.simpleName}. Успело сохраниться файлов: $saved."
            } finally { busy = false }
        }
    }

    fun searchDevices() {
        scope.launch {
            busy = true
            status = "Ищем телевизоры в локальной сети…"
            try {
                devices = discoverDevices()
                status = if (devices.isEmpty()) {
                    "Телевизоры с доступной ADB-отладкой не найдены. Проверь, что телефон и ТВ в одной Wi-Fi-сети."
                } else "Найдено устройств: ${devices.size}. Выбери свой телевизор."
            } catch (failure: Exception) {
                status = "Ошибка поиска: ${failure.message ?: failure.javaClass.simpleName}"
            } finally { busy = false }
        }
    }

    fun launchApp(packageName: String) {
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return
        val device = selectedDevice ?: return
        scope.launch {
            busy = true
            status = "Запускаем \$packageName…"
            try {
                val response = withContext(Dispatchers.IO) {
                    Dadb.create(device.ip, device.port, tvAppsKeyPair(context)).use { adb ->
                        adb.shell("monkey -p \$packageName 1")
                    }
                }
                status = if (response.exitCode == 0) "Команда запуска отправлена: \$packageName"
                    else "Не удалось запустить: ${response.allOutput.take(240)}"
            } catch (failure: Exception) {
                status = "Ошибка запуска: ${failure.message ?: failure.javaClass.simpleName}"
            } finally { busy = false }
        }
    }

    fun uninstallApp(packageName: String) {
        val device = selectedDevice ?: return
        scope.launch {
            busy = true
            pendingUninstall = null
            status = "Удаляем \$packageName…"
            try {
                val result = withContext(Dispatchers.IO) {
                    Dadb.create(device.ip, device.port, tvAppsKeyPair(context)).use { adb ->
                        adb.uninstall(packageName)
                    }
                }
                when (result) {
                    UninstallResult.Success -> {
                        packages = packages.filterNot { it.packageName == packageName }
                        status = "Приложение удалено: \$packageName"
                    }
                    is UninstallResult.Failure -> status =
                        "Не удалось удалить \$packageName: ${result.reason.take(240)}"
                }
            } catch (failure: Exception) {
                status = "Ошибка удаления: ${failure.message ?: failure.javaClass.simpleName}"
            } finally { busy = false }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Приложения телевизора") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = { searchDevices() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Найти телевизоры")
            }
            devices.forEach { device ->
                OutlinedButton(
                    onClick = { selectedDevice = device; packages = emptyList(); refreshPackages(device) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${if (selectedDevice == device) "✓ " else ""}${device.name} — ${device.ip}:${device.port}")
                }
            }
            selectedDevice?.let { device ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Телевизор: ${device.name} (${device.ip})")
                    TextButton(onClick = { refreshPackages(device) }, enabled = !busy) { Text("Обновить") }
                }
            }
            if (selectedDevice != null) {
                OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (backupFolder == null) "Выбрать папку для резервной копии" else "Изменить папку резервной копии")
                }
                Button(onClick = { backupSelectedApps() }, enabled = !busy && selectedPackages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Сохранить APK (${selectedPackages.size})")
                }
            }
            Text(status)
            if (selectedDevice != null && packages.isEmpty() && !busy) {
                Text("Пользовательские приложения не найдены или список ещё не загружен.")
            }
            packages.forEach { app ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(app.packageName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Версия: ${app.versionName ?: "неизвестна"}" +
                                (app.versionCode?.let { " (код $it)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("Размер APK: ${formatApkSize(app.apkSizeBytes)}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            Checkbox(
                                checked = app.packageName in selectedPackages,
                                onCheckedChange = { checked -> selectedPackages = if (checked) selectedPackages + app.packageName else selectedPackages - app.packageName },
                                enabled = !busy
                            )
                            Text("Включить в резервную копию", modifier = Modifier.padding(top = 12.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { launchApp(app.packageName) }, enabled = !busy) { Text("Запустить") }
                            OutlinedButton(onClick = { pendingUninstall = app.packageName }, enabled = !busy) { Text("Удалить") }
                        }
                    }
                }
            }
            if (busy) Text("Выполняется…")
            Spacer(Modifier.height(8.dp))
        }
    }

    pendingUninstall?.let { packageName ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingUninstall = null },
            title = { Text("Удалить приложение?") },
            text = { Text("Будет удалено приложение \$packageName с телевизора. Системные приложения в этом списке не отображаются.") },
            confirmButton = { TextButton(onClick = { uninstallApp(packageName) }, enabled = !busy) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { pendingUninstall = null }, enabled = !busy) { Text("Отмена") } }
        )
    }
}
