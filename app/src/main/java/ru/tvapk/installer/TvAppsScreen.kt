package ru.tvapk.installer

import android.content.Context
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
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Найди телевизор с включённой ADB-отладкой по сети.") }

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
