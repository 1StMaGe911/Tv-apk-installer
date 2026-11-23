package ru.tvapk.installer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dadb.AdbKeyPair
import dadb.Dadb
import dadb.SyncResult
import dadb.InstallResult
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
    val appLabel: String = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() },
    val versionName: String?,
    val versionCode: String?,
    val apkSizeBytes: Long?,
    val isSystem: Boolean = false,
    val firstInstallTime: String? = null,
    val isLaunchable: Boolean = false
)

private fun parseUserPackages(output: String): List<String> =
    output.lineSequence().map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }
        .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
        .distinct().sorted().toList()

private val knownTvAppLabels = mapOf(
    "com.netflix.ninja" to "Netflix",
    "com.google.android.youtube.tv" to "YouTube",
    "com.google.android.youtube.tvleanback" to "YouTube",
    "com.google.android.youtube" to "YouTube",
    "com.amazon.amazonvideo.livingroom" to "Prime Video",
    "com.amazon.avod.thirdpartyclient" to "Prime Video"
)

internal fun parseInstalledApp(packageName: String, details: String, sizeOutput: String, isSystem: Boolean = false): TvInstalledApp {
    val appLabel = knownTvAppLabels[packageName] ?: Regex("""(?m)^\s*application-label(?:-[^:]+)?:\s*['"]?(.*?)['"]?\s*$""").find(details)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        ?: Regex("""nonLocalizedLabel=(?:'([^']*)'|"([^"]*)"|([^,}]+))""").find(details)?.let { it.groupValues.drop(1).firstOrNull(String::isNotBlank)?.trim() }?.takeIf { it.isNotBlank() }
        ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    val versionName = Regex("""versionName=(\S+)""").find(details)?.groupValues?.get(1)
    val versionCode = Regex("""versionCode=(\d+)""").find(details)?.groupValues?.get(1)
    val sizeKb = sizeOutput.lineSequence().mapNotNull { line ->
        line.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
    }.sum().takeIf { it > 0L }
    return TvInstalledApp(
        packageName = packageName,
        appLabel = appLabel,
        versionName = versionName,
        versionCode = versionCode,
        apkSizeBytes = sizeKb?.times(1024L),
        isSystem = isSystem,
        firstInstallTime = Regex("""firstInstallTime=(.+)""").find(details)?.groupValues?.get(1)?.trim()
    )
}

private fun parseLauncherPackages(output: String): Set<String> =
    Regex("(?m)^\\s*([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/")
        .findAll(output).map { it.groupValues[1] }.toSet()

internal fun parseResolvedTvActivity(output: String): String? =
    Regex("""(?m)^\s*([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)\s*$""")
        .findAll(output).lastOrNull()?.groupValues?.get(1)

private val protectedTvPackages = setOf(
    "com.android.systemui", "com.android.settings",
    "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
    "com.android.providers.settings", "com.android.providers.media"
)

