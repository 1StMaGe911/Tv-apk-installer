#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
legacy = root / "app/src/main/java/ru/tvapk/installer/MainActivity.kt"
app_root = root / "vendor/cleanyourtv-app/app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"
remote = root / "app/src/main/java/ru/tvapk/installer/FloatingRemoteControl.kt"
def replace_once(path, old, new):
    data=path.read_text(encoding="utf-8")
    if new in data: return
    if data.count(old)!=1: raise SystemExit(f"Expected one match in {path}: {old!r}; found {data.count(old)}")
    path.write_text(data.replace(old,new,1),encoding="utf-8")
replace_once(legacy,"private fun connectWithRetry(","internal fun connectWithRetry(")
replace_once(legacy,"private fun sendTvKey(","internal fun sendTvKey(")
replace_once(app_root,"import tv.cleanyour.feature.setup.SetupFlow","import tv.cleanyour.feature.setup.SetupFlow\nimport ru.tvapk.installer.FloatingRemoteControlOverlay")
replace_once(app_root,"        // R5-W8 O2: a Manage write that paused left its run open;","        FloatingRemoteControlOverlay()\n        // R5-W8 O2: a Manage write that paused left its run open;")
remote.write_text(r'''package ru.tvapk.installer

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun FloatingRemoteControlOverlay() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tv_installer", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(prefs.getBoolean("floating_remote_visible", true)) }
    var panelOpen by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(prefs.getString("ip", "").orEmpty()) }
    var port by remember { mutableStateOf(prefs.getString("port", "5555").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var x by remember { mutableStateOf(prefs.getFloat("floating_remote_x", Float.NaN)) }
    var y by remember { mutableStateOf(prefs.getFloat("floating_remote_y", Float.NaN)) }

    fun send(keyCode: Int, label: String) {
        if (busy) return
        val target = host.trim()
        val targetPort = port.toIntOrNull() ?: 5555
        if (target.isBlank() || targetPort !in 1..65535) {
            status = "Укажи IP телевизора и порт ADB (обычно 5555)"
            panelOpen = true
            return
        }
        busy = true
        status = "Отправляю команду…"
        prefs.edit().putString("ip", target).putString("port", targetPort.toString()).apply()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    connectWithRetry(context, target, targetPort) { adb ->
                        sendTvKey(adb, keyCode)
                        label
                    }
                }
            }
            status = result.getOrElse { "Ошибка: \${it.message ?: "не удалось подключиться"}" }
            busy = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val bubbleSize = with(density) { 54.dp.toPx() }
        val peek = with(density) { 15.dp.toPx() }
        val maxX = (constraints.maxWidth.toFloat() - bubbleSize).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight.toFloat() - bubbleSize).coerceAtLeast(0f)
        val leftDock = -bubbleSize + peek
        val rightDock = maxX + bubbleSize - peek
        val currentX = (if (x.isFinite()) x else maxX).coerceIn(leftDock, rightDock)
        val currentY = (if (y.isFinite()) y else maxY * 0.45f).coerceIn(0f, maxY)
        if (visible) {
            Surface(
                modifier = Modifier.offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
                    .size(54.dp).zIndex(100f)
                    .pointerInput(maxX, maxY) {
                        detectDragGestures(
                            onDragEnd = {
                                x = if (currentX + bubbleSize / 2f >= maxX / 2f) rightDock else leftDock
                                y = currentY
                                prefs.edit().putFloat("floating_remote_x", x).putFloat("floating_remote_y", y).apply()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                x = ((if (x.isFinite()) x else currentX) + amount.x).coerceIn(leftDock, rightDock)
                                y = ((if (y.isFinite()) y else currentY) + amount.y).coerceIn(0f, maxY)
                            }
                        )
                    }.clickable { panelOpen = true },
                shape = CircleShape, color = Color(0xFFD6A84F), contentColor = Color(0xFF171006),
                shadowElevation = 12.dp, border = BorderStroke(1.dp, Color(0xFFF4D58A))
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("🎮", fontSize = 23.sp) }
            }
        }
    }

    if (panelOpen) Dialog(onDismissRequest = { panelOpen = false }) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            color = Color(0xFF20242D), contentColor = Color.White) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ПУЛЬТ ТЕЛЕВИЗОРА", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Color(0xFFF4D58A))
                    TextButton(onClick = { panelOpen = false }) { Text("Закрыть") }
                }
                TextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("IP телевизора") })
                TextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Порт ADB") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { RemoteKey("▲", busy) { send(19, "Вверх") } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteKey("◀", busy, Modifier.weight(1f)) { send(21, "Влево") }
                    RemoteKey("OK", busy, Modifier.weight(1f), true) { send(23, "OK") }
                    RemoteKey("▶", busy, Modifier.weight(1f)) { send(22, "Вправо") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { RemoteKey("▼", busy) { send(20, "Вниз") } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteKey("Назад", busy, Modifier.weight(1f)) { send(4, "Назад") }
                    RemoteKey("Домой", busy, Modifier.weight(1f)) { send(3, "Домой") }
                    RemoteKey("Меню", busy, Modifier.weight(1f)) { send(82, "Меню") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteKey("Громкость −", busy, Modifier.weight(1f)) { send(25, "Громкость −") }
                    RemoteKey("Громкость +", busy, Modifier.weight(1f)) { send(24, "Громкость +") }
                    RemoteKey("Mute", busy, Modifier.weight(1f)) { send(164, "Звук переключён") }
                }
                if (status.isNotBlank()) Text(status, color = if (status.startsWith("Ошибка")) Color(0xFFFF8888) else Color.LightGray, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        visible = false
                        prefs.edit().putBoolean("floating_remote_visible", false).apply()
                        panelOpen = false
                    }) { Text("Скрыть кружок") }
                    Button(onClick = {
                        prefs.edit().putString("ip", host.trim()).putString("port", port.trim()).apply()
                        status = "Адрес сохранён"
                    }) { Text("Сохранить IP") }
                }
            }
        }
    }
}

@Composable
private fun RemoteKey(label: String, busy: Boolean, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    if (primary) Button(onClick, enabled = !busy, modifier = modifier.height(46.dp)) { Text(label) }
    else OutlinedButton(onClick, enabled = !busy, modifier = modifier.height(46.dp)) { Text(label) }
}
''',encoding="utf-8")
print("Floating remote overlay installed.")
