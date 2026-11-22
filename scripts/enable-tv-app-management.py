#!/usr/bin/env python3
"""Integrate the TV installed-app manager into the primary Clean Your TV navigation."""
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
    path.write_text(data.replace(old, new), encoding="utf-8")


app_root = upstream / "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"
manage_screens = upstream / "feature/manage/src/main/kotlin/tv/cleanyour/feature/manage/ManageScreens.kt"

replace_once(
    app_root,
    "import tv.cleanyour.feature.setup.SetupFlow",
    "import tv.cleanyour.feature.setup.SetupFlow\nimport ru.tvapk.installer.TvAppsScreen\nimport ru.tvapk.installer.discoverAdbDevices",
)
replace_once(
    app_root,
    "    @Serializable data object AppSettings : Dest",
    "    @Serializable data class TvApps(val tvId: String) : Dest\n    @Serializable data object AppSettings : Dest",
)
replace_once(
    app_root,
    '        "tvsettings" -> id?.let { Dest.TvSettings(it) }',
    '        "tvsettings" -> id?.let { Dest.TvSettings(it) }\n        "tvapps" -> id?.let { Dest.TvApps(it) }',
)
replace_once(
    app_root,
    '                    ManageScreen(state, m::dispatch, onInstallApk = { go("installapk") })',
    '                    ManageScreen(state, m::dispatch, onInstallApk = { go("installapk") }, onTvApps = { state.current?.id?.let { backStack.add(Dest.TvApps(it.value)) } })',
)
replace_once(
    app_root,
    "                entry<Dest.SwitchedOff> { key ->",
    """                entry<Dest.TvApps> { key ->
                    TvAppsScreen(
                        historyTvId = key.tvId,
                        onBack = { go("back") },
                        discoverDevices = { withContext(Dispatchers.IO) { discoverAdbDevices(context) } }
                    )
                }
                entry<Dest.SwitchedOff> { key ->""",
)

replace_once(
    manage_screens,
    "fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier, onInstallApk: () -> Unit = {}) {",
    "fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier, onInstallApk: () -> Unit = {}, onTvApps: () -> Unit = {}) {",
)
replace_once(
    manage_screens,
    "        val others = state.cards.filter { it.id != tv?.id }",
    """        ListGroup(Modifier.padding(top = 12.dp)) {
            ListRow("Приложения телевизора", subtitle = "Просмотр, запуск и остановка установленных приложений", onClick = onTvApps)
        }
        val others = state.cards.filter { it.id != tv?.id }""",
)
print("Clean Your TV: TV installed-app management is integrated.")
