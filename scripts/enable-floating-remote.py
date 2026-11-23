#!/usr/bin/env python3
"""Use Clean Your TV's selected, trusted ADB session for the floating remote."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
upstream = root / "vendor/cleanyourtv-app"
app_root = upstream / "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"
remote = root / "app/src/main/java/ru/tvapk/installer/FloatingRemoteControl.kt"

def replace_once(path, old, new):
    data = path.read_text(encoding="utf-8")
    if new in data:
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit("Expected one match in " + str(path) + ": " + repr(old) + "; found " + str(count))
    path.write_text(data.replace(old, new, 1), encoding="utf-8")

# The selected TV ID follows the actual Manage selection and any TV-specific screen.
app_root_text = app_root.read_text(encoding="utf-8")
if "import ru.tvapk.installer.FloatingRemoteControlOverlay" not in app_root_text:
    anchor = "import tv.cleanyour.feature.setup.SetupFlow\n"
    if app_root_text.count(anchor) != 1:
        raise SystemExit("Could not locate AppRoot import anchor for floating remote")
    app_root.write_text(app_root_text.replace(anchor, anchor + "import ru.tvapk.installer.FloatingRemoteControlOverlay\n", 1), encoding="utf-8")
replace_once(
    app_root,
    "    val scope = rememberCoroutineScope()\n",
    "    val scope = rememberCoroutineScope()\n    var remoteTvId by remember { mutableStateOf<String?>(null) }\n",
)
replace_once(
    app_root,
    "                    val m = rememberMachine(\"manage:${key.tvId}\") { graph.manage(it, key.tvId?.let(::TvId)) }\n                    val state by m.state.collectAsStateWithLifecycle()",
    "                    val m = rememberMachine(\"manage:${key.tvId}\") { graph.manage(it, key.tvId?.let(::TvId)) }\n                    val state by m.state.collectAsStateWithLifecycle()\n                    LaunchedEffect(state.current?.id) { remoteTvId = state.current?.id?.value }",
)
replace_once(
    app_root,
    "    Box(Modifier.fillMaxSize()) {\n        NavDisplay(",
    """    val remoteTargetId = when (val current = backStack.lastOrNull()) {
        is Dest.Manage -> remoteTvId ?: current.tvId
        is Dest.Home -> current.tvId
        is Dest.Customise -> current.tvId
        is Dest.Modules -> current.tvId
        is Dest.Restore -> current.tvId
        is Dest.TvSettings -> current.tvId
        is Dest.SwitchedOff -> current.tvId
        is Dest.Leftovers -> current.tvId
        is Dest.Setup -> current.tvId ?: remoteTvId
        is Dest.Report -> current.tvId ?: remoteTvId
        else -> remoteTvId
    }
    Box(Modifier.fillMaxSize()) {
        NavDisplay(""",
)
replace_once(
    app_root,
    "        // R5-W8 O2: a Manage write that paused left its run open;",
    "        FloatingRemoteControlOverlay(graph, remoteTargetId)\n        // R5-W8 O2: a Manage write that paused left its run open;",
)

remote.write_text(r'''package ru.tvapk.installer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.cleanyour.app.AppGraph
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.session.InstallOutcome
import tv.cleanyour.core.session.ExecSpec
import kotlin.math.roundToInt

@OptIn(RawShell::class)
@Composable
fun FloatingRemoteControlOverlay(graph: AppGraph, selectedTvId: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("tv_installer", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var tucked by remember { mutableStateOf(prefs.getBoolean("floating_remote_tucked", false)) }
    var panelOpen by remember { mutableStateOf(false) }
    var keyboardMode by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(prefs.getString("remote_keyboard_language", "RU") ?: "RU") }
    var symbols by remember { mutableStateOf(false) }
    var shifted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var imeReady by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var x by remember { mutableStateOf(prefs.getFloat("floating_remote_x", Float.NaN)) }
    var y by remember { mutableStateOf(prefs.getFloat("floating_remote_y", Float.NaN)) }

    fun runShell(command: String, label: String) {
        if (busy) return
        val id = selectedTvId?.let { runCatching { TvId(it) }.getOrNull() }
        if (id == null) {
            status = "Сначала выбери телевизор в Clean Your TV"
            panelOpen = true
            return
        }
        busy = true
        status = "Отправляю на выбранный телевизор…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // Reuse the app's own trusted session; if it has gone idle, quietly reconnect
                    // to this exact saved TV identity. No second IP entry or independent pairing.
                    val session = graph.access.live(id) ?: graph.access.quiet(id)
                        ?: throw IllegalStateException("Телевизор сейчас не подключён. Открой его в Clean Your TV и повтори.")
                    val hold = session.hold()
                    try {
                        val reply = session.exec(ShellLine(command), ExecSpec(timeoutMs = 6000, idleTimeoutMs = 4000))
                        if (reply.exit != 0) throw IllegalStateException(reply.stderr.ifBlank { "Команда отклонена телевизором" })
                    } finally {
                        hold.close()
                    }
                }
            }
            status = result.fold({ label }, { "Ошибка: " + (it.message ?: "не удалось отправить команду") })
            busy = false
        }
    }

    fun key(code: Int, label: String) = runShell("input keyevent " + code, label)
    fun quoteShell(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun prepareIme() {
        if (busy || imeReady) return
        val id = selectedTvId?.let { runCatching { TvId(it) }.getOrNull() }
        if (id == null) {
            status = "Сначала выбери телевизор в Clean Your TV"
            return
        }
        busy = true
        status = "Подготавливаю Unicode-клавиатуру на телевизоре…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val session = graph.access.live(id) ?: graph.access.quiet(id)
                        ?: error("Телевизор сейчас не подключён")
                    val hold = session.hold()
                    try {
                        suspend fun checked(command: String): String {
                            val reply = session.exec(ShellLine(command), ExecSpec(timeoutMs = 15000, idleTimeoutMs = 8000))
                            if (reply.exit != 0) error(reply.stderr.ifBlank { reply.stdout.ifBlank { "Команда отклонена: $command" } })
                            return reply.stdout
                        }
                        val component = "tv.cleanyour.app/.remote.RemoteInputMethodService"
                        val installedImes = checked("ime list -a")
                        if (!installedImes.contains(component)) {
                            val apk = File(context.applicationInfo.sourceDir)
                            if (!apk.isFile || apk.length() <= 0L) error("Не удалось прочитать APK приложения на телефоне")
                            val digest = MessageDigest.getInstance("SHA-256")
                            FileInputStream(apk).use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    digest.update(buffer, 0, count)
                                }
                            }
                            val sha = digest.digest().joinToString("") { "%02x".format(it) }
                            val blob = graph.blobs.put(Sha256(sha), FileInputStream(apk), apk.length())
                            when (val outcome = session.installStream(blob, blob.size)) {
                                InstallOutcome.Installed -> Unit
                                is InstallOutcome.Failed -> error("Не удалось установить клавиатуру: " + outcome.detail)
                            }
                        }
                        val previous = checked("settings get secure default_input_method").trim()
                        if (previous.isNotBlank() && previous != "null" && previous != component) {
                            prefs.edit().putString("remote_ime_previous_" + id.value, previous).apply()
                        }
                        checked("ime enable " + component)
                        checked("ime set " + component)
                    } finally {
                        hold.close()
                    }
                }
            }
            result.fold(
                { imeReady = true; status = "Unicode-клавиатура подключена. Можно печатать по-русски и по-английски." },
                { status = "Ошибка клавиатуры: " + (it.message ?: "не удалось подготовить ввод") }
            )
            busy = false
        }
    }

    fun restoreIme() {
        val id = selectedTvId?.let { runCatching { TvId(it) }.getOrNull() } ?: return
        val previous = prefs.getString("remote_ime_previous_" + id.value, null) ?: return
        if (previous.isBlank() || previous == "null") return
        runShell("ime set " + quoteShell(previous), "Восстановлена клавиатура телевизора")
        prefs.edit().remove("remote_ime_previous_" + id.value).apply()
        imeReady = false
    }

    fun typeText(value: String) {
        if (!imeReady) {
            status = "Сначала включи клавиатуру и дождись её подключения"
            return
        }
        val encoded = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val command = "am broadcast -n tv.cleanyour.app/.remote.RemoteInputReceiver -a tv.cleanyour.app.REMOTE_INPUT_TEXT --es payload " + quoteShell(encoded)
        runShell(command, "Введено: " + value)
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
        Surface(
            modifier = Modifier.offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
                .size(54.dp).zIndex(100f)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            // A normal drag must preserve the exact visible position.
                            // Docking is reserved for the explicit "Скрыть к краю" action.
                            x = (if (x.isFinite()) x else currentX).coerceIn(0f, maxX)
                            y = (if (y.isFinite()) y else currentY).coerceIn(0f, maxY)
                            prefs.edit().putFloat("floating_remote_x", x).putFloat("floating_remote_y", y).apply()
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            x = ((if (x.isFinite()) x else currentX) + amount.x).coerceIn(leftDock, rightDock)
                            y = ((if (y.isFinite()) y else currentY) + amount.y).coerceIn(0f, maxY)
                        }
                    )
                }
                .clickable {
                    if (tucked) {
                        tucked = false
                        x = if (currentX < 0f) 0f else maxX
                        prefs.edit().putBoolean("floating_remote_tucked", false)
                            .putFloat("floating_remote_x", x).apply()
                    } else {
                        panelOpen = true
                    }
                },
            shape = if (tucked) RoundedCornerShape(14.dp) else CircleShape,
            color = if (tucked) Color(0xFFB98A38) else Color(0xFFD6A84F),
            contentColor = Color(0xFF171006),
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, Color(0xFFF4D58A))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!tucked) Text("🎮", fontSize = 23.sp)
            }
        }


    if (panelOpen) Dialog(onDismissRequest = {
        restoreIme()
        panelOpen = false
        tucked = true
        x = if (currentBubbleIsRight(x, maxX, rightDock)) rightDock else leftDock
        prefs.edit().putBoolean("floating_remote_tucked", true)
            .putFloat("floating_remote_x", x).putFloat("floating_remote_y", currentY).apply()
    }) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = Color(0xFF20242D),
            contentColor = Color.White
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ПУЛЬТ ТВ", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Color(0xFFF4D58A))
                    TextButton(onClick = {
                        restoreIme()
                        panelOpen = false
                        tucked = true
                        x = if (currentBubbleIsRight(x, maxX, rightDock)) rightDock else leftDock
                        prefs.edit().putBoolean("floating_remote_tucked", true)
                            .putFloat("floating_remote_x", x).putFloat("floating_remote_y", currentY).apply()
                    }) { Text("Закрыть") }
                }
                Text(
                    selectedTvId?.let { "Управление выбранным телевизором" } ?: "Телевизор не выбран",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!keyboardMode) Button(onClick = {
                        keyboardMode = true
                        prepareIme()
                    }, Modifier.weight(1f)) { Text("Клавиатура") }
                    else Button(onClick = {
                        restoreIme()
                        keyboardMode = false
                    }, Modifier.weight(1f)) { Text("Кнопки пульта") }
                    TextButton(onClick = {
                        language = if (language == "RU") "EN" else "RU"
                        prefs.edit().putString("remote_keyboard_language", language).apply()
                        symbols = false
                        shifted = false
                    }, enabled = keyboardMode) { Text(language) }
                }

                if (!keyboardMode) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        RemoteKey("▲", busy) { key(19, "Вверх") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemoteKey("◀", busy, Modifier.weight(1f)) { key(21, "Влево") }
                        RemoteKey("OK", busy, Modifier.weight(1f), true) { key(23, "OK") }
                        RemoteKey("▶", busy, Modifier.weight(1f)) { key(22, "Вправо") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        RemoteKey("▼", busy) { key(20, "Вниз") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteKey("Назад", busy, Modifier.weight(1f)) { key(4, "Назад") }
                        RemoteKey("Домой", busy, Modifier.weight(1f)) { key(3, "Домой") }
                        RemoteKey("Меню", busy, Modifier.weight(1f)) { key(256, "Меню ТВ") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteKey("Настройки", busy, Modifier.weight(1f)) { key(176, "Настройки") }
                        RemoteKey("Громкость −", busy, Modifier.weight(1f)) { key(25, "Громкость −") }
                        RemoteKey("Громкость +", busy, Modifier.weight(1f)) { key(24, "Громкость +") }
                        RemoteKey("Mute", busy, Modifier.weight(1f)) { key(164, "Звук переключён") }
                    }
                } else {
                    if (!imeReady && !busy) {
                        OutlinedButton(onClick = { prepareIme() }, Modifier.fillMaxWidth()) {
                            Text("Подключить клавиатуру к ТВ")
                        }
                    }
                    val rows = when {
                        symbols -> listOf("1234567890".map { it.toString() }, listOf("@", "#", "\$", "%", "&", "-", "+", "(", ")", "/"),
                            listOf("*", "\"", "'", ":", ";", "!", "?", "⌫"))
                        language == "RU" -> listOf("йцукенгшщзх".map { it.toString() }, "фывапролджэ".map { it.toString() },
                            "ячсмитьбю".map { it.toString() })
                        else -> listOf("qwertyuiop".map { it.toString() }, "asdfghjkl".map { it.toString() },
                            "zxcvbnm".map { it.toString() })
                    }
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            row.forEach { value ->
                                RemoteKey(value, busy, Modifier.weight(1f)) {
                                    when (value) {
                                        "⌫" -> key(67, "Удаление")
                                        else -> typeText(if (shifted) value.uppercase() else value)
                                    }
                                    if (shifted) shifted = false
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        RemoteKey("⇧", busy, Modifier.weight(1f)) { shifted = !shifted }
                        RemoteKey(if (symbols) "ABC" else "?123", busy, Modifier.weight(1f)) { symbols = !symbols; shifted = false }
                        RemoteKey("Пробел", busy, Modifier.weight(2.5f)) { key(62, "Пробел") }
                        RemoteKey("⌫", busy, Modifier.weight(1f)) { key(67, "Удаление") }
                        RemoteKey("↵", busy, Modifier.weight(1f)) { key(66, "Ввод") }
                    }
                }

                if (status.isNotBlank()) Text(status, color = if (status.startsWith("Ошибка")) Color(0xFFFF8888) else Color.LightGray, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        restoreIme()
                        tucked = true
                        panelOpen = false
                        x = if (currentBubbleIsRight(x, maxX, rightDock)) rightDock else leftDock
                        prefs.edit().putBoolean("floating_remote_tucked", true)
                            .putFloat("floating_remote_x", x).apply()
                    }) { Text("Скрыть к краю") }
                }
            }
        }
    }
    }
}

private fun currentBubbleIsRight(x: Float, maxX: Float, rightDock: Float): Boolean =
    if (x.isFinite()) x > maxX / 2f else rightDock > maxX / 2f

@Composable
private fun RemoteKey(
    label: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    if (primary) Button(onClick, enabled = !busy, modifier = modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)) {
        Text(label, fontSize = 13.sp)
    } else OutlinedButton(onClick, enabled = !busy, modifier = modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)) {
        Text(label, fontSize = 13.sp)
    }
}
''', encoding="utf-8")
print("Selected-TV remote and keyboard integration written.")
