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
  python3 .github/scripts/android-wait-for-window.py "preview-smoke-results/launch-$attempt.xml" "$package" '登录网易云'
  python3 - "$package" "preview-smoke-results/launch-$attempt.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
package, path = sys.argv[1:]
nodes = list(ET.parse(path).iter('node'))
assert any(n.get('package') == package for n in nodes), 'App window is not visible'
assert any(n.get('text') == '曲库' for n in nodes), 'Library did not render'
assert any(n.get('text') == '登录网易云' for n in nodes), 'Online sign-in entry is missing'
assert all(any(n.get('content-desc') == label for n in nodes) for label in ('曲库', '我喜欢', '设置')), 'Icon navigation is missing'
assert not any(n.get('text') in ('我喜欢', '设置') for n in nodes), 'Bottom navigation still has text labels'
assert not any(n.get('content-desc') == '网易云' for n in nodes), 'Separate NetEase tab remains'
assert not any(n.get('content-desc') == '导入音乐文件夹' for n in nodes), 'Repeated import action remains'
PY
done
python3 - preview-smoke-results/launch-2.xml <<'PY'
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).iter('node'))
node = next(n for n in nodes if n.get('content-desc') == '设置')
x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True)
PY
sleep 2
python3 .github/scripts/android-wait-for-window.py preview-smoke-results/settings.xml "$package" 'DeepSeek API Key'
python3 - <<'PY'
import xml.etree.ElementTree as ET
nodes = list(ET.parse('preview-smoke-results/settings.xml').iter('node'))
assert any(n.get('text') == 'DeepSeek API Key' for n in nodes), 'Settings did not render'
assert any(n.get('text') == '网易云账号' for n in nodes), 'Account entry is missing from settings'
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
if [ -d music-preview-apk ]; then
  music_package=com.longzhousz.ami.musicpreview
  music_apk=$(find music-preview-apk -name '*.apk' -print -quit)
  test -n "$music_apk"
  sha256sum "$music_apk" > preview-smoke-results/music-apk-sha256.txt
  adb install -r "$music_apk"
  adb shell am start -W -n "$music_package/com.longzhousz.ami.MainActivity"
  python3 .github/scripts/android-wait-for-window.py preview-smoke-results/music-launch.xml "$music_package" '登录网易云'
  python3 - <<'PYTHON'
import xml.etree.ElementTree as ET
nodes = list(ET.parse('preview-smoke-results/music-launch.xml').iter('node'))
assert all(any(n.get('content-desc') == label for n in nodes) for label in ('曲库', '我喜欢', '设置'))
assert not any(n.get('text') in ('我喜欢', '设置') for n in nodes)
assert not any(n.get('content-desc') == '网易云' for n in nodes)
PYTHON
  adb exec-out screencap -p > preview-smoke-results/music-launch.png
  if adb logcat -b crash -d | grep -F "Process: $music_package,"; then
    echo 'The optimized music preview crashed' >&2
    exit 1
  fi
fi
if [ -d phone-preview-apk ]; then
  phone_package=com.longzhousz.ami.phonepreview
  phone_apk=$(find phone-preview-apk -name '*.apk' -print -quit)
  test -n "$phone_apk"
  sha256sum "$phone_apk" > preview-smoke-results/phone-apk-sha256.txt
  adb install -r "$phone_apk"
  adb shell am start -W -n "$phone_package/com.longzhousz.ami.PhonePlayerActivity"
  python3 .github/scripts/android-wait-for-window.py preview-smoke-results/phone-launch.xml "$phone_package" '让 AMI 跟随网易云'
  adb exec-out screencap -p > preview-smoke-results/phone-connect.png
  python3 - <<'PYTHON'
import re
import subprocess
import xml.etree.ElementTree as ET
node = next(n for n in ET.parse('preview-smoke-results/phone-launch.xml').iter('node') if n.get('text') == '连接手机上的网易云')
x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True)
PYTHON
  # The app opens the system permission page; the user grants access there.
  adb shell uiautomator dump /sdcard/ami-phone-permission.xml
  adb pull /sdcard/ami-phone-permission.xml preview-smoke-results/phone-permission.xml
  python3 - <<'PYTHON'
import xml.etree.ElementTree as ET
nodes = list(ET.parse('preview-smoke-results/phone-permission.xml').iter('node'))
assert any(n.get('package') == 'com.android.settings' for n in nodes), 'Notification access settings did not open'
assert any('AMI' in (n.get('text', '') + n.get('content-desc', '')) for n in nodes), 'AMI listener is missing from the system permission page'
PYTHON
  adb exec-out screencap -p > preview-smoke-results/phone-permission.png
  adb shell input keyevent KEYCODE_BACK
  python3 .github/scripts/android-wait-for-window.py preview-smoke-results/phone-return.xml "$phone_package" '让 AMI 跟随网易云'
  if adb logcat -b crash -d | grep -F "Process: $phone_package,"; then
    echo 'The optimized phone preview crashed' >&2
    exit 1
  fi
fi
