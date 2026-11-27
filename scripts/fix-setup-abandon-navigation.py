#!/usr/bin/env python3
"""Return from an abandoned TV setup to management and keep TV selection visible."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
vendor = root / "vendor" / "cleanyourtv-app"
support = vendor / "core/flows/src/main/kotlin/tv/cleanyour/core/flows/Support.kt"
support_test = vendor / "core/flows/src/test/kotlin/tv/cleanyour/core/flows/SetupMachineTest.kt"
connect = vendor / "feature/connect/src/main/kotlin/tv/cleanyour/feature/connect/ConnectScreens.kt"
manage = vendor / "feature/manage/src/main/kotlin/tv/cleanyour/feature/manage/ManageScreens.kt"
app_root = vendor / "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"

def replace_once(path: Path, old: str, new: str, label: str):
    data = path.read_text(encoding="utf-8")
    count = data.count(old)
    if count == 0 and data.count(new) == 1:
        return  # The integration step or a previous run already applied this exact patch.
    if count != 1:
        raise SystemExit(f"{label}: expected one original match in {path}, found {count}")
    path.write_text(data.replace(old, new, 1), encoding="utf-8")

# Keep the unfinished wizard draft on disk, but do not force the app back into it on every launch.
replace_once(
    support,
    '    byRecent.firstOrNull { it.wizard != null }?.let { return StartDestination.ContinueSetup(it.id) }\n',
    '    // An unfinished wizard is resumed only when the person explicitly chooses that TV from Manage.\n',
    "startup destination",
)
replace_once(
    support_test,
    '        assertEquals(StartDestination.ContinueSetup(tv.id), resolveStart(listOf(draft), h.journal))\n',
    '        assertEquals(StartDestination.Manage(tv.id), resolveStart(listOf(draft), h.journal))\n',
    "startup destination test",
)

# The Back arrow on the connected-TV instructions screen must leave setup and open TV management.
replace_once(
    connect,
    '        is SetupState.OpenDoor -> DoorScreen(state, extras, actions, modifier, onCopyError)\n',
    '        is SetupState.OpenDoor -> DoorScreen(state, extras, actions, modifier, onCopyError, onBack = onReturning)\n',
    "connect flow back callback",
)
replace_once(
    connect,
    'fun DoorScreen(state: SetupState.OpenDoor, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, onCopyError: (String) -> Unit = {}) {\n',
    'fun DoorScreen(state: SetupState.OpenDoor, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, onCopyError: (String) -> Unit = {}, onBack: () -> Unit = { actions.back() }) {\n',
    "door screen callback",
)
replace_once(
    connect,
    '            CytTopBar(onBack = { actions.back() }, center = { if (name != null) TvStatusChip(name, (extras.chip ?: ChipState.WAITING).toTvStatus()) })\n',
    '            CytTopBar(onBack = onBack, center = { if (name != null) TvStatusChip(name, (extras.chip ?: ChipState.WAITING).toTvStatus()) })\n',
    "door screen back action",
)

# Route the Welcome screen's existing "returning" action to Manage as well, replacing the setup stack.
replace_once(
    app_root,
    '                        onReturning = { go("returning") },\n',
    '                        onReturning = { go("manage") },\n',
    "returning route",
)

# Put saved TVs and Add TV immediately below the page title instead of after the long management content.
manage_data = manage.read_text(encoding="utf-8")
block = '''        val others = state.cards.filter { it.id != tv?.id }
        ListGroup(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
            others.forEach { other ->
                ListRow(other.name, subtitle = other.room, onClick = { onIntent(ManageIntent.Select(other.id)) })
                ListDivider()
            }
            LinkRow(stringResource(R.string.manage_add_tv), { onIntent(ManageIntent.SetUpAnother) }, chevron = false, minHeight = 56.dp)
        }
'''
if manage_data.count(block) != 1:
    raise SystemExit(f"TV list block: expected one match, found {manage_data.count(block)}")
manage_data = manage_data.replace(block, "", 1)
title = '        ScreenTitle(stringResource(R.string.manage_title), titleTop)\n'
if manage_data.count(title) != 1:
    raise SystemExit("Manage title anchor not found exactly once")
manage_data = manage_data.replace(title, title + block, 1)
manage.write_text(manage_data, encoding="utf-8")

print("Back from connected setup now opens Manage; startup no longer auto-resumes an abandoned wizard; TV list and Add TV are visible near the top.")
