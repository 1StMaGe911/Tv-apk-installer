#!/usr/bin/env python3
"""Add read-only Android TV diagnostics to the main menu."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
up = root / "vendor" / "cleanyourtv-app"
app = up / "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"
manage = up / "feature/manage/src/main/kotlin/tv/cleanyour/feature/manage/ManageScreens.kt"

def replace_once(path, old, new):
    s = path.read_text(encoding="utf-8")
    if new in s: return
    n = s.count(old)
    if n != 1: raise SystemExit("Expected one match in " + str(path) + "; found " + str(n))
    path.write_text(s.replace(old, new), encoding="utf-8")

replace_once(app, "import ru.tvapk.installer.InstallHistoryScreen",
             "import ru.tvapk.installer.InstallHistoryScreen\nimport ru.tvapk.installer.TvDiagnosticsScreen")
replace_once(app, "    @Serializable data object InstallHistory : Dest",
             "    @Serializable data object TvDiagnostics : Dest\n    @Serializable data object InstallHistory : Dest")
replace_once(app, '        "installhistory" -> Dest.InstallHistory',
             '        "installhistory" -> Dest.InstallHistory\n        "tvdiagnostics" -> Dest.TvDiagnostics')
replace_once(app,
'''ManageScreen(state, m::dispatch, onInstallApk = { go("installapk") }, onTvApps = { state.current?.id?.let { backStack.add(Dest.TvApps(it.value)) } }, onInstallHistory = { go("installhistory") })''',
'''ManageScreen(state, m::dispatch, onInstallApk = { go("installapk") }, onTvApps = { state.current?.id?.let { backStack.add(Dest.TvApps(it.value)) } }, onInstallHistory = { go("installhistory") }, onTvDiagnostics = { go("tvdiagnostics") })''')
replace_once(app, "                entry<Dest.InstallHistory> {",
'''                entry<Dest.TvDiagnostics> {
                    TvDiagnosticsScreen(onBack = { go("back") })
                }
                entry<Dest.InstallHistory> {''')
replace_once(manage,
'''onInstallApk: () -> Unit = {}, onTvApps: () -> Unit = {}, onInstallHistory: () -> Unit = {}) {''',
'''onInstallApk: () -> Unit = {}, onTvApps: () -> Unit = {}, onInstallHistory: () -> Unit = {}, onTvDiagnostics: () -> Unit = {}) {''')
replace_once(manage,
'''            ListRow("История установок", subtitle = "Результаты установки APK, дата, телевизор и ошибки", onClick = onInstallHistory)''',
'''            ListRow("История установок", subtitle = "Результаты установки APK, дата, телевизор и ошибки", onClick = onInstallHistory)
            ListRow("Диагностика ТВ", subtitle = "Оперативная память, процессор, температура и хранилище", onClick = onTvDiagnostics)''')

screen = root / "app/src/main/java/ru/tvapk/installer/TvDiagnosticsScreen.kt"
screen.write_text("""package ru.tvapk.installer

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun diagnosticsKeyPair(context: android.content.Context): AdbKeyPair {
    val dir = File(context.filesDir, "adb")
    val priv = File(dir, "adbkey")
    val pub = File(dir, "adbkey.pub")
    if (!priv.exists() || !pub.exists()) { dir.mkdirs(); AdbKeyPair.generate(priv, pub) }
    return AdbKeyPair.read(priv, pub)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { loadSavedTvAppsDevices(context) }
    var ip by remember { mutableStateOf(saved.firstOrNull()?.ip ?: "") }
    var port by remember { mutableStateOf(saved.firstOrNull()?.port?.toString() ?: "5555") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Укажи IP телевизора с включённой ADB-отладкой по сети.") }
    var report by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        val host = ip.trim()
        val adbPort = port.toIntOrNull()
        if (host.isBlank() || adbPort == null || adbPort !in 1..65535) {
            status = "Проверь IP-адрес и порт ADB."
            return
        }
        scope.launch {
            busy = true
            report = ""
            status = "Подключаемся и собираем показатели…"
            try {
                val data = withContext(Dispatchers.IO) {
                    Dadb.create(host, adbPort, diagnosticsKeyPair(context)).use { adb ->
                        val model = adb.shell("getprop ro.product.manufacturer").output.trim() + " " +
                            adb.shell("getprop ro.product.model").output.trim()
                        val android = adb.shell("getprop ro.build.version.release").output.trim()
                        val mem = adb.shell("cat /proc/meminfo").output
                        val disk = adb.shell("df -h /data").output
                        val uptime = adb.shell("uptime").output
                        val cpu = adb.shell("top -bn1").output
                        val thermal = adb.shell("dumpsys thermalservice").output
                        listOf("Телевизор: " + model.trim(), "Android: " + android,
                            "ОПЕРАТИВНАЯ ПАМЯТЬ\\n" + mem,
                            "ВНУТРЕННЯЯ ПАМЯТЬ\\n" + disk,
                            "ЗАГРУЗКА CPU\\n" + cpu,
                            "ВРЕМЯ РАБОТЫ\\n" + uptime,
                            "ТЕМПЕРАТУРНЫЕ ДАТЧИКИ\\n" + thermal)
                    }
                }
                report = data.joinToString("\\n\\n")
                status = "Диагностика получена."
                mergeSavedTvAppsDevices(context, saved + TvDevice("Android TV", host, adbPort))
            } catch (e: Exception) {
                status = "Не удалось получить диагностику: " + (e.message ?: e.javaClass.simpleName)
            } finally { busy = false }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Диагностика ТВ") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("← Назад") }
        }, actions = { TextButton(onClick = { refresh() }, enabled = !busy) { Text("Обновить") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            saved.forEach { device ->
                OutlinedButton(onClick = { ip = device.ip; port = device.port.toString() },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(device.name + " · " + device.ip + ":" + device.port)
                }
            }
            OutlinedTextField(value = ip, onValueChange = { ip = it },
                label = { Text("IP-адрес телевизора") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Порт ADB") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { refresh() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Собираем данные…" else "Проверить телевизор")
            }
            Text(status)
            if (report.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(report, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                }
                Text("Температура зависит от модели и прошивки. Если система скрывает датчики, команда может не вернуть показания.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
""", encoding="utf-8")
print("Android TV diagnostics screen and main-menu route added.")
