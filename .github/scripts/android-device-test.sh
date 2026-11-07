#!/usr/bin/env bash
set -euo pipefail
# Read private files as the debug app, avoiding external-storage mount restrictions.
collect_screenshots() {
  mkdir -p "$GITHUB_WORKSPACE/device-screenshots"
  free -m > "$GITHUB_WORKSPACE/device-screenshots/host-memory.txt"
  sudo dmesg | tail -100 > "$GITHUB_WORKSPACE/device-screenshots/host-kernel.txt" || true
  if ! timeout 10s adb get-state > /dev/null 2>&1; then
    echo 'Emulator disconnected; preserving the incomplete test reports without waiting for screenshots.' >&2
    return
  fi
  timeout 15s adb logcat -d > "$GITHUB_WORKSPACE/device-screenshots/logcat.txt" || true
  if ! timeout 15s adb exec-out run-as com.longzhousz.ami tar -cf - -C files/qa performance > "$GITHUB_WORKSPACE/device-screenshots/performance.tar"; then
    rm -f "$GITHUB_WORKSPACE/device-screenshots/performance.tar"
  fi
  if ! timeout 15s adb exec-out run-as com.longzhousz.ami cat files/qa/pause-envelope.csv > "$GITHUB_WORKSPACE/device-screenshots/pause-envelope.csv"; then
    rm -f "$GITHUB_WORKSPACE/device-screenshots/pause-envelope.csv"
  fi
  for name in settings-home settings-storage settings-lyrics backup-restore-settings; do
    if ! timeout 10s adb get-state > /dev/null 2>&1; then break; fi
    destination="$GITHUB_WORKSPACE/device-screenshots/$name.png"
    if ! timeout 15s adb exec-out run-as com.longzhousz.ami cat "files/qa/$name.png" > "$destination"; then
      rm -f "$destination"
    fi
  done
}
trap collect_screenshots EXIT
cd "$GITHUB_WORKSPACE"
# boot_completed can precede Android 17's credential/storage readiness.
# UTP may otherwise return an empty, successful report without running tests.
ready=false
for attempt in {1..24}; do
  if adb shell 'touch /sdcard/.ami-qa-ready && rm /sdcard/.ami-qa-ready'; then
    ready=true
    break
  fi
  sleep 5
done
if [ "$ready" != true ]; then
  echo 'Emulator user/storage did not become ready; no tests were run.' >&2
  exit 1
fi
result=0
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.longzhousz.ami.InterfaceTest#titleOnlySettingsKeepBackupInformationAndSubmenuBackNavigation -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || result=$?
python3 .github/scripts/android-check-settings-focus.py || result=$?

exit "$result"
