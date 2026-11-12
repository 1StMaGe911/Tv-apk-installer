#!/usr/bin/env python3
"""Keep the original welcome screen as the launcher; do not bypass its setup flow."""
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

activities = list(app.findall("activity")) + list(app.findall("activity-alias"))
welcome = [n for n in activities if "WelcomeActivity" in n.get(ANDROID + "name", "")]
if not welcome:
    raise SystemExit("WelcomeActivity was not found; refusing to alter the launch flow")

has_launcher = False
for node in welcome:
    for filt in node.findall("intent-filter"):
        actions = {x.get(ANDROID + "name") for x in filt.findall("action")}
        categories = {x.get(ANDROID + "name") for x in filt.findall("category")}
        if MAIN in actions and LAUNCHER in categories:
            has_launcher = True

if not has_launcher:
    raise SystemExit("WelcomeActivity is not the original launcher; refusing to guess or rewrite launch flow")

app.set(ANDROID + "label", "Clean Your TV")
tree.write(path, encoding="utf-8", xml_declaration=True)
print("Original WelcomeActivity launcher preserved; app label set to Clean Your TV.")
