#!/usr/bin/env python3
"""Extend TV diagnostics with network health and an overall device assessment."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
screen = root / "app/src/main/java/ru/tvapk/installer/TvDiagnosticsScreen.kt"

def replace_once(old: str, new: str) -> None:
    source = screen.read_text(encoding="utf-8")
    if new in source:
        return
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one diagnostics anchor, found {count}: {old[:100]!r}")
    screen.write_text(source.replace(old, new), encoding="utf-8")

replace_once(
'''    val temperature: String?
)''',
'''    val temperature: String?,
    val networkType: String, val networkIp: String, val internetAvailable: Boolean,
    val latencyMs: Long?
)'''
)

replace_once(
'''private fun parseTemperature(raw: String): String? {''',
'''private data class NetworkInfo(val type: String, val ip: String, val internet: Boolean, val latencyMs: Long?)

private fun parseNetwork(routeRaw: String, addressesRaw: String, pingRaw: String): NetworkInfo {
    val route = routeRaw.lineSequence().firstOrNull { it.trim().startsWith("default ") }.orEmpty()
    val iface = Regex("\\\\bdev\\\\s+(\\\\S+)").find(route)?.groupValues?.get(1).orEmpty()
    val type = when {
        iface.startsWith("wlan") || iface.startsWith("wifi") -> "Wi-Fi"
        iface.startsWith("eth") || iface.startsWith("en") -> "Ethernet"
        iface.isNotBlank() -> iface
        else -> "Нет подключения"
    }
    val ip = addressesRaw.lineSequence().mapNotNull { line ->
        Regex("\\\\binet\\\\s+(\\\\d{1,3}(?:\\\\.\\\\d{1,3}){3})/").find(line)?.groupValues?.get(1)
    }.firstOrNull { it != "127.0.0.1" }.orEmpty()
    val latency = Regex("time[=<]([0-9]+(?:\\\\.[0-9]+)?)\\\\s*ms", RegexOption.IGNORE_CASE)
        .find(pingRaw)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
    val internet = pingRaw.contains("bytes from") || pingRaw.contains("1 received") ||
        pingRaw.contains("1 packets received") || latency != null
    return NetworkInfo(type, ip.ifBlank { "IP не определён" }, internet, latency)
}

private fun overallAssessment(d: TvDiagnostic): Pair<String, String> {
    val issues = mutableListOf<String>()
    val critical = mutableListOf<String>()
    val ramPercent = if (d.ramUsed != null && d.ramTotal != null && d.ramTotal > 0) d.ramUsed * 100 / d.ramTotal else null
    val storagePercent = if (d.storageUsed != null && d.storageTotal != null && d.storageTotal > 0) d.storageUsed * 100 / d.storageTotal else null
    if (d.cpu != null && d.cpu >= 95) critical += "высокая загрузка CPU" else if (d.cpu != null && d.cpu >= 85) issues += "повышенная загрузка CPU"
    if (ramPercent != null && ramPercent >= 95) critical += "почти закончилась RAM" else if (ramPercent != null && ramPercent >= 85) issues += "мало свободной RAM"
    if (storagePercent != null && storagePercent >= 95) critical += "почти заполнено хранилище" else if (storagePercent != null && storagePercent >= 85) issues += "мало свободного места"
    val temperature = d.temperature?.replace(',', '.')?.let { Regex("-?[0-9]+(?:\\\\.[0-9]+)?").find(it)?.value?.toFloatOrNull() }
    if (temperature != null && temperature >= 85f) critical += "высокая температура" else if (temperature != null && temperature >= 75f) issues += "повышенная температура"
    if (d.networkType == "Нет подключения") critical += "нет сетевого подключения"
    else if (!d.internetAvailable) issues += "интернет недоступен"
    return when {
        critical.isNotEmpty() -> "Критично" to critical.joinToString(", ")
        issues.isNotEmpty() -> "Есть проблемы" to issues.joinToString(", ")
        else -> "Отлично" to "Критических отклонений не обнаружено"
    }
}

private fun parseTemperature(raw: String): String? {'''
)

replace_once(
'''                            val temperature = parseTemperature(adb.shell("dumpsys thermalservice").output)
                            val data = TvDiagnostic(model, android, memory.first, memory.second, storage.first, storage.second, cpu, cpuFrequency, uptime, temperature)''',
'''                            val temperature = parseTemperature(adb.shell("dumpsys thermalservice").output)
                            val route = adb.shell("ip route 2>/dev/null").output
                            val addresses = adb.shell("ip -o -4 addr show scope global 2>/dev/null").output
                            val ping = adb.shell("ping -c 1 -W 2 1.1.1.1 2>/dev/null").output
                            val network = parseNetwork(route, addresses, ping)
                            val data = TvDiagnostic(model, android, memory.first, memory.second, storage.first, storage.second, cpu, cpuFrequency, uptime, temperature,
                                network.type, network.ip, network.internet, network.latencyMs)'''
)
replace_once(
'''                Text("РЕСУРСЫ", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)''',
'''                val assessment = overallAssessment(d)
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ОБЩАЯ ОЦЕНКА СОСТОЯНИЯ", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(assessment.first, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = if (assessment.first == "Отлично") Color(0xFF16865B) else if (assessment.first == "Критично") MaterialTheme.colorScheme.error else Color(0xFFB7791F))
                        Text(assessment.second, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("СОСТОЯНИЕ СЕТИ", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(d.networkType, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("IP-адрес: " + d.networkIp, style = MaterialTheme.typography.bodyMedium)
                        Text(if (d.internetAvailable) "Интернет доступен" else "Нет доступа к интернету",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (d.internetAvailable) Color(0xFF16865B) else MaterialTheme.colorScheme.error)
                        Text("Задержка: " + (d.latencyMs?.let { "$it мс" } ?: "нет данных"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("РЕСУРСЫ", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)'''
)

print("Network diagnostics and overall TV health assessment added.")
