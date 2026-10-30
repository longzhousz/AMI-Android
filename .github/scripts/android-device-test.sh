#!/usr/bin/env bash
set -euo pipefail
# Read private files as the debug app, avoiding external-storage mount restrictions.
collect_screenshots() {
  mkdir -p "$GITHUB_WORKSPACE/device-screenshots"
  adb logcat -d > "$GITHUB_WORKSPACE/device-screenshots/logcat.txt" || true
  if ! adb exec-out run-as com.longzhousz.ami tar -cf - -C files/qa performance > "$GITHUB_WORKSPACE/device-screenshots/performance.tar"; then
    rm -f "$GITHUB_WORKSPACE/device-screenshots/performance.tar"
  fi
  for name in empty-library library-songs favorites-songs library-albums album-tracks settings-dark lyrics-immersive lyrics-large-font lyrics-untimed lyrics-word-timing lyrics-player-window translation-models player-lyrics-redesign player-artwork-mid player-artwork-expanded player-collapse-handoff player-minibar-corners cloud-connect cloud-playlists cloud-tracks phone-lyrics phone-artwork online-library online-favorites online-discovery shelf-album-expanded shelf-seen-preview listening-month listening-year listening-calendar listening-artists listening-hours listening-dark listening-empty listening-settings; do
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
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || result=$?
python3 .github/scripts/android-check-test-results.py || result=$?
python3 .github/scripts/android-background-recovery.py || result=$?
exit "$result"
