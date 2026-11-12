#!/usr/bin/env python3
"""Safely move Android's launcher entry from WelcomeActivity to MainActivity."""
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

def activity_name(node):
    return node.get(ANDROID + "name", "")

activities = list(app.findall("activity")) + list(app.findall("activity-alias"))
welcome = [n for n in activities if "WelcomeActivity" in activity_name(n)]
main = [n for n in activities if activity_name(n).endswith("MainActivity")]

if not welcome:
    raise SystemExit("WelcomeActivity was not found; refusing to change an unknown launcher")
if not main:
    raise SystemExit("MainActivity was not found; refusing to guess the target")

removed = 0
for node in welcome:
    for filt in list(node.findall("intent-filter")):
        actions = {x.get(ANDROID + "name") for x in filt.findall("action")}
        categories = {x.get(ANDROID + "name") for x in filt.findall("category")}
        if MAIN in actions and LAUNCHER in categories:
            node.remove(filt)
            removed += 1

if removed == 0:
    raise SystemExit("WelcomeActivity had no MAIN/LAUNCHER filter; refusing to produce an unverified build")

target = main[0]
has_launcher = False
for filt in target.findall("intent-filter"):
    actions = {x.get(ANDROID + "name") for x in filt.findall("action")}
    categories = {x.get(ANDROID + "name") for x in filt.findall("category")}
    if MAIN in actions and LAUNCHER in categories:
        has_launcher = True
        break
if not has_launcher:
    filt = ET.SubElement(target, "intent-filter")
    ET.SubElement(filt, "action", {ANDROID + "name": MAIN})
    ET.SubElement(filt, "category", {ANDROID + "name": LAUNCHER})

app.set(ANDROID + "label", "Clean Your TV")
tree.write(path, encoding="utf-8", xml_declaration=True)
print("Launcher moved from WelcomeActivity to MainActivity; label set to Clean Your TV.")
