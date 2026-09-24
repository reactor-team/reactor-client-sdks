#!/usr/bin/env bash
#
# What Android gives you instead of a debugger's backtrace.
#
# Every other binding's endurance job runs the suite as gdb's or lldb's direct inferior, because
# the crash being hunted dies on a Rust or libwebrtc thread and the runtime's own report names it
# <no frame>. That is not available here: the suite runs inside an app process on a device, and the
# debugger would be on the host.
#
# The platform's equivalent is a tombstone — a full all-thread native backtrace written by
# debuggerd when a process dies on a signal — plus the logcat around it. Both are collected here,
# into the same native-crash-diagnostics.json the other suites produce, so a reader who has seen
# one knows how to read this one.
#
# Usage: android-endurance-diagnostics.sh <ScenarioClassName> <results-dir>
set -euo pipefail

SCENARIO="${1:?scenario}"
RESULTS="${2:?results directory}"
mkdir -p "$RESULTS"

logcat="$(adb logcat -d -v time 2>/dev/null | tail -c 200000 || true)"

# Tombstones need root on a production build; on an emulator `adb root` succeeds and they are the
# single most useful artifact here. Absent is normal on a physical device, so it is reported as
# absent rather than treated as an error.
tombstone=""
if adb root >/dev/null 2>&1; then
  adb wait-for-device
  newest="$(adb shell 'ls -t /data/tombstones/ 2>/dev/null | head -1' | tr -d '\r')"
  if [ -n "$newest" ]; then
    tombstone="$(adb shell "cat /data/tombstones/$newest" 2>/dev/null | tail -c 100000 || true)"
  fi
fi

SCENARIO="$SCENARIO" LOGCAT="$logcat" TOMBSTONE="$tombstone" RESULTS="$RESULTS" python3 - <<'PY'
import json, os, pathlib

tombstone = os.environ.get("TOMBSTONE") or None
pathlib.Path(os.environ["RESULTS"], "native-crash-diagnostics.json").write_text(
    json.dumps(
        {
            "what_is_this_file": (
                "An endurance run failed on a device. If it died on a signal rather than on an "
                "assertion, the faulting thread is usually a Rust or libwebrtc one that ART knows "
                "nothing about. Read 'tombstone' first — debuggerd writes an all-thread native "
                "backtrace there, which is Android's equivalent of the gdb backtrace the other "
                "SDKs' suites capture. 'logcat' is the surrounding context. A null tombstone means "
                "the device would not give up /data/tombstones, which needs root and is normal on "
                "a physical device; reproduce on an emulator to get one."
            ),
            "scenario": os.environ["SCENARIO"],
            "run_url": (
                f"{os.environ.get('GITHUB_SERVER_URL', '')}/{os.environ.get('GITHUB_REPOSITORY', '')}"
                f"/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"
                if os.environ.get("GITHUB_RUN_ID")
                else None
            ),
            "sha": os.environ.get("GITHUB_SHA"),
            "reproduce": (
                f"ENDURANCE_DURATION_MINUTES={os.environ.get('ENDURANCE_DURATION_MINUTES', '5')} "
                f"mise run test:android:endurance -- {os.environ['SCENARIO']}"
            ),
            "tombstone": tombstone,
            "logcat": os.environ.get("LOGCAT") or None,
        },
        indent=2,
    )
)
PY

echo "wrote $RESULTS/native-crash-diagnostics.json"
