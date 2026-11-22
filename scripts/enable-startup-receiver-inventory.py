from pathlib import Path
import re

root = Path(__file__).resolve().parents[1] / "vendor" / "cleanyourtv-app"

def once(path, old, new):
    s = path.read_text(encoding="utf-8")
    if new in s:
        return
    if s.count(old) != 1:
        raise SystemExit(f"Expected one integration anchor in {path}: {old!r}; found {s.count(old)}")
    path.write_text(s.replace(old, new), encoding="utf-8")

src = r'''package tv.cleanyour.core.probe

import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession

data class StartupReceiver(val component: String, val packageName: String, val lockedBoot: Boolean, val userInstalled: Boolean)
data class StartupReceiverInventory(val receivers: List<StartupReceiver>, val bootQuerySupported: Boolean?, val lockedBootQuerySupported: Boolean?)

object StartupReceiverParser {
    private const val MARK = "@@cyt-boot@@ "
    private val component = Regex("^([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)$")

    fun parse(stdout: String): StartupReceiverInventory {
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        for (raw in stdout.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith(MARK)) {
                val name = line.removePrefix(MARK)
                if (name !in setOf("user", "boot", "locked") || name in sections) throw ProbeException(ProbeStage.PACKAGES, "unexpected boot receiver section")
                current = name
                sections[name] = mutableListOf()
            } else current?.let { sections.getValue(it) += line }
        }
        if (!sections.keys.containsAll(listOf("user", "boot", "locked"))) throw ProbeException(ProbeStage.PACKAGES, "boot receiver output is incomplete")
        fun supported(name: String): Boolean? {
            val lines = sections.getValue(name)
            return when {
                lines.any { it.trim() == "__cyt_query_unsupported__" } -> false
                lines.any { component.matches(it.trim()) } -> true
                lines.any { it.trim().isNotEmpty() && !it.trim().startsWith("No activities found") } -> true
                else -> null
            }
        }
        val userPackages = sections.getValue("user").mapNotNull { line -> line.trim().removePrefix("package:").takeIf { it != line.trim() && it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) } }.toSet()
        val receivers = linkedMapOf<String, StartupReceiver>()
        for ((name, locked) in listOf("boot" to false, "locked" to true)) {
            for (line in sections.getValue(name)) {
                val value = line.trim()
                val match = component.matchEntire(value) ?: continue
                receivers.putIfAbsent(value, StartupReceiver(value, match.groupValues[1], locked, match.groupValues[1] in userPackages))
            }
        }
        return StartupReceiverInventory(receivers.values.take(300), supported("boot"), supported("locked"))
    }
}

@OptIn(RawShell::class)
class AdbStartupReceiverReader {
    suspend fun read(session: TvSession): StartupReceiverInventory {
        val result = session.exec(SCRIPT, ExecSpec(timeoutMs = 12_000, stdoutCap = 48 * 1024, retry = RetryClass.IDEMPOTENT_READ))
        if (result.truncated) throw ProbeException(ProbeStage.PACKAGES, "boot receiver output exceeded limit")
        return StartupReceiverParser.parse(result.stdout)
    }
    companion object {
        internal val SCRIPT = ShellLine(
            "echo '@@cyt-boot@@ user'; pm list packages -3 2>/dev/null; " +
                "echo '@@cyt-boot@@ boot'; "
                "if cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED 2>/dev/null; then :; else echo '__cyt_query_unsupported__'; fi; " +
                "echo '@@cyt-boot@@ locked'; " +
                "if cmd package query-receivers --brief -a android.intent.action.LOCKED_BOOT_COMPLETED 2>/dev/null; then :; else echo '__cyt_query_unsupported__'; fi"
        )
    }
}
'''
p = root / "core/probe/src/main/kotlin/tv/cleanyour/core/probe/StartupReceivers.kt"
p.parent.mkdir(parents=True, exist_ok=True)
if not p.exists(): p.write_text(src, encoding="utf-8")

