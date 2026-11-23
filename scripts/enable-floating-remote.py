#!/usr/bin/env python3
"""Add a draggable, edge-docking floating remote control to the TV APK Installer UI."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/java/ru/tvapk/installer/MainActivity.kt"
data = source.read_text(encoding="utf-8")

def replace_once(old: str, new: str) -> None:
    global data
    if new in data:
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence: {old!r}; found {count}")
    data = data.replace(old, new, 1)

replace_once(
    "import androidx.compose.foundation.clickable\n",
    """import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
""",
)

replace_once(
    "    var showRemoteControl by remember { mutableStateOf(false) }\n",
    """    var showRemoteControl by remember { mutableStateOf(false) }
    var showFloatingRemote by remember {
        mutableStateOf(settings.getBoolean("floating_remote_visible", true))
    }
""",
)

replace_once(
    """                    NavigationDrawerItem(label = { Text("Пульт TV") }, selected = activeSection == "remote",
                        icon = { Text("🎮") },
                        onClick = { showSettings = false; activeSection = "remote"; drawerScope.launch { drawerState.close() } })""",
    """                    NavigationDrawerItem(label = { Text("Пульт TV") }, selected = activeSection == "remote",
                        icon = { Text("🎮") },
                        onClick = {
                            showFloatingRemote = true
                            settings.edit().putBoolean("floating_remote_visible", true).apply()
                            showSettings = false
                            activeSection = "remote"
                            drawerScope.launch { drawerState.close() }
                        })""",
)

start_marker = "                if (showRemoteControl) {"
end_marker = """

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    )
                ) {"""
start = data.find(start_marker)
end = data.find(end_marker, start + len(start_marker))
if start < 0 or end < 0:
    raise SystemExit("Could not locate the existing remote control panel.")
block = data[start:end]
block = block.replace(
    """                if (showRemoteControl) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {""",
    """                if (showRemoteControl) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showRemoteControl = false }
                    ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.92f),
                        colors = CardDefaults.cardColors(containerColor = Panel)
                    ) {""",
    1,
)
block = block.replace(
    "Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {",
    "Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {",
    1,
)
block = block.replace(
    """                            Text("ПУЛЬТ УПРАВЛЕНИЯ", color = GoldLight, style = MaterialTheme.typography.titleMedium)""",
    """                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "ПУЛЬТ УПРАВЛЕНИЯ",
                                    modifier = Modifier.weight(1f),
                                    color = GoldLight,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                TextButton(onClick = {
                                    showRemoteControl = false
                                    showFloatingRemote = false
                                    settings.edit().putBoolean("floating_remote_visible", false).apply()
                                }) { Text("Скрыть кружок") }
                            }""",
    1,
)
closing = "                        }\n                    }\n                }"
if not block.endswith(closing):
    raise SystemExit("Unexpected end of the existing remote control panel.")
block = block[:-len(closing)] + "                        }\n                    }\n                    }\n                }"
data = data[:start] + block + data[end:]

replace_once(
    "        Scaffold(containerColor = Bg) { padding ->",
    "        Box(Modifier.fillMaxSize()) {\n        Scaffold(containerColor = Bg) { padding ->",
)

tail = "            }\n        }\n        }\n        }\n    }\n}"
if not data.rstrip().endswith(tail):
    raise SystemExit("Could not locate the end of TvInstallerApp for the floating overlay.")
trailing_newline = "\n" if data.endswith("\n") else ""
data = data.rstrip()
data = data[:-len(tail)] + """            }
        }
        }
        FloatingRemoteBubble(
            settings = settings,
            visible = showFloatingRemote,
            onClick = {
                if (ip.isBlank()) {
                    showSettings = false
                    activeSection = "remote"
                } else {
                    showRemoteControl = true
                }
            }
        )
        }
        }
    }
}""" + trailing_newline

helper = r'''

@Composable
private fun FloatingRemoteBubble(
    settings: android.content.SharedPreferences,
    visible: Boolean,
    onClick: () -> Unit
) {
    if (!visible) return

    val density = androidx.compose.ui.platform.LocalDensity.current
    var savedX by remember { mutableStateOf(settings.getFloat("floating_remote_x", Float.NaN)) }
    var savedY by remember { mutableStateOf(settings.getFloat("floating_remote_y", Float.NaN)) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bubblePx = with(density) { 54.dp.toPx() }
        val visibleAtEdgePx = with(density) { 16.dp.toPx() }
        val maxX = (constraints.maxWidth.toFloat() - bubblePx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight.toFloat() - bubblePx).coerceAtLeast(0f)
        val minX = -bubblePx + visibleAtEdgePx
        val maxDockX = maxX + bubblePx - visibleAtEdgePx
        val currentX = (if (savedX.isFinite()) savedX else maxX).coerceIn(minX, maxDockX)
        val currentY = (if (savedY.isFinite()) savedY else maxY * 0.45f).coerceIn(0f, maxY)

        Surface(
            modifier = Modifier
                .offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
                .size(54.dp)
                .zIndex(20f)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            val dragX = if (savedX.isFinite()) savedX else currentX
                            savedX = if (dragX + bubblePx / 2f >= maxX / 2f) {
                                maxDockX
                            } else {
                                minX
                            }
                            savedY = (if (savedY.isFinite()) savedY else currentY).coerceIn(0f, maxY)
                            settings.edit()
                                .putFloat("floating_remote_x", savedX)
                                .putFloat("floating_remote_y", savedY)
                                .apply()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dragStartX = if (savedX.isFinite()) savedX else currentX
                            val dragStartY = if (savedY.isFinite()) savedY else currentY
                            savedX = (dragStartX + dragAmount.x).coerceIn(minX, maxDockX)
                            savedY = (dragStartY + dragAmount.y).coerceIn(0f, maxY)
                        }
                    )
                }
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = Color(0xFFD6A84F),
            contentColor = Color(0xFF171006),
            shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF4D58A))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("🎮", fontSize = 22.sp)
            }
        }
    }
}
'''
if "private fun FloatingRemoteBubble(" not in data:
    data += helper

source.write_text(data, encoding="utf-8")
print("Floating remote control enabled.")
