#!/usr/bin/env bash
# Refuses to run the instrumented suite when nothing is attached to run it on.
#
# This is the Android-specific way to be silently green, and the other SDKs do not have it. A
# missing secret is caught on the device by Live.apiKey(); a missing *device* means no test code
# runs at all, and `connectedAndroidTest` with no device attached is a Gradle failure whose
# message ("com.android.builder.testing.api.DeviceException: No connected devices!") reads like
# infrastructure noise rather than "this gate did not run". Checked here, before anything is
# built, so the reason is the first thing in the log.
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "::error::adb is not on PATH. Run 'mise run setup:android' first — the platform-tools" >&2
  echo "         package pinned in sdks/android/sdk-packages.txt is what provides it." >&2
  exit 1
fi

# `adb devices` prints a header line and then one line per device; a device that is present but
# unauthorised or still booting shows a state other than "device", and running against one fails
# later in a way that looks like a test failure.
ready="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"

if [ "$ready" -eq 0 ]; then
  echo "::error::No Android device or emulator is ready — the instrumented suite cannot run." >&2
  echo "" >&2
  echo "This suite gates CI and the release, so it fails here rather than reporting success" >&2
  echo "for tests that never executed." >&2
  echo "" >&2
  echo "  mise run setup:android:emulator   # the emulator and a 16 KB page-size system image" >&2
  echo "  # then start an AVD from that image and re-run" >&2
  echo "" >&2
  echo "Current adb state:" >&2
  adb devices >&2
  exit 1
fi

echo "$ready Android device(s) ready."
