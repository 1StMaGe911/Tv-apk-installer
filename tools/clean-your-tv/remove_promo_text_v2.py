#!/usr/bin/env python3
"""Blank only known promotional string constants; preserve app flow and UI layout."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
needles = (
    "pro100tehnika",
    "youtube.com/@pro100tehnika",
    "2202 2036 4910 3266",
    "Перевод:",
    "YouTube:",
    "Донат (карта):",
)
changed = []
for path in root.rglob("*"):
    if not path.is_file() or path.suffix.lower() not in {".smali", ".xml"}:
        continue
    try:
        src = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    dst = src
    if path.suffix.lower() == ".smali":
        out = []
        for line in dst.splitlines(keepends=True):
            if any(s in line for s in needles) and "const-string" in line:
                # Keep the destination register and replace the complete literal.
                line = re.sub(r'^(\s*const-string(?:/jumbo)?\s+[^,]+,\s*)".*"(\s*(?:#.*)?\n?)$', r'\1""\2', line)
            out.append(line)
        dst = "".join(out)
    else:
        for s in needles:
            dst = dst.replace(s, "")
    if dst != src:
        path.write_text(dst, encoding="utf-8")
        changed.append(str(path.relative_to(root)))

if not changed:
    raise SystemExit("No matching promotional text found in decoded resources/code; stop for manual inspection.")
print("Promo strings blanked in:")
print("\n".join(changed))