common = root / "core/flows/src/main/kotlin/tv/cleanyour/core/flows/ManageCommon.kt"
once(common, "import tv.cleanyour.core.probe.Prober", "import tv.cleanyour.core.probe.Prober\nimport tv.cleanyour.core.probe.AdbStartupReceiverReader")
once(common, "    val phoneNetwork: PhoneNetwork = PhoneNetwork { PhoneNetworkFacts.UNKNOWN },", "    val phoneNetwork: PhoneNetwork = PhoneNetwork { PhoneNetworkFacts.UNKNOWN },\n    val startupReceiverReader: AdbStartupReceiverReader = AdbStartupReceiverReader(),")

machine = root / "core/flows/src/main/kotlin/tv/cleanyour/core/flows/ManageMachine.kt"
once(machine, "    val switchingHome: Boolean = false,   // 0.4.6: the wizard Home row's \"Switch it back on\" is running\n",
     "    val switchingHome: Boolean = false,   // 0.4.6: the wizard Home row's \"Switch it back on\" is running\n    val startupReceiversLoading: Boolean = false,\n    val startupReceivers: tv.cleanyour.core.probe.StartupReceiverInventory? = null,\n    val startupReceiversError: Boolean = false,\n")
once(machine, "    data object SwitchStockHomeOn : ManageIntent", "    data object SwitchStockHomeOn : ManageIntent\n    data object CheckStartupReceivers : ManageIntent")
once(machine, "    private data class RestartsRead(val tvId: TvId, val tally: RestartTally)\n",
     "    private data class RestartsRead(val tvId: TvId, val tally: RestartTally)\n    private data object StartupReceiversLoading\n    private data class StartupReceiversLoaded(val tvId: TvId, val inventory: tv.cleanyour.core.probe.StartupReceiverInventory)\n    private data class StartupReceiversFailed(val tvId: TvId)\n")
once(machine, "        is ManageIntent.Select -> state.copy(selected = event.tvId)",
     "        is ManageIntent.Select -> state.copy(selected = event.tvId, startupReceiversLoading = false, startupReceivers = null, startupReceiversError = false)")
once(machine, "        is RestartsRead -> state.copy(cards = state.cards.map { if (it.id == event.tvId) it.copy(restarts = event.tally) else it })\n",
     "        is RestartsRead -> state.copy(cards = state.cards.map { if (it.id == event.tvId) it.copy(restarts = event.tally) else it })\n        StartupReceiversLoading -> state.copy(startupReceiversLoading = true, startupReceiversError = false)\n        is StartupReceiversLoaded -> if (state.current?.id == event.tvId) state.copy(startupReceiversLoading = false, startupReceivers = event.inventory, startupReceiversError = false) else state\n        is StartupReceiversFailed -> if (state.current?.id == event.tvId) state.copy(startupReceiversLoading = false, startupReceiversError = true) else state\n")
once(machine, "            ManageIntent.RefreshThumbnail -> scope.launch { thumbnail() }\n",
     "            ManageIntent.RefreshThumbnail -> scope.launch { thumbnail() }\n            ManageIntent.CheckStartupReceivers -> if (id != null && !before.startupReceiversLoading) scope.launch { checkStartupReceivers(id) }\n")
once(machine, "    // The card's thumbnail is the last screenshot; tapping refreshes it when the TV is connected.\n",
     "    private suspend fun checkStartupReceivers(id: TvId) {\n        send(StartupReceiversLoading)\n        val session = d.access.live(id)\n        if (session == null) { send(StartupReceiversFailed(id)); return }\n        try { send(StartupReceiversLoaded(id, d.startupReceiverReader.read(session))) }\n        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }\n        catch (_: Exception) { send(StartupReceiversFailed(id)) }\n    }\n\n    // The card's thumbnail is the last screenshot; tapping refreshes it when the TV is connected.\n")

