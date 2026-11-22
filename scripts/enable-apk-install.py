#!/usr/bin/env python3
"""Expose APK installation as a standalone Clean Your TV menu section."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
upstream = root / "vendor" / "cleanyourtv-app"

def replace_once(path: Path, old: str, new: str) -> None:
    data = path.read_text(encoding="utf-8")
    if new and new in data:
        return
    count = data.count(old)
    if count == 0 and not new:
        return
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence in {path}: {old!r}; found {count}")
    path.write_text(data.replace(old, new), encoding="utf-8")

app_root = upstream / "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"
manage_screens = upstream / "feature/manage/src/main/kotlin/tv/cleanyour/feature/manage/ManageScreens.kt"

replace_once(app_root, "import ru.tvapk.installer.TvInstallerApp", "import ru.tvapk.installer.ApkInstallerScreen")
replace_once(app_root, "@Serializable data object LegacyInstaller : Dest", "@Serializable data object InstallApk : Dest")
replace_once(app_root, '"legacyinstaller" -> Dest.LegacyInstaller', '"installapk" -> Dest.InstallApk')
replace_once(app_root, "entry<Dest.LegacyInstaller> { TvInstallerApp() }", 'entry<Dest.InstallApk> { ApkInstallerScreen(graph.tvs, graph.access, null, onBack = { go("back") }, discoverDevices = { withContext(Dispatchers.IO) { discoverAdbDevices(context) } }) }')
replace_once(app_root, 'ManageScreen(state, m::dispatch, onLegacyInstaller = { go("legacyinstaller") })', 'ManageScreen(state, m::dispatch, onInstallApk = { go("installapk") })')
replace_once(manage_screens,
    "fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier, onLegacyInstaller: () -> Unit = {}) {",
    "fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier, onInstallApk: () -> Unit = {}) {")
replace_once(manage_screens,
    '                ListRow("TV APK Installer — дополнительные инструменты", subtitle = "ADB, очистка, резервные копии и управление лаунчером", onClick = onLegacyInstaller)',
    '')
replace_once(manage_screens,
    '        val others = state.cards.filter { it.id != tv?.id }',
    '        ListGroup(Modifier.padding(top = 12.dp)) {\n            ListRow("Установка APK", subtitle = "Установить выбранный APK по Wi-Fi или USB OTG", onClick = onInstallApk)\n        }\n        val others = state.cards.filter { it.id != tv?.id }')
print("Clean Your TV: APK installation is now a standalone menu section.")
