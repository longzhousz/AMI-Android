#!/usr/bin/env python3
"""Small adb UI-tree helper: python ui.py SERIAL dump|tap [exact text/description]."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

serial, action, *arguments = sys.argv[1:]

def adb(*args):
    return subprocess.check_output(["adb", "-s", serial, *args], timeout=45).decode()

xml = adb("exec-out", "uiautomator", "dump", "/dev/tty")
start, end = xml.find("<?xml"), xml.rfind("</hierarchy>") + len("</hierarchy>")
if start < 0 or end <= start:
    raise SystemExit("No UI tree; check that Android has finished booting")
tree = ET.fromstring(xml[start:end])
nodes = list(tree.iter("node"))
if action == "dump":
    for node in nodes:
        title = node.get("text") or node.get("content-desc")
        if title:
            print(title, node.get("bounds"), "clickable=" + node.get("clickable", ""))
elif action == "tap":
    title = arguments[0]
    matches = [n for n in nodes if title in (n.get("text"), n.get("content-desc"))]
    if not matches:
        raise SystemExit("Target missing; scroll and dump again: " + title)
    node = matches[-1]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    print("Tapped", title, node.get("bounds"))
else:
    raise SystemExit("Use dump or tap")