screens = root / "feature/manage/src/main/kotlin/tv/cleanyour/feature/manage/ManageScreens.kt"
once(screens, "        ScreenTitle(stringResource(R.string.manage_title), titleTop)\n",
     "        ScreenTitle(stringResource(R.string.manage_title), titleTop)\n"
     "        if (tv != null) {\n"
     "            SecondaryButton(stringResource(R.string.manage_startup_check), { onIntent(ManageIntent.CheckStartupReceivers) }, Modifier.padding(top = 12.dp))\n"
     "            if (state.startupReceiversLoading) LiveLine(stringResource(R.string.manage_startup_reading))\n"
     "            if (state.startupReceiversError) Banner(stringResource(R.string.manage_startup_error), BannerTone.WARNING, Modifier.padding(top = 8.dp))\n"
     "            state.startupReceivers?.let { inventory ->\n"
     "                SectionLabel(stringResource(R.string.manage_startup_title))\n"
     "                val available = stringResource(R.string.manage_startup_supported)\n"
     "                val unsupported = stringResource(R.string.manage_startup_unsupported)\n"
     "                val unknown = stringResource(R.string.manage_startup_unknown)\n"
     "                val boot = when (inventory.bootQuerySupported) { true -> available; false -> unsupported; null -> unknown }\n"
     "                val locked = when (inventory.lockedBootQuerySupported) { true -> available; false -> unsupported; null -> unknown }\n"
     "                Body(stringResource(R.string.manage_startup_boot) + \": \" + boot + \"\\n\" + stringResource(R.string.manage_startup_locked) + \": \" + locked, Modifier.padding(top = 4.dp))\n"
     "                if (inventory.receivers.isEmpty()) Body(stringResource(R.string.manage_startup_empty), Modifier.padding(top = 4.dp))\n"
     "                else {\n"
     "                    val lockedLabel = stringResource(R.string.manage_startup_locked)\n"
     "                    val bootLabel = stringResource(R.string.manage_startup_boot)\n"
     "                    Body(inventory.receivers.joinToString(\"\\n\") { receiver -> receiver.packageName + \" · \" + (if (receiver.lockedBoot) lockedLabel else bootLabel) + \"\\n\" + receiver.component }, Modifier.padding(top = 4.dp))\n"
     "                }\n"
     "            }\n"
     "        }\n")

def merge(path, entries):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = path.read_text(encoding="utf-8") if path.exists() else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    for name, value in re.findall(r'<string name="([^"]+)">([\s\S]*?)</string>', entries):
        if f'<string name="{name}">' not in data:
            data = data.replace("</resources>", f'    <string name="{name}">{value}</string>\n</resources>')
    path.write_text(data, encoding="utf-8")

merge(root / "feature/manage/src/main/res/values/strings.xml", """<string name="manage_startup_check">Check startup apps</string>
<string name="manage_startup_reading">Reading startup receivers…</string>
<string name="manage_startup_error">Could not read startup receivers. Check the TV connection and try again.</string>
<string name="manage_startup_title">Startup receivers</string>
<string name="manage_startup_supported">Available</string>
<string name="manage_startup_unsupported">Unsupported</string>
<string name="manage_startup_unknown">Unknown</string>
<string name="manage_startup_empty">No receiver entries were returned. This does not prove that no apps start automatically.</string>
<string name="manage_startup_boot">After boot</string>
<string name="manage_startup_locked">Before unlock</string>""")
merge(root / "feature/manage/src/main/res/values-ru/strings.xml", """<string name="manage_startup_check">Проверить автозапуск</string>
<string name="manage_startup_reading">Читаем список автозапуска…</string>
<string name="manage_startup_error">Не удалось прочитать список автозапуска. Проверьте подключение к ТВ и повторите попытку.</string>
<string name="manage_startup_title">Компоненты автозапуска</string>
<string name="manage_startup_supported">Доступно</string>
<string name="manage_startup_unsupported">Не поддерживается</string>
<string name="manage_startup_unknown">Неизвестно</string>
<string name="manage_startup_empty">Список пуст. Это не доказывает, что приложения не запускаются автоматически.</string>
<string name="manage_startup_boot">После загрузки</string>
<string name="manage_startup_locked">До разблокировки</string>
<string name="manage_startup_user_app">Пользовательское приложение</string>
<string name="manage_startup_system_app">Системное приложение · только просмотр</string>""")

