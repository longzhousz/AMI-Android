#!/usr/bin/env bash
set -euo pipefail
# Read private files as the debug app, avoiding external-storage mount restrictions.
collect_screenshots() {
  mkdir -p "$GITHUB_WORKSPACE/device-screenshots"
  for name in empty-library library-songs favorites-songs library-albums album-tracks settings-dark lyrics-immersive lyrics-large-font lyrics-untimed lyrics-player-window translation-models; do
    destination="$GITHUB_WORKSPACE/device-screenshots/$name.png"
    if ! adb exec-out run-as com.longzhousz.ami cat "files/qa/$name.png" > "$destination"; then
      rm -f "$destination"
    fi
  done
}
trap collect_screenshots EXIT
cd "$GITHUB_WORKSPACE"
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
