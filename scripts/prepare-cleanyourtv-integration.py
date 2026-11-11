#!/usr/bin/env python3
"""Apply the small, reproducible product-identity patch to the pinned upstream source."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
upstream = root / "vendor" / "cleanyourtv-app"
app_gradle = upstream / "app" / "build.gradle.kts"
convention = upstream / "build-logic" / "convention" / "src" / "main" / "kotlin" / "AndroidConventionPlugins.kt"

def replace_once(path: Path, old: str, new: str) -> None:
    data = path.read_text(encoding="utf-8")
    if new in data:
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence in {path}: {old!r}; found {count}")
    path.write_text(data.replace(old, new), encoding="utf-8")

replace_once(app_gradle, "versionCode = 30", "versionCode = 188")
replace_once(app_gradle, 'versionName = "0.4.8"', 'versionName = "0.9.137"')
replace_once(convention, 'applicationId = "tv.cleanyour.app"', 'applicationId = "ru.tvapk.installer"')

# Sign the debug variant with the existing TV APK Installer key when CI supplies it.
source = convention.read_text(encoding="utf-8")
marker = '                getByName("release") {'
insert = '''                getByName("debug") {
                    if (releaseConfig != null) signingConfig = releaseConfig
                }
'''
if insert not in source:
    if source.count(marker) != 1:
        raise SystemExit("Could not safely locate Android app release build type")
    convention.write_text(source.replace(marker, insert + marker), encoding="utf-8")
print("Clean Your TV integration patched: applicationId ru.tvapk.installer, version 0.9.137 (188).")
