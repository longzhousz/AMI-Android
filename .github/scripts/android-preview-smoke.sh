#!/usr/bin/env bash
set -euo pipefail
package=com.longzhousz.ami.preview
mkdir -p preview-smoke-results
collect_logs() {
  adb logcat -d > preview-smoke-results/logcat.txt
  adb logcat -b crash -d | tee preview-smoke-results/crash.txt
  adb shell dumpsys activity activities > preview-smoke-results/activities.txt
  adb exec-out screencap -p > preview-smoke-results/screen.png
}
trap collect_logs EXIT
apk=$(find preview-apk -name '*.apk' -print -quit)
test -n "$apk"
sha256sum "$apk" | tee preview-smoke-results/apk-sha256.txt
if [ -d baseline-apk ]; then
  baseline=$(find baseline-apk -name '*.apk' -print -quit)
  test -n "$baseline"
  adb install "$baseline"
  adb shell am start -W -n "$package/com.longzhousz.ami.MainActivity"
  sleep 3
  adb shell pidof "$package"
  python3 .github/scripts/android-wait-for-window.py preview-smoke-results/baseline.xml "$package" '导入我的音乐'
  python3 - <<'PY'
import xml.etree.ElementTree as ET
assert any(n.get('text') == '导入我的音乐' for n in ET.parse('preview-smoke-results/baseline.xml').iter('node')), 'Previous database did not finish loading'
PY
  adb shell am force-stop "$package"
fi
adb install -r "$apk"
adb logcat -c
for attempt in 1 2; do
  adb shell am force-stop "$package"
  adb shell am start -W -n "$package/com.longzhousz.ami.MainActivity"
  sleep 5
  adb shell pidof "$package"
  python3 .github/scripts/android-wait-for-window.py "preview-smoke-results/launch-$attempt.xml" "$package" '前往设置'
  python3 - "$package" "preview-smoke-results/launch-$attempt.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
package, path = sys.argv[1:]
nodes = list(ET.parse(path).iter('node'))
assert any(n.get('package') == package for n in nodes), 'App window is not visible'
assert any(n.get('text') == '曲库' for n in nodes), 'Library did not render'
assert any(n.get('text') == '暂无音乐' for n in nodes), 'Library did not finish loading'
assert any(n.get('text') == '前往设置' for n in nodes), 'Settings entry is missing'
assert not any(n.get('content-desc') == '导入音乐文件夹' for n in nodes), 'Repeated import action remains'
PY
done
python3 - preview-smoke-results/launch-2.xml <<'PY'
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).iter('node'))
node = next(n for n in nodes if n.get('text') == '设置')
x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True)
PY
sleep 2
python3 .github/scripts/android-wait-for-window.py preview-smoke-results/settings.xml "$package" 'DeepSeek API Key'
python3 - <<'PY'
import xml.etree.ElementTree as ET
nodes = list(ET.parse('preview-smoke-results/settings.xml').iter('node'))
assert any(n.get('text') == 'DeepSeek API Key' for n in nodes), 'Settings did not render'
assert any(n.get('text') == '导入音乐' for n in nodes), 'Music import is missing from settings'
PY
python3 - <<'PY'
import re
import subprocess
import xml.etree.ElementTree as ET
node = next(n for n in ET.parse('preview-smoke-results/settings.xml').iter('node') if n.get('text') == '网易云歌词')
x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True)
PY
sleep 2
python3 .github/scripts/android-wait-for-window.py preview-smoke-results/netease-lyrics.xml "$package" '选择歌词文件夹'
python3 - <<'PY'
import xml.etree.ElementTree as ET
assert any(n.get('text') == '选择歌词文件夹' for n in ET.parse('preview-smoke-results/netease-lyrics.xml').iter('node')), 'NetEase lyrics import did not render'
PY
if adb logcat -b crash -d | grep -F "Process: $package,"; then
  echo 'The optimized app crashed' >&2
  exit 1
fi