print("Read-only startup receiver inventory integrated.")


# Keep the startup inventory out of the Manage feature list: open it in its own dialog.
screens_ui = root / "feature/manage/src/main/kotlin/tv/cleanyour/feature/manage/ManageScreens.kt"
nl = chr(10)
once(screens_ui, "import androidx.compose.material3.ExperimentalMaterial3Api" + nl,
     "import androidx.compose.material3.ExperimentalMaterial3Api" + nl
     + "import androidx.compose.material3.AlertDialog" + nl
     + "import androidx.compose.material3.LinearProgressIndicator" + nl
     + "import androidx.compose.material3.Text" + nl
     + "import androidx.compose.material3.TextButton" + nl)
once(screens_ui, "    val tv = state.current" + nl + "    val locale = LocalConfiguration.current.locales[0]" + nl,
     "    val tv = state.current" + nl + "    val locale = LocalConfiguration.current.locales[0]" + nl
     + "    var startupDialogVisible by rememberSaveable(tv?.id?.value) { mutableStateOf(false) }" + nl)

ui = screens_ui.read_text(encoding="utf-8")
old_start = "        if (tv != null) {" + nl + "            SecondaryButton(stringResource(R.string.manage_startup_check)"
if old_start in ui:
    start = ui.index(old_start)
    end = ui.index("        if (state.offline)", start)
    ui = ui[:start] + ui[end:]
elif "SecondaryButton(stringResource(R.string.manage_startup_check)" in ui:
    raise SystemExit("Startup UI changed unexpectedly; refusing unsafe rewrite")

row_anchor = "                ListRow(stringResource(R.string.manage_row_tvsettings), onClick = { onIntent(ManageIntent.Open(ManageSection.TV_SETTINGS)) })"
row_new = row_anchor + nl + "                ListDivider()" + nl + "                ListRow(stringResource(R.string.manage_startup_check), subtitle = stringResource(R.string.manage_startup_title), onClick = { startupDialogVisible = true; onIntent(ManageIntent.CheckStartupReceivers) })"
if row_new not in ui:
    if ui.count(row_anchor) != 1:
        raise SystemExit("Could not find the unique TV settings row for startup check")
    ui = ui.replace(row_anchor, row_new)

dialog_anchor = "        ScreenTitle(stringResource(R.string.manage_title), titleTop)" + nl
dialog = """        if (startupDialogVisible) {
            AlertDialog(
                onDismissRequest = { startupDialogVisible = false },
                title = { Text(stringResource(R.string.manage_startup_title)) },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        if (state.startupReceiversLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.startupReceiversError) Text(stringResource(R.string.manage_startup_error))
                        state.startupReceivers?.let { inventory ->
                            val available = stringResource(R.string.manage_startup_supported)
                            val unsupported = stringResource(R.string.manage_startup_unsupported)
                            val unknown = stringResource(R.string.manage_startup_unknown)
                            val boot = when (inventory.bootQuerySupported) { true -> available; false -> unsupported; null -> unknown }
                            val locked = when (inventory.lockedBootQuerySupported) { true -> available; false -> unsupported; null -> unknown }
                            Text(stringResource(R.string.manage_startup_boot) + ": " + boot)
                            Text(stringResource(R.string.manage_startup_locked) + ": " + locked)
                            if (inventory.receivers.isEmpty()) Text(stringResource(R.string.manage_startup_empty), Modifier.padding(top = 8.dp))
                            else inventory.receivers.forEach { receiver ->
                                Text((if (receiver.userInstalled) stringResource(R.string.manage_startup_user_app) else stringResource(R.string.manage_startup_system_app)) + " · " + receiver.packageName + " · " + (if (receiver.lockedBoot) stringResource(R.string.manage_startup_locked) else stringResource(R.string.manage_startup_boot)) + "\\n" + receiver.component,
                                    Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { startupDialogVisible = false }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(enabled = !state.startupReceiversLoading, onClick = { onIntent(ManageIntent.CheckStartupReceivers) }) {
                        Text(stringResource(R.string.manage_startup_check))
                    }
                },
            )
        }
"""
if "if (startupDialogVisible)" not in ui:
    if ui.count(dialog_anchor) != 1:
        raise SystemExit("Could not find the Manage title anchor for startup dialog")
    ui = ui.replace(dialog_anchor, dialog_anchor + dialog)
