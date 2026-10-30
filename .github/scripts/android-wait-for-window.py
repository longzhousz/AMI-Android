#!/usr/bin/env python3
"""Wait for the requested app window; recover only emulator System UI ANR overlays."""
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

destination, package, expected_text = sys.argv[1:]
destination = pathlib.Path(destination)
deadline = time.monotonic() + 60
attempt = 0
while time.monotonic() < deadline:
    attempt += 1
    try:
        # Android 17 may briefly return exit 0 with a null accessibility root and no XML.
        # Discard the previous dump so an unsuccessful attempt cannot pass on stale content.
        subprocess.run(["adb", "shell", "rm", "-f", "/sdcard/ami-window.xml"], check=True, timeout=10)
        subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ami-window.xml"], check=True, timeout=20)
        subprocess.run(["adb", "pull", "/sdcard/ami-window.xml", str(destination)], check=True, timeout=10)
        nodes = list(ET.parse(destination).iter("node"))
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError, FileNotFoundError):
        print(f"Waiting for emulator accessibility root, attempt {attempt}", flush=True)
        time.sleep(2)
        continue
    system_ui_anr = any(n.get("package") == "android" and n.get("text") == "System UI isn't responding" for n in nodes)
    if system_ui_anr:
        evidence = destination.with_name(f"system-ui-{destination.stem}-{attempt}")
        evidence.with_suffix(".xml").write_bytes(destination.read_bytes())
        with evidence.with_suffix(".png").open("wb") as output:
            subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=output, check=True, timeout=10)
        button = next((n for n in nodes if n.get("package") == "android" and n.get("text") == "Wait"), None)
        if button is None:
            raise RuntimeError("System UI recovery action is unavailable")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", button.get("bounds")))
        print("Recovering emulator System UI overlay; saved evidence:", evidence, flush=True)
        subprocess.run(["adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)], check=True, timeout=10)
    elif any(n.get("package") == package for n in nodes) and any(n.get("text") == expected_text for n in nodes):
        sys.exit(0)
    time.sleep(2)
raise RuntimeError(f"App window did not show {expected_text!r}; inspect {destination}")
