#!/usr/bin/env python3
"""Expose only APK installation inside Clean Your TV; keep its existing TV discovery and cleanup."""
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

replace_once(app_root, "import ru.tvapk.installer.TvInstallerApp", "import ru.tvapk.installer.ApkInstallerScreen")
replace_once(app_root, "@Serializable data object LegacyInstaller : Dest", "@Serializable data class InstallApk(val tvId: String) : Dest")
replace_once(app_root, '"legacyinstaller" -> Dest.LegacyInstaller', '"installapk" -> id?.let { Dest.InstallApk(it) }')
replace_once(app_root, "entry<Dest.LegacyInstaller> { TvInstallerApp() }", 'entry<Dest.InstallApk> { key -> ApkInstallerScreen(graph.tvs, graph.access, key.tvId, onBack = { go("back") }) }')
replace_once(app_root, 'ManageScreen(state, m::dispatch, onLegacyInstaller = { go("legacyinstaller") })', 'ManageScreen(state, m::dispatch, onInstallApk = { go("installapk/$it") })')
replace_once(manage_screens,
    "fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier, onLegacyInstaller: () -> Unit = {}) {",
    "fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier, onInstallApk: (String) -> Unit = {}) {")
replace_once(manage_screens,
    'ListRow("TV APK Installer — дополнительные инструменты", subtitle = "ADB, очистка, резервные копии и управление лаунчером", onClick = onLegacyInstaller)',
    'ListRow("Установить APK", subtitle = "Выбрать APK-файл и установить на этот телевизор", onClick = { tv?.id?.value?.let(onInstallApk) })')
print("Clean Your TV: added the APK-only installation screen. Existing TV discovery and cleanup are unchanged.")