screens_ui.write_text(ui, encoding="utf-8")

# Keep the app-settings shortcut visible across destinations, not only on the Manage home screen.
app_root = root / "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"
once(app_root, "import androidx.compose.foundation.layout.Box" + nl,
     "import androidx.compose.foundation.layout.Box" + nl)
once(app_root, "import tv.cleanyour.feature.setup.SetupFlow" + nl,
     "import tv.cleanyour.feature.setup.SetupFlow" + nl
     + "import tv.cleanyour.ui.design.component.CytIconButton" + nl
     + "import tv.cleanyour.ui.design.icon.CytIcons" + nl)
shortcut = """        if (backStack.lastOrNull() !is Dest.Manage) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 16.dp)) {
                CytIconButton(CytIcons.Settings, context.getString(tv.cleanyour.feature.manage.R.string.manage_settings_cd), onClick = {
                    if (backStack.lastOrNull() != Dest.AppSettings) go("appsettings")
                })
            }
        }
"""
anchor = "        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))"
if shortcut.strip() not in app_root.read_text(encoding="utf-8"):
    once(app_root, anchor, shortcut + anchor)


# Record a completed startup receiver inventory read in the per-TV history.
once(app_root,
'''import ru.tvapk.installer.TvAppsScreen''',
'''import ru.tvapk.installer.TvAppsScreen
import ru.tvapk.installer.TvAppsHistory''')
once(app_root,
'''                entry<Dest.Manage> { key ->
                    val m = rememberMachine("manage:${key.tvId}") { graph.manage(it, key.tvId?.let(::TvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }''',
'''                entry<Dest.Manage> { key ->
                    val m = rememberMachine("manage:${key.tvId}") { graph.manage(it, key.tvId?.let(::TvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    var startupCheckWasLoading by remember(key.tvId) { mutableStateOf(false) }
                    LaunchedEffect(state.startupReceiversLoading, state.startupReceivers, state.startupReceiversError) {
                        if (startupCheckWasLoading && !state.startupReceiversLoading &&
                            state.startupReceivers != null && !state.startupReceiversError) {
                            val selectedTvId = state.current?.id?.value ?: key.tvId
                            val inventory = state.startupReceivers
                            if (selectedTvId != null && inventory != null) {
                                val boot = when (inventory.bootQuerySupported) { true -> "доступно"; false -> "не поддерживается"; null -> "неизвестно" }
                                val locked = when (inventory.lockedBootQuerySupported) { true -> "доступно"; false -> "не поддерживается"; null -> "неизвестно" }
                                TvAppsHistory.record(context, selectedTvId, "Проверен автозапуск",
                                    "Найдено компонентов: ${inventory.receivers.size}; после загрузки: $boot; до разблокировки: $locked")
                            }
                        }
                        startupCheckWasLoading = state.startupReceiversLoading
                    }
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }''')
print("Startup inventory now opens in a separate dialog; app settings shortcut is global.")
