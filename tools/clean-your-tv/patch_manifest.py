#!/usr/bin/env python3
"""Launch directly into MainActivity, bypassing only the welcome/QR screen."""
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
MAIN = "android.intent.action.MAIN"
LAUNCHER = "android.intent.category.LAUNCHER"

path = sys.argv[1]
tree = ET.parse(path)
root = tree.getroot()
app = root.find("application")
if app is None:
    raise SystemExit("Manifest has no <application> element")

activities = list(app.findall("activity"))
welcome = [n for n in activities if "WelcomeActivity" in n.get(ANDROID + "name", "")]
main = [n for n in activities if n.get(ANDROID + "name", "").endswith(".MainActivity")]
if not welcome or len(main) != 1:
    raise SystemExit("Expected WelcomeActivity and exactly one MainActivity; refusing to guess")

launcher_filters = []
for node in welcome:
    for filt in list(node.findall("intent-filter")):
        actions = {x.get(ANDROID + "name") for x in filt.findall("action")}
        categories = {x.get(ANDROID + "name") for x in filt.findall("category")}
        if MAIN in actions and LAUNCHER in categories:
            launcher_filters.append(filt)
            node.remove(filt)

if len(launcher_filters) != 1:
    raise SystemExit(f"Expected exactly one WelcomeActivity launcher filter, found {len(launcher_filters)}")

target = main[0]
for filt in list(target.findall("intent-filter")):
    actions = {x.get(ANDROID + "name") for x in filt.findall("action")}
    categories = {x.get(ANDROID + "name") for x in filt.findall("category")}
    if MAIN in actions and LAUNCHER in categories:
        target.remove(filt)

target.append(launcher_filters[0])
target.set(ANDROID + "exported", "true")
app.set(ANDROID + "label", "Clean Your TV")
tree.write(path, encoding="utf-8", xml_declaration=True)
print("Launcher moved from WelcomeActivity to MainActivity; app label kept as Clean Your TV.")
