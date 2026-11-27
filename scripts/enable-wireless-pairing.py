#!/usr/bin/env python3
"""Wire in-app Android 11+ Wireless Debugging pairing to Clean Your TV's session stack."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
upstream = root / "vendor" / "cleanyourtv-app"

def replace_once(path: Path, old: str, new: str) -> None:
    data = path.read_text(encoding="utf-8")
    if new in data:
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence in {path}: {old!r}; found {count}")
    path.write_text(data.replace(old, new, 1), encoding="utf-8")

# The app graph owns the same TLS-capable SocketFactory used by every Clean Your TV operation.
app_graph = upstream / "app/src/main/kotlin/tv/cleanyour/app/AppGraph.kt"
replace_once(
    app_graph,
    "import tv.cleanyour.android.platform.PlainSocketFactory\n",
    "import ru.tvapk.installer.WirelessAdbPairing\nimport ru.tvapk.installer.WirelessAdbSocketFactory\n",
)
replace_once(
    app_graph,
    "val sessions = AdbSessionManager(PlainSocketFactory(), signer, tls = null, clock = clock, scope = workScope, log = { CytLog.i(it) })",
    "val sessions = AdbSessionManager(WirelessAdbSocketFactory(app), signer, tls = null, clock = clock, scope = workScope, log = { CytLog.i(it) })",
)
replace_once(
    app_graph,
    "        restarts = restarts,       // 0.4.8\n    )",
    "        restarts = restarts,       // 0.4.8\n        endpointForTv = { tv -> WirelessAdbPairing.endpointForTv(app, tv) },\n    )",
)
replace_once(
    app_graph,
    "    init {\n        // At start and off the main thread: the key (the first launch makes it),",
    "    init {\n        WirelessAdbPairing.configure(app)\n        // At start and off the main thread: the key (the first launch makes it),",
)

# SetupDeps remains platform-agnostic: the Android app supplies an optional paired endpoint resolver.
setup_machine = upstream / "core/flows/src/main/kotlin/tv/cleanyour/core/flows/SetupMachine.kt"
replace_once(
    setup_machine,
    "    val restarts: Restarts? = documents?.let { Restarts(it) },\n)",
    "    val restarts: Restarts? = documents?.let { Restarts(it) },\n    // Android Wireless Debugging: non-null only for a host paired on this phone.\n    val endpointForTv: (FoundTv) -> Endpoint? = { null },\n)",
)
replace_once(
    setup_machine,
    "    private fun door(tv: FoundTv): SetupState {\n        val pairingOnly = (tv.pairingService != null || tv.connectService != null) && tv.port5555Open != true",
    "    private fun door(tv: FoundTv): SetupState {\n        val pairedEndpoint = deps.endpointForTv(tv)\n        if (pairedEndpoint is Endpoint.Tls) {\n            val family = Families.of(tv)\n            return SetupState.OpenDoor(tv, family, step = 2, phase = DoorPhase.CONNECTING, fingerprint = null, banner = bannerFor(family, tv), waitedMs = 0)\n        }\n        val pairingOnly = (tv.pairingService != null || tv.connectService != null) && tv.port5555Open != true",
)
replace_once(
    setup_machine,
    "        val waitingForPort = s is SetupState.OpenDoor && s.tv.host.isNotEmpty() && s.phase == DoorPhase.STEPS && !blocked",
    "        val waitingForPort = s is SetupState.OpenDoor && s.tv.host.isNotEmpty() && s.phase == DoorPhase.STEPS && !blocked && deps.endpointForTv(s.tv) == null",
)
replace_once(
    setup_machine,
    "            deps.sessions.open(Endpoint.PlainTcp(found.host), known?.id) { fp -> allowShown = true; send(Ev.Allow(fp)) }",
    "            val endpoint = deps.endpointForTv(found) ?: Endpoint.PlainTcp(found.host)\n            deps.sessions.open(endpoint, known?.id) { fp -> allowShown = true; send(Ev.Allow(fp)) }",
)

# The existing PairingNeeded dead end becomes a six-digit in-app pairing sheet.
connect_screens = upstream / "feature/connect/src/main/kotlin/tv/cleanyour/feature/connect/ConnectScreens.kt"
replace_once(
    connect_screens,
    "import androidx.compose.foundation.layout.Arrangement\n",
    "import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.text.KeyboardOptions\nimport androidx.compose.material3.OutlinedTextField\nimport androidx.compose.ui.text.input.KeyboardType\n",
)
replace_once(
    connect_screens,
    "fun ConnectFlow(state: SetupState, extras: SetupExtras, actions: SetupActions, onReturning: () -> Unit, modifier: Modifier = Modifier, onCopyError: (String) -> Unit = {}) {",
    "fun ConnectFlow(state: SetupState, extras: SetupExtras, actions: SetupActions, onReturning: () -> Unit, modifier: Modifier = Modifier, onCopyError: (String) -> Unit = {}, onPair: (FoundTv, String, (Result<Unit>) -> Unit) -> Unit = { _, _, done -> done(Result.failure(IllegalStateException(\"Pairing is unavailable\"))) }) {",
)
replace_once(
    connect_screens,
    "is SetupState.PairingNeeded -> PairingScreen(state, extras, actions, modifier)",
    "is SetupState.PairingNeeded -> PairingScreen(state, extras, actions, modifier, onPair)",
)
old_pairing = '''@Composable
fun PairingScreen(state: SetupState.PairingNeeded, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    var steps by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = { actions.back() }) }) {
        // "Show me" opens a sheet, "Back" goes back: neither acts on the TV, so neither is guarded.
        // 0.4.6: "Report a problem" opens the GitHub form with how the TV was found (no TV read yet).
        val title = stringResource(R.string.pairing_title)
        StopCard(
            title, stringResource(R.string.pairing_body), Modifier.padding(top = 20.dp),
            primaryLabel = stringResource(R.string.pairing_show_steps), onPrimary = { steps = true },
            secondaryLabel = stringResource(R.string.action_back), onSecondary = { actions.back() },
            detailsLabel = stringResource(R.string.connect_report), onDetails = { actions.report(title) },
            primaryGuarded = false,
        )
    }
    if (steps) CytBottomSheet({ steps = false }) { PairingStepsContent() }
}'''

new_pairing = '''@Composable
fun PairingScreen(
    state: SetupState.PairingNeeded,
    extras: SetupExtras,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    onPair: (FoundTv, String, (Result<Unit>) -> Unit) -> Unit,
) {
    var pairingSheet by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = { actions.back() }) }) {
        val title = "Сопряжение телевизора"
        StopCard(
            title,
            "На телевизоре открой «Беспроводная отладка» → «Сопряжение устройства с помощью кода». Введи код прямо здесь — компьютер не нужен.",
            Modifier.padding(top = 20.dp),
            primaryLabel = "Сопрячь телевизор по коду",
            onPrimary = { pairingSheet = true },
            secondaryLabel = "Назад",
            onSecondary = { actions.back() },
            detailsLabel = "Сообщить о проблеме",
            onDetails = { actions.report(title) },
            primaryGuarded = false,
        )
        if (state.tv.pairingService == null) {
            Body("На телевизоре открой «Беспроводная отладка» → «Сопряжение устройства с помощью кода», затем повтори поиск.")
        }
        if (state.tv.connectService == null) {
            Body("Не найден отдельный TLS-порт подключения. Оставь страницу беспроводной отладки открытой и повтори поиск.")
        }
        if (status.isNotBlank()) Body(status, Modifier.padding(top = 12.dp))
    }
    if (pairingSheet) CytBottomSheet({ if (!busy) pairingSheet = false }) {
        SheetHeader("Сопряжение телевизора")
        Body("Введи шестизначный код, который показывает телевизор. Компьютер и Termux не нужны.", Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { ch -> ch in '0'..'9' }.take(6) },
            label = { androidx.compose.material3.Text("Код сопряжения") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        PrimaryButton(
            if (busy) "Выполняется сопряжение…" else "Сопрячь",
            onClick = {
                if (!busy && code.length == 6 && state.tv.pairingService != null && state.tv.connectService != null) {
                    busy = true
                    status = ""
                    onPair(state.tv, code) { result ->
                        busy = false
                        result.fold(
                            {
                                pairingSheet = false
                                actions.intent(SetupIntent.EnterAddress(state.tv.host))
                            },
                            { status = "Не удалось выполнить сопряжение: " + (it.message ?: "проверь код и повтори") }
                        )
                    }
                } else if (code.length != 6) {
                    status = "Введи все 6 цифр кода."
                } else {
                    status = "Не найдены оба порта Wireless debugging. Повтори поиск при открытой странице отладки."
                }
            },
            modifier = Modifier.padding(top = 16.dp),
        )
        if (status.isNotBlank()) Body(status, Modifier.padding(top = 12.dp))
    }
}'''
replace_once(connect_screens, old_pairing, new_pairing)

# Pass the pairing action through the active Setup entry; network work stays off the UI thread.
app_root = upstream / "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"
replace_once(
    app_root,
    "import tv.cleanyour.feature.connect.ConnectFlow\n",
    "import ru.tvapk.installer.WirelessAdbPairing\nimport tv.cleanyour.feature.connect.ConnectFlow\n",
)
replace_once(
    app_root,
    'if (isConnectState(state)) ConnectFlow(state, extras, m, onReturning = { go("returning") }, onCopyError = { copyText(context, it, "error") })',
    '''if (isConnectState(state)) ConnectFlow(
                        state, extras, m,
                        onReturning = { go("returning") },
                        onCopyError = { copyText(context, it, "error") },
                        onPair = { found, code, complete ->
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { WirelessAdbPairing.pair(context, found, code) }
                                }
                                complete(result)
                            }
                        },
                    )''',
)

print("Wireless Debugging pairing UI, persisted identity, and TLS session routing patched.")