private fun formatApkSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "Размер неизвестен"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(java.util.Locale.getDefault(), "%.1f МБ", mb)
    else "${bytes / 1024L} КБ"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvAppsScreen(historyTvId: String, onBack: () -> Unit, discoverDevices: suspend () -> List<TvDevice>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(loadSavedTvAppsDevices(context)) }
    var deviceInfo by remember { mutableStateOf("") }
    var selectedDevice by remember { mutableStateOf<TvDevice?>(null) }
    var packages by remember { mutableStateOf(emptyList<TvInstalledApp>()) }
    var appSearch by remember { mutableStateOf("") }
    var appFilter by remember { mutableStateOf("Доступные") }
    var appSort by remember { mutableStateOf("Имя") }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var backupFolder by remember { mutableStateOf<Uri?>(null) }
    var restoreUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Найди телевизор с включённой ADB-отладкой по сети.") }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        restoreUris = uris
        if (uris.isNotEmpty()) status = "Выбрано APK-файлов для восстановления: ${uris.size}. Подключи телевизор и нажми «Восстановить»."
    }

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
                        val model = adb.shell("getprop ro.product.model").output.trim()
                        val manufacturer = adb.shell("getprop ro.product.manufacturer").output.trim()
                        val androidVersion = adb.shell("getprop ro.build.version.release").output.trim()
                        val sdk = adb.shell("getprop ro.build.version.sdk").output.trim()
                        val identity = listOf(manufacturer, model).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { device.name }
                        val info = listOf(identity, androidVersion.takeIf { it.isNotBlank() }?.let { "Android $it" }, sdk.takeIf { it.isNotBlank() }?.let { "SDK $it" }).filterNotNull().joinToString(" • ")
                        val response = adb.shell("pm list packages")
                        val systemResponse = adb.shell("pm list packages -s")
                        if (response.exitCode != 0) throw IllegalStateException(
                            response.allOutput.ifBlank { "Команда ADB завершилась с ошибкой ${response.exitCode}" }
                        )
                        val systemPackages = parseUserPackages(systemResponse.output).toSet()
                        val packageNames = parseUserPackages(response.output)
                        val launcherOutput = listOf(
                            adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER").allOutput,
                            adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER").allOutput
                        ).joinToString("\n")
                        val launchablePackages = parseLauncherPackages(launcherOutput)
                        val installed = packageNames.mapIndexed { index, packageName ->
                            withContext(Dispatchers.Main) {
                                status = "Получаем сведения: ${index + 1}/${packageNames.size}"
                            }
                            val details = adb.shell("dumpsys package $packageName").output
                            val sizes = adb.shell(
                                "for p in $(pm path $packageName | sed 's/^package://'); do du -k \"\$p\"; done"
                            ).output
                            parseInstalledApp(packageName, details, sizes, packageName in systemPackages).copy(
                                isLaunchable = packageName in launchablePackages
                            )
                        }
                        info to installed
                    }
                }
                deviceInfo = result.first
                packages = result.second
                status = "Найдено пакетов: ${result.second.size}. Приложений с кнопкой запуска: ${result.second.count { it.isLaunchable }}."
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
                TvAppsHistory.record(context, historyTvId, "Создана резервная копия APK", "Приложений: ${targets.size}; APK-файлов: $saved")
                status = "Резервная копия готова: сохранено APK-файлов $saved для ${targets.size} приложений."
                selectedPackages = emptySet()
            } catch (failure: Exception) {
                status = "Ошибка резервного копирования: ${failure.message ?: failure.javaClass.simpleName}. Успело сохраниться файлов: $saved."
            } finally { busy = false }
        }
    }

    fun restoreSelectedApks() {
        val device = selectedDevice ?: return
        val uris = restoreUris
        if (uris.isEmpty()) { restorePicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")); return }
        scope.launch {
            busy = true
            var restored = 0
            val tempFiles = mutableListOf<File>()
            try {
                val grouped = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "backup-${System.nanoTime()}.apk"
                        val match = Regex("^([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)_\\d+_").find(name)
                            ?: throw IllegalArgumentException("Имя файла не распознано как резервная копия Clean Your TV: $name")
                        val packageName = match.groupValues[1]
                        val local = File(context.cacheDir, "restore-${System.nanoTime()}-$name")
                        context.contentResolver.openInputStream(uri)?.use { input -> local.outputStream().use { output -> input.copyTo(output) } }
                            ?: throw IllegalStateException("Не удалось прочитать $name")
                        tempFiles.add(local)
                        packageName to local
                    }.groupBy({ it.first }, { it.second })
                }
                grouped.forEach { (packageName, files) ->
                    withContext(Dispatchers.Main) { status = "Восстанавливаем $packageName (${files.size} APK)…" }
                    val result = withContext(Dispatchers.IO) {
                        Dadb.create(device.ip, device.port, tvAppsKeyPair(context)).use { adb ->
                            if (files.size == 1) adb.install(files.first()) else adb.installMultiple(files)
                        }
                    }
                    when (result) {
                        InstallResult.Success -> { restored++; TvAppsHistory.record(context, historyTvId, "Восстановлено приложение", packageName) }
                        is InstallResult.Failure -> throw IllegalStateException("Не удалось восстановить $packageName: ${result.reason}")
                    }
                }
                status = "Восстановление завершено: установлено приложений $restored."
                restoreUris = emptyList()
                refreshPackages(device)
            } catch (failure: Exception) {
                status = "Ошибка восстановления: ${failure.message ?: failure.javaClass.simpleName}. Установлено приложений: $restored."
            } finally {
                tempFiles.forEach { it.delete() }
                busy = false
            }
        }
    }

    fun searchDevices() {
        scope.launch {
            busy = true
            status = "Ищем телевизоры в локальной сети…"
            try {
                val discovered = discoverDevices()
                devices = mergeSavedTvAppsDevices(context, devices + discovered)
                status = if (discovered.isEmpty()) {
                    "Телевизоры с доступной ADB-отладкой не найдены. Проверь, что телефон и ТВ в одной Wi-Fi-сети."
                } else "Найдено новых устройств: ${discovered.size}. Всего сохранено: ${devices.size}."
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
            status = "Запускаем $packageName…"
            try {
                val response = withContext(Dispatchers.IO) {
                    Dadb.create(device.ip, device.port, tvAppsKeyPair(context)).use { adb ->
                        val settingsAction = when (packageName) {
                            "com.android.settings", "com.google.android.tv.settings" -> "android.settings.SETTINGS"
                            else -> null
                        }
                        if (settingsAction != null) {
                            adb.shell("am start -W -a $settingsAction")
                        } else {
                            val leanback = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p $packageName")
                            val launcher = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName")
                            val component = parseResolvedTvActivity(leanback.allOutput)
                                ?: parseResolvedTvActivity(launcher.allOutput)
                            if (component != null) {
                                adb.shell("am start -W -n $component")
                            } else {
                                adb.shell("monkey -p $packageName 1")
                            }
                        }
                    }
                }
                val output = response.allOutput
                val failed = response.exitCode != 0 ||
                    output.contains("Error:", ignoreCase = true) ||
                    output.contains("unable to resolve", ignoreCase = true) ||
                    output.contains("No activities found", ignoreCase = true) ||
                    output.contains("No activity found", ignoreCase = true) ||
                    output.contains("monkey aborted", ignoreCase = true)
                status = if (!failed) {
                    TvAppsHistory.record(context, historyTvId, "Запущено приложение", packageName)
                    "Приложение запущено: $packageName\\n${output.take(180)}"
                } else "Не удалось запустить $packageName: ${output.take(240).ifBlank { "Android не нашёл активность запуска" }}"
            } catch (failure: Exception) {
                status = "Ошибка запуска: ${failure.message ?: failure.javaClass.simpleName}"
            } finally { busy = false }
        }
    }

    fun stopApp(packageName: String) {
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return
        val app = packages.firstOrNull { it.packageName == packageName } ?: return
        if (!app.isLaunchable || packageName in protectedTvPackages) return
        val device = selectedDevice ?: return
        scope.launch {
            busy = true
            status = "Останавливаем $packageName…"
            try {
                val response = withContext(Dispatchers.IO) {
                    Dadb.create(device.ip, device.port, tvAppsKeyPair(context)).use { adb ->
                        adb.shell("am force-stop --user 0 $packageName")
                    }
                }
                status = if (response.exitCode == 0) {
                    TvAppsHistory.record(context, historyTvId, "Остановлено приложение", packageName)
                    "Приложение остановлено: $packageName"
                } else "Не удалось остановить: ${response.allOutput.take(240)}"
            } catch (failure: Exception) {
                status = "Ошибка остановки: ${failure.message ?: failure.javaClass.simpleName}"
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
            if (devices.isNotEmpty()) Text("Сохранённые телевизоры", style = MaterialTheme.typography.titleMedium)
            devices.forEach { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                        Text("IP-адрес: ${device.ip}:${device.port}", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { selectedDevice = device; packages = emptyList(); deviceInfo = ""; refreshPackages(device) },
                                enabled = !busy
                            ) { Text(if (selectedDevice == device) "Подключить ещё раз" else "Подключиться") }
                            TextButton(
                                onClick = {
                                    devices = mergeSavedTvAppsDevices(context, devices.filterNot { it.ip == device.ip && it.port == device.port })
                                    if (selectedDevice == device) { selectedDevice = null; packages = emptyList(); deviceInfo = "" }
                                },
                                enabled = !busy
                            ) { Text("Забыть") }
                        }
                    }
                }
            }
            selectedDevice?.let { device ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Телевизор: ${device.name}")
                    Text("IP-адрес: ${device.ip}:${device.port}")
                    if (deviceInfo.isNotBlank()) Text(deviceInfo, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { refreshPackages(device) }, enabled = !busy) { Text("Проверить подключение / обновить") }
                }
            }
            if (selectedDevice != null) {
                OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Выбрать APK для восстановления (${restoreUris.size})")
                }
                Button(onClick = { restoreSelectedApks() }, enabled = !busy && restoreUris.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Восстановить выбранные приложения")
                }
                OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (backupFolder == null) "Выбрать папку для резервной копии" else "Изменить папку резервной копии")
                }
                Button(onClick = { backupSelectedApps() }, enabled = !busy && selectedPackages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Сохранить APK (${selectedPackages.size})")
                }
            }
            Text(status)
            if (selectedDevice != null && packages.isNotEmpty()) {
                OutlinedTextField(
                    value = appSearch,
                    onValueChange = { appSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск приложения") },
                    placeholder = { Text("Название или имя пакета") },
                    singleLine = true,
                    enabled = !busy
                )
                Text("Фильтр приложений", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Доступные", "Все пакеты", "Пользовательские", "Системные").forEach { option ->
                        FilterChip(selected = appFilter == option, onClick = { appFilter = option }, label = { Text(option) }, enabled = !busy)
                    }
                }
                Text("Сортировка", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Имя", "Размер", "Дата").forEach { option ->
                        FilterChip(selected = appSort == option, onClick = { appSort = option }, label = { Text(option) }, enabled = !busy)
                    }
                }
                val visibleApps = packages.filter { app ->
                    val matchesFilter = when (appFilter) {
                        "Доступные" -> app.isLaunchable
                        "Пользовательские" -> !app.isSystem
                        "Системные" -> app.isSystem
                        else -> true
                    }
                    val query = appSearch.trim()
                    matchesFilter && (query.isBlank() || app.appLabel.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
                }.let { list ->
                    when (appSort) {
                        "Размер" -> list.sortedByDescending { it.apkSizeBytes ?: -1L }
                        "Дата" -> list.sortedByDescending { it.firstInstallTime.orEmpty() }
                        else -> list.sortedBy { it.appLabel.lowercase() }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Показано: ${visibleApps.size}")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val eligible = visibleApps.filterNot { it.isSystem }.map { it.packageName }.toSet()
                        selectedPackages = if (eligible.isNotEmpty() && eligible.all { it in selectedPackages }) selectedPackages - eligible else selectedPackages + eligible
                    }, enabled = !busy && visibleApps.any { !it.isSystem }) { Text("Выбрать пользовательские") }
                }
                if (selectedPackages.isNotEmpty()) {
                    Text("Выбрано для резервной копии: ${selectedPackages.size}")
                }
            }
            if (selectedDevice != null && packages.isEmpty() && !busy) {
                Text("Пользовательские приложения не найдены или список ещё не загружен.")
            }
            val visibleApps = packages.filter { app ->
                val matchesFilter = when (appFilter) {
                    "Доступные" -> app.isLaunchable
                    "Пользовательские" -> !app.isSystem
                    "Системные" -> app.isSystem
                    else -> true
                }
                val query = appSearch.trim()
                matchesFilter && (query.isBlank() || app.appLabel.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
            }.let { list -> when (appSort) { "Размер" -> list.sortedByDescending { it.apkSizeBytes ?: -1L }; "Дата" -> list.sortedByDescending { it.firstInstallTime.orEmpty() }; else -> list.sortedBy { it.appLabel.lowercase() } } }
            visibleApps.forEach { app ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(app.appLabel, style = MaterialTheme.typography.titleSmall)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        Text(if (app.isSystem) "Системное приложение" else "Пользовательское приложение", style = MaterialTheme.typography.labelSmall)
                        app.firstInstallTime?.let { Text("Установлено: $it", style = MaterialTheme.typography.bodySmall) }
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
                                enabled = !busy && !app.isSystem
                            )
                            Text(if (app.isSystem) "Системное приложение" else "Выбрать для резервной копии", modifier = Modifier.padding(top = 12.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { launchApp(app.packageName) }, enabled = !busy && app.isLaunchable) { Text("Запустить") }
                            OutlinedButton(onClick = { stopApp(app.packageName) }, enabled = !busy && app.isLaunchable && app.packageName !in protectedTvPackages) { Text("Остановить") }
                        }
                    }
                }
            }
            if (busy) Text("Выполняется…")
            Spacer(Modifier.height(8.dp))
        }
    }


}
