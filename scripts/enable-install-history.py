#!/usr/bin/env python3
"""Integrate persistent APK installation history into the primary Clean Your TV UI."""
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
    "import ru.tvapk.installer.TvAppsScreen",
    "import ru.tvapk.installer.TvAppsScreen\nimport ru.tvapk.installer.InstallHistoryScreen",
)
replace_once(
    app_root,
    "    @Serializable data object TvApps : Dest",
    "    @Serializable data object InstallHistory : Dest\n    @Serializable data object TvApps : Dest",
)
replace_once(
    app_root,
    '        "tvapps" -> Dest.TvApps',
    '        "tvapps" -> Dest.TvApps\n        "installhistory" -> Dest.InstallHistory',
)
replace_once(
    app_root,
    '                    ManageScreen(state, m::dispatch, onInstallApk = { go("installapk") }, onTvApps = { state.current?.id?.let { go("tvapps/${it.value}") } })',
    '                    ManageScreen(state, m::dispatch, onInstallApk = { go("installapk") }, onTvApps = { state.current?.id?.let { go("tvapps/${it.value}") } }, onInstallHistory = { go("installhistory") })',
)
replace_once(
    app_root,
    "                entry<Dest.TvApps> {",
    """                entry<Dest.InstallHistory> {
                    InstallHistoryScreen(onBack = { go("back") })
                }
                entry<Dest.TvApps> {""",
)
replace_once(
    manage_screens,
    "onInstallApk: () -> Unit = {}, onTvApps: () -> Unit = {}) {",
    "onInstallApk: () -> Unit = {}, onTvApps: () -> Unit = {}, onInstallHistory: () -> Unit = {}) {",
)
replace_once(
    manage_screens,
    '            ListRow("Приложения телевизора", subtitle = "Список, запуск и удаление установленных приложений", onClick = onTvApps)',
    '            ListRow("Приложения телевизора", subtitle = "Список, запуск и удаление установленных приложений", onClick = onTvApps)\n            ListRow("История установок", subtitle = "Результаты установки APK, дата, телевизор и ошибки", onClick = onInstallHistory)',
)
print("Clean Your TV: persistent APK installation history is integrated.")
