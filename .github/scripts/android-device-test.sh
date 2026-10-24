#!/usr/bin/env bash
set -euo pipefail
# Read private files as the debug app, avoiding external-storage mount restrictions.
collect_screenshots() {
  mkdir -p "$GITHUB_WORKSPACE/device-screenshots"
  adb logcat -d > "$GITHUB_WORKSPACE/device-screenshots/logcat.txt" || true
  for name in empty-library library-songs favorites-songs library-albums album-tracks settings-dark lyrics-immersive lyrics-large-font lyrics-untimed lyrics-player-window translation-models; do
    destination="$GITHUB_WORKSPACE/device-screenshots/$name.png"
    if ! adb exec-out run-as com.longzhousz.ami cat "files/qa/$name.png" > "$destination"; then
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
  if adb shell 'test "$(cmd user is-user-unlocked 0)" = true && touch /sdcard/.ami-qa-ready && rm /sdcard/.ami-qa-ready'; then
    ready=true
    break
  fi
  sleep 5
done
if [ "$ready" != true ]; then
  echo 'Emulator user/storage did not become ready; no tests were run.' >&2
  exit 1
fi
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
python3 .github/scripts/android-check-test-results.py
