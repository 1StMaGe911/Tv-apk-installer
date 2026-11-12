#!/usr/bin/env python3
"""Remove only promotional/donation text while preserving the welcome flow."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
needles = [
    "pro100tehnika",
    "youtube.com/@pro100tehnika",
    "2202 2036 4910 3266",
    "Перевод:",
    "YouTube:",
    "Донат (карта):",
]

changed = []
for path in root.rglob("*"):
    if not path.is_file() or path.suffix.lower() not in {".smali", ".xml"}:
        continue
    try:
        original = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    updated = original
    for needle in needles:
        if needle not in updated:
            continue
        # In smali, blank the complete string constant so no fragments of
        # account names, URLs, or card details remain visible.
        if path.suffix == ".smali":
            lines = []
            for line in updated.splitlines(keepends=True):
                if needle in line and "const-string" in line:
                    line = re.sub(r'(")(?:\\\\.|[^"\\\\])*(")', r'""', line, count=1)
                else:
                    line = line.replace(needle, "")
                lines.append(line)
            updated = "".join(lines)
        else:
            updated = updated.replace(needle, "")
    if updated != original:
        path.write_text(updated, encoding="utf-8")
        changed.append(str(path.relative_to(root)))

if not changed:
    raise SystemExit("No promotional strings found; refusing to publish an unverified APK")
print("Promotional text removed from:")
for item in changed:
    print(item)
