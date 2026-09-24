#!/usr/bin/env bash
#
# Runs one endurance scenario on a device and brings its report back.
#
# Two things here are Android's and no other binding's:
#
#   * The suite runs in a process on a device. Its reports are written to app-private external
#     storage and have to be pulled off afterwards — a report that exists only inside a sandbox on
#     an emulator about to be destroyed is a report nobody reads.
#   * There is no attaching a debugger as the suite's direct parent. The equivalent evidence on
#     Android is the tombstone the platform writes for a native crash plus the logcat around it,
#     both collected here on failure. See scripts/android-endurance-diagnostics.sh.
#
# Usage: scripts/android-endurance.sh <ScenarioClassName>
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: android-endurance.sh <ScenarioClassName>   e.g. PublishChurnTest" >&2
  exit 1
fi

SCENARIO="$1"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS="$REPO_ROOT/sdks/android/endurance-tests/endurance-results"
PACKAGE="inc.reactor.sdk.android.endurance"

"$REPO_ROOT/scripts/require-android-device.sh"

# Cleared before the run, not after: a pull that silently returns the previous run's files is a
# report that describes a run nobody performed.
rm -rf "$RESULTS"
mkdir -p "$RESULTS"
adb shell "rm -rf /sdcard/Android/data/$PACKAGE.test/files/endurance-results" >/dev/null 2>&1 || true

# Logcat from before the run starts, so a crash during startup is covered too.
adb logcat -c || true

status=0
gradle --project-dir "$REPO_ROOT/sdks/android" --console=plain \
  ":endurance-tests:connectedDebugAndroidTest" \
  "-Pandroid.testInstrumentationRunnerArguments.class=$PACKAGE.$SCENARIO" || status=$?

# Pulled whichever way the run ended. The report from a failed run is the one that matters.
adb pull "/sdcard/Android/data/$PACKAGE.test/files/endurance-results/." "$RESULTS/" >/dev/null 2>&1 \
  || echo "warning: no reports on the device to pull" >&2

if [ "$status" -ne 0 ]; then
  "$REPO_ROOT/scripts/android-endurance-diagnostics.sh" "$SCENARIO" "$RESULTS"
fi

exit "$status"
