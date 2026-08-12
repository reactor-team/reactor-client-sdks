#!/usr/bin/env python3
"""
Pause / resume example — demonstrate track subscription control.

Connects to a model, subscribes to a named recvonly track, measures incoming
frame rate, then pauses the track (no frames delivered), waits, resumes it,
and prints frame counts for each phase so you can verify frames stop / restart.

Timeline (all durations configurable via flags):

    0s                     receive_secs
    |——— receiving frames ——|
                            |——— PAUSED (no frames) ———|
                                                        |——— resumed ———|
                                                        resume_secs    done

Usage:
    python -m examples.pause_resume --track video_output

    # Custom timing
    python -m examples.pause_resume --track video_output \
        --receive 5 --pause 5 --resume 5

    # Interactive: press Enter to pause, Enter again to resume
    python -m examples.pause_resume --track video_output --interactive

Environment variables (overridden by flags):
    REACTOR_API_URL, REACTOR_MODEL, REACTOR_JWT, REACTOR_LOCAL
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from .reactor_client import make_reactor


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Demonstrate Reactor track pause/resume")
    p.add_argument(
        "--track", metavar="NAME", required=True, help="Name of the recvonly track to pause/resume"
    )
    p.add_argument(
        "--receive",
        metavar="SECS",
        type=float,
        default=5.0,
        help="Seconds to receive frames before pausing (default: 5)",
    )
    p.add_argument(
        "--pause",
        metavar="SECS",
        type=float,
        default=5.0,
        help="Seconds to keep the track paused (default: 5)",
    )
    p.add_argument(
        "--resume",
        metavar="SECS",
        type=float,
        default=5.0,
        help="Seconds to receive frames after resuming (default: 5)",
    )
    p.add_argument(
        "--interactive",
        action="store_true",
        help="Wait for Enter key press instead of using fixed timers",
    )
    p.add_argument("--model", metavar="NAME")
    p.add_argument("--api-url", metavar="URL")
    p.add_argument("--jwt", metavar="TOKEN")
    p.add_argument("--local", action="store_true", default=None)
    return p.parse_args()


async def _wait_enter(prompt: str) -> None:
    """Print prompt and wait for Enter in a non-blocking way."""
    loop = asyncio.get_event_loop()
    print(prompt, end=" ", flush=True)
    await loop.run_in_executor(None, input)


async def main() -> None:
    args = _parse_args()

    reactor = make_reactor(
        api_url=args.api_url,
        model_name=args.model,
        jwt=args.jwt,
        local=args.local if args.local else None,
    )

    # Frame counters per phase
    phase_counts: dict[str, int] = {"receiving": 0, "paused": 0, "resumed": 0}
    current_phase = "receiving"
    last_frame_time: float | None = None

    def on_frame(data: bytes, width: int, height: int) -> None:
        nonlocal last_frame_time
        phase_counts[current_phase] += 1
        last_frame_time = time.monotonic()

    reactor.on("frame", on_frame)
    reactor.on("error", lambda e: print(f"[error] {e}", file=sys.stderr))

    ready = asyncio.Event()
    reactor.on("status_changed", lambda s: ready.set() if s == "ready" else None)

    print("Connecting…", file=sys.stderr)
    await reactor.connect()
    print("Waiting for ready…", file=sys.stderr)
    await asyncio.wait_for(ready.wait(), timeout=60)
    print("Ready.", file=sys.stderr)

    # ── Phase 1: receive ──────────────────────────────────────────────────────
    current_phase = "receiving"
    t_start = time.monotonic()
    print(f"\n[Phase 1] Receiving frames from '{args.track}'…")

    if args.interactive:
        await _wait_enter("Press Enter to pause the track →")
    else:
        await asyncio.sleep(args.receive)

    elapsed = time.monotonic() - t_start
    fps = phase_counts["receiving"] / elapsed if elapsed > 0 else 0.0
    print(f"  received {phase_counts['receiving']} frames in {elapsed:.1f}s  ({fps:.1f} fps)")

    # ── Phase 2: pause ────────────────────────────────────────────────────────
    print(f"\n[Phase 2] Pausing '{args.track}'…")
    await reactor.pause_track(args.track)
    print(f"  Track paused.  (frames during pause = {phase_counts['paused']})")

    current_phase = "paused"
    t_pause = time.monotonic()

    if args.interactive:
        await _wait_enter("Press Enter to resume the track →")
    else:
        await asyncio.sleep(args.pause)

    elapsed_pause = time.monotonic() - t_pause
    print(
        f"  Paused for {elapsed_pause:.1f}s — "
        f"frames received during pause: {phase_counts['paused']}"
    )

    # ── Phase 3: resume ───────────────────────────────────────────────────────
    print(f"\n[Phase 3] Resuming '{args.track}'…")
    await reactor.resume_track(args.track)
    print("  Track resumed.")

    current_phase = "resumed"
    t_resume = time.monotonic()

    if args.interactive:
        await _wait_enter("Press Enter to disconnect →")
    else:
        await asyncio.sleep(args.resume)

    elapsed_resume = time.monotonic() - t_resume
    fps_resume = phase_counts["resumed"] / elapsed_resume if elapsed_resume > 0 else 0.0
    print(
        f"  received {phase_counts['resumed']} frames in {elapsed_resume:.1f}s  "
        f"({fps_resume:.1f} fps)"
    )

    # ── Summary ───────────────────────────────────────────────────────────────
    print("\n── Summary ─────────────────────────────────")
    print(f"  Phase 1 (receiving): {phase_counts['receiving']} frames")
    print(f"  Phase 2 (paused):    {phase_counts['paused']} frames  ← should be 0")
    print(f"  Phase 3 (resumed):   {phase_counts['resumed']} frames")
    if phase_counts["paused"] > 0:
        print("  WARNING: frames arrived while track was paused!", file=sys.stderr)

    await reactor.disconnect()
    reactor.close()


if __name__ == "__main__":
    asyncio.run(main())
