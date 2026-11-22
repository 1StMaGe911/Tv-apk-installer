#!/usr/bin/env python3
"""Let Back leave a restored/stopped TV setup screen and return to TV management."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
app_root = root / "vendor" / "cleanyourtv-app/app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt"

old = '                    BackHandler { if (!m.back()) { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else onExit() } }'
new = """                    BackHandler {
                        if (state is SetupState.OpenDoor || state is SetupState.Stopped) {
                            go("manage")
                        } else if (!m.back()) {
                            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            else onExit()
                        }
                    }"""

data = app_root.read_text(encoding="utf-8")
if new not in data:
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one BackHandler in {app_root}; found {count}")
    app_root.write_text(data.replace(old, new), encoding="utf-8")

print("Back from a stopped/recovered setup now returns to TV management.")
