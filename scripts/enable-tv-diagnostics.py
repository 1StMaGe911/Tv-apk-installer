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
screen.write_text("""
package ru.tvapk.installer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun diagnosticsKeyPair(context: android.content.Context): AdbKeyPair {
    val dir = File(context.filesDir, "adb")
    val priv = File(dir, "adbkey")
    val pub = File(dir, "adbkey.pub")
    if (!priv.exists() || !pub.exists()) { dir.mkdirs(); AdbKeyPair.generate(priv, pub) }
    return AdbKeyPair.read(priv, pub)
}

private data class TvDiagnostic(
    val model: String, val android: String, val ramUsed: Long?, val ramTotal: Long?,
    val storageUsed: Long?, val storageTotal: Long?, val cpu: Int?, val uptime: String,
    val temperature: String?
)

private fun parseMemory(raw: String): Pair<Long?, Long?> {
    fun value(key: String) = Regex("(?m)^$key:\\s+(\\d+)\\s+kB").find(raw)?.groupValues?.get(1)?.toLongOrNull()
    val total = value("MemTotal")
    val available = value("MemAvailable") ?: listOfNotNull(value("MemFree"), value("Buffers"), value("Cached")).sum().takeIf { it > 0 }
    return (if (total != null && available != null) (total - available).coerceAtLeast(0) else null) to total
}

private fun parseStorage(raw: String): Pair<Long?, Long?> {
    val row = raw.lines().lastOrNull { it.trim().startsWith("/") } ?: return null to null
    val parts = row.trim().split(Regex("\\s+"))
    if (parts.size < 4) return null to null
    fun size(value: String): Long? {
        val number = Regex("([0-9.]+)").find(value)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val unit = value.lastOrNull()?.uppercaseChar()
        val multiplier = when (unit) { 'K' -> 1.0; 'M' -> 1024.0; 'G' -> 1024.0 * 1024; 'T' -> 1024.0 * 1024 * 1024; else -> 1.0 }
        return (number * multiplier).toLong()
    }
    return size(parts[2]) to size(parts[0])
}

private fun parseCpu(raw: String): Int? {
    val idle = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*%?\\s*(?:id|idle)").find(raw)?.groupValues?.get(1)?.toFloatOrNull()
    if (idle != null) return (100f - idle).toInt().coerceIn(0, 100)
    val used = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*%\\s*(?:usr|user)").find(raw)?.groupValues?.get(1)?.toFloatOrNull()
    return used?.toInt()?.coerceIn(0, 100)
}

private fun formatMb(mb: Long?): String {
    if (mb == null) return "—"
    return if (mb >= 1024) String.format(Locale.getDefault(), "%.1f ГБ", mb / 1024.0) else "$mb МБ"
}

private fun formatUptime(raw: String): String {
    val seconds = raw.trim().substringBefore(" ").toDoubleOrNull()?.toLong() ?: return "Нет данных"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return if (days > 0) "$days дн. $hours ч." else if (hours > 0) "$hours ч. $minutes мин." else "$minutes мин."
}

private fun parseTemperature(raw: String): String? {
    val values = Regex("(?i)(?:mValue=|temp(?:erature)?\\s*[:=]\\s*)(-?[0-9]+(?:\\.[0-9]+)?)")
        .findAll(raw).mapNotNull { it.groupValues[1].toFloatOrNull() }.filter { it in 0f..150f }.toList()
    val max = values.maxOrNull() ?: return null
    return String.format(Locale.getDefault(), "%.1f °C", max)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { loadSavedTvAppsDevices(context) }
    var ip by remember { mutableStateOf(saved.firstOrNull()?.ip ?: "") }
    var port by remember { mutableStateOf(saved.firstOrNull()?.port?.toString() ?: "5555") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Подключись к телевизору, чтобы посмотреть его состояние.") }
    var diagnostic by remember { mutableStateOf<TvDiagnostic?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        val host = ip.trim()
        val adbPort = port.toIntOrNull()
        if (host.isBlank() || adbPort == null || adbPort !in 1..65535) { status = "Проверь IP-адрес и порт ADB."; return }
        scope.launch {
            busy = true
            status = "Собираем показатели телевизора…"
            diagnostic = null
            try {
                val data = withContext(Dispatchers.IO) {
                    Dadb.create(host, adbPort, diagnosticsKeyPair(context)).use { adb ->
                        val model = (adb.shell("getprop ro.product.manufacturer").output.trim() + " " +
                            adb.shell("getprop ro.product.model").output.trim()).trim().ifBlank { "Android TV" }
                        val android = adb.shell("getprop ro.build.version.release").output.trim().ifBlank { "Неизвестно" }
                        val memory = parseMemory(adb.shell("cat /proc/meminfo").output)
                        val storage = parseStorage(adb.shell("df -k /data").output)
                        val cpu = parseCpu(adb.shell("top -bn1").output)
                        val uptime = formatUptime(adb.shell("cat /proc/uptime").output)
                        val temperature = parseTemperature(adb.shell("dumpsys thermalservice").output)
                        TvDiagnostic(model, android, memory.first, memory.second, storage.first, storage.second, cpu, uptime, temperature)
                    }
                }
                diagnostic = data
                status = "Данные успешно обновлены"
                mergeSavedTvAppsDevices(context, saved + TvDevice("Android TV", host, adbPort))
            } catch (e: Exception) {
                status = "Не удалось подключиться. Проверь ADB и IP-адрес."
            } finally { busy = false }
        }
    }

    val d = diagnostic
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Диагностика ТВ", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            actions = { TextButton(onClick = { refresh() }, enabled = !busy) { Text("Обновить") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("СОСТОЯНИЕ УСТРОЙСТВА", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(d?.model ?: "Ваш телевизор", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(if (d != null) "Android " + d.android + "  ·  " + ip else status,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (busy) { Spacer(Modifier.height(4.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
            }

            if (d == null) {
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Подключение к ТВ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        saved.forEach { device ->
                            OutlinedButton(onClick = { ip = device.ip; port = device.port.toString() },
                                enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(device.name + "  ·  " + device.ip + ":" + device.port)
                            }
                        }
                        OutlinedTextField(value = ip, onValueChange = { ip = it },
                            label = { Text("IP-адрес телевизора") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text("Порт ADB") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { refresh() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "Подключаемся…" else "Проверить телевизор")
                        }
                    }
                }
            } else {
                Text("РЕСУРСЫ", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiagnosticMetric("Оперативная память", formatMb(d.ramUsed), d.ramUsed, d.ramTotal, Modifier.weight(1f))
                    DiagnosticMetric("Внутренняя память", formatMb(d.storageUsed), d.storageUsed, d.storageTotal, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiagnosticSimpleMetric("Процессор", d.cpu?.let { "$it%" } ?: "Нет данных",
                        d.cpu?.let { if (it >= 85) "Высокая нагрузка" else if (it >= 60) "Средняя нагрузка" else "Низкая нагрузка" } ?: "Показатель недоступен",
                        Modifier.weight(1f))
                    DiagnosticSimpleMetric("Температура", d.temperature ?: "Нет данных",
                        if (d.temperature != null) "Данные датчика ТВ" else "Датчик не предоставлен",
                        Modifier.weight(1f))
                }
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Время работы", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(d.uptime, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Text("◷", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { refresh() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Обновляем…" else "Обновить показатели")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DiagnosticMetric(title: String, value: String, used: Long?, total: Long?, modifier: Modifier = Modifier) {
    val fraction = if (used != null && total != null && total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val available = used != null && total != null && total > 0
    val color = when { fraction >= 0.9f -> MaterialTheme.colorScheme.error; fraction >= 0.75f -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }
    Card(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (available) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = color)
                Text((fraction * 100).toInt().toString() + "% занято", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("Нет данных", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticSimpleMetric(title: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
        }
    }
}

""", encoding="utf-8")
print("Android TV diagnostics screen and main-menu route added.")
