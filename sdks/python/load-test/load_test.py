"""Load test: hold N concurrent sessions open, in steps (default 10, 25, 50).

Each session publishes a small video track continuously and receives the
model's video back, so it keeps producing client and runtime stats for as
long as it is open. Sessions are opened one at a time, paced under the
sessions-per-minute quota, and stay open across steps: going from 10 to 25
opens 15 more.

If the backend reports it has no capacity left, the test stops adding
sessions, holds what it has for the current step's duration, and skips the
remaining steps. That is a result (the environment's ceiling), not a crash.

Writes, under <out>/<timestamp>/:
  sessions.csv  one row per session attempt: session id, step, open/close
                times (UTC), frames sent/received, whether it dropped, error
  steps.csv     per step: target, sessions open, when it started holding
                and when it ended (UTC)
  summary.md    the same, as a short Markdown report

Usage (from sdks/python, with the API key exported):
  export INTEGRATION_TESTS_REACTOR_API_KEY=<key>
  export REACTOR_API_URL=<api url>
  .venv/bin/python load-test/load_test.py --steps 10,25,50 --hold-minutes 10
REACTOR_API_URL defaults to the SDK's default (production).
Exits non-zero if a step's target was not reached or a session dropped.
Ctrl+C stops early, closes every session and still writes the files.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import os
import signal
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

from reactor_sdk import DEFAULT_API_URL, Reactor
from reactor_sdk.errors import RateLimitedError

API_URL = os.environ.get("REACTOR_API_URL") or DEFAULT_API_URL
MODEL = os.environ.get("REACTOR_MODEL_NAME") or "reactor/echo"
API_KEY = os.environ.get("INTEGRATION_TESTS_REACTOR_API_KEY")

FPS = 15
WIDTH, HEIGHT = 128, 128
PUBLISH_TRACK = "webcam"
RECEIVE_TRACK = "main_video"
OPEN_ATTEMPTS = 5
CLOSE_TIMEOUT_S = 15


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def is_no_capacity(error: Exception) -> bool:
    return "no available capacity" in str(error)


@dataclass
class Session:
    index: int
    step: int
    client: Reactor
    session_id: str = ""
    opened_at: str = ""
    closed_at: str = ""
    frames_sent: int = 0
    frames_received: int = 0
    dropped: bool = False
    error: str = ""
    track: Any = None


@dataclass
class StepResult:
    target: int
    reached: int
    ramp_started_at: str
    hold_started_at: str = ""
    ended_at: str = ""
    note: str = ""


class LoadTest:
    def __init__(
        self, steps: list[int], hold_s: float, connect_interval_s: float, out: Path
    ) -> None:
        self.steps = steps
        self.hold_s = hold_s
        self.connect_interval_s = connect_interval_s
        self.out = out
        self.sessions: list[Session] = []
        self.step_results: list[StepResult] = []
        self.stop = asyncio.Event()
        self.no_capacity = False
        self.frame = np.full((HEIGHT, WIDTH, 3), (90, 140, 200), dtype=np.uint8)

    def open_sessions(self) -> list[Session]:
        return [s for s in self.sessions if s.track is not None and not s.dropped]

    async def sleep_or_stop(self, seconds: float) -> None:
        try:
            await asyncio.wait_for(self.stop.wait(), timeout=max(0.0, seconds))
        except asyncio.TimeoutError:
            pass

    async def open_one(self, step: int) -> None:
        client = Reactor(model_name=MODEL, api_key=API_KEY, api_url=API_URL)
        session = Session(index=len(self.sessions) + 1, step=step, client=client)
        self.sessions.append(session)

        def on_status(status: object) -> None:
            if str(getattr(status, "value", status)) != "disconnected":
                return
            if session.track is not None and not self.stop.is_set() and not session.dropped:
                session.dropped = True
                session.closed_at = utc_now()
                print(f"  ! session {session.index} ({session.session_id}) dropped", flush=True)

        def on_frame(_frame: object) -> None:
            session.frames_received += 1

        client.on_status(on_status)
        client.track(RECEIVE_TRACK).on_frame(on_frame)

        for attempt in range(1, OPEN_ATTEMPTS + 1):
            if self.stop.is_set():
                session.error = "stopped before opening"
                break
            try:
                await client.connect()
                session.session_id = client.session_id or ""
                session.track = await client.publish_track(PUBLISH_TRACK)
                session.opened_at = utc_now()
                return
            except RateLimitedError as error:
                await self.end_server_session(client)
                if is_no_capacity(error):
                    session.error = "no available capacity"
                    self.no_capacity = True
                    break
                session.error = f"rate limited: {error}"
                print(
                    f"  rate limited opening session {session.index} (attempt {attempt}), retrying",
                    flush=True,
                )
                await self.sleep_or_stop(2 * attempt)
            except Exception as error:  # noqa: BLE001 - any open failure is a result to record
                session.error = f"{type(error).__name__}: {error}"
                await self.end_server_session(client)
                break
        print(f"  x could not open session {session.index}: {session.error}", flush=True)
        client.close()

    @staticmethod
    async def end_server_session(client: Reactor) -> None:
        """Disconnect a client whose open attempt failed partway, e.g. connect()
        succeeded but publish_track() did not. close() alone would leave that
        session holding a backend slot until it times out, which would make the
        environment's capacity look lower than it is."""
        if client.status == "disconnected":
            return
        try:
            await asyncio.wait_for(client.disconnect(), timeout=CLOSE_TIMEOUT_S)
        except Exception:  # noqa: BLE001 - best effort; the attempt already failed
            pass

    async def push_frames(self) -> None:
        period = 1 / FPS
        next_tick = time.monotonic()
        while not self.stop.is_set():
            for session in self.open_sessions():
                try:
                    session.track.push_frame(self.frame)
                    session.frames_sent += 1
                except Exception as error:  # noqa: BLE001
                    session.error = session.error or f"push_frame: {error}"
            next_tick += period
            await asyncio.sleep(max(0.0, next_tick - time.monotonic()))

    def status_line(self, label: str) -> str:
        live = self.open_sessions()
        dropped = sum(1 for s in self.sessions if s.dropped)
        failed = sum(1 for s in self.sessions if s.track is None and s.error)
        receiving = sum(1 for s in live if s.frames_received > 0)
        return (
            f"[{utc_now()}] {label}: open={len(live)} receiving_video={receiving} "
            f"dropped={dropped} failed_to_open={failed}"
        )

    async def run_step(self, target: int) -> StepResult:
        print(f"\n=== step {target}: opening sessions up to {target} ===", flush=True)
        result = StepResult(target=target, reached=0, ramp_started_at=utc_now())
        failures_this_step = 0
        while len(self.open_sessions()) < target and not self.stop.is_set():
            before = len(self.open_sessions())
            await self.open_one(target)
            if self.no_capacity:
                result.note = f"no capacity left after {len(self.open_sessions())} sessions"
                print(f"  {result.note}: holding what is open, skipping later steps", flush=True)
                break
            if len(self.open_sessions()) == before:
                failures_this_step += 1
                if failures_this_step >= 5:
                    result.note = "gave up after 5 failed opens"
                    print(f"  {result.note}", flush=True)
                    break
            await self.sleep_or_stop(self.connect_interval_s)

        result.hold_started_at = utc_now()
        print(self.status_line(f"step {target} holding"), flush=True)
        deadline = time.monotonic() + self.hold_s
        while time.monotonic() < deadline and not self.stop.is_set():
            await self.sleep_or_stop(min(30.0, deadline - time.monotonic()))
            print(self.status_line(f"step {target}"), flush=True)
        result.reached = len(self.open_sessions())
        result.ended_at = utc_now()
        if self.stop.is_set() and not result.note:
            result.note = "stopped early"
        return result

    async def run(self) -> None:
        pusher = asyncio.create_task(self.push_frames())
        try:
            for target in self.steps:
                if self.stop.is_set() or self.no_capacity:
                    break
                self.step_results.append(await self.run_step(target))
        finally:
            self.stop.set()
            await pusher
            await self.close_all()
            self.write_files()

    async def close_all(self) -> None:
        to_close = [s for s in self.sessions if s.track is not None]
        print(f"\nClosing {len(to_close)} session(s)...", flush=True)

        async def close(session: Session) -> None:
            try:
                await asyncio.wait_for(session.client.disconnect(), timeout=CLOSE_TIMEOUT_S)
            except Exception as error:  # noqa: BLE001
                session.error = session.error or f"disconnect: {type(error).__name__}: {error}"
            session.client.close()
            session.closed_at = session.closed_at or utc_now()

        await asyncio.gather(*(close(s) for s in to_close))

    def passed(self) -> bool:
        reached_all = len(self.step_results) == len(self.steps) and all(
            r.reached >= r.target for r in self.step_results
        )
        return reached_all and not any(s.dropped for s in self.sessions)

    def write_files(self) -> None:
        with open(self.out / "sessions.csv", "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(
                [
                    "index",
                    "session_id",
                    "step",
                    "opened_at",
                    "closed_at",
                    "frames_sent",
                    "frames_received",
                    "dropped",
                    "error",
                ]
            )
            for s in self.sessions:
                writer.writerow(
                    [
                        s.index,
                        s.session_id,
                        s.step,
                        s.opened_at,
                        s.closed_at,
                        s.frames_sent,
                        s.frames_received,
                        s.dropped,
                        s.error,
                    ]
                )
        with open(self.out / "steps.csv", "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(
                ["target", "reached", "ramp_started_at", "hold_started_at", "ended_at", "note"]
            )
            for r in self.step_results:
                writer.writerow(
                    [r.target, r.reached, r.ramp_started_at, r.hold_started_at, r.ended_at, r.note]
                )

        opened = [s for s in self.sessions if s.session_id]
        lines = [
            f"## Load test: {MODEL} at {API_URL}",
            "",
            f"Result: **{'passed' if self.passed() else 'did not reach every step'}**",
            "",
            "| Step | Sessions open | Holding from (UTC) | Until (UTC) | Note |",
            "|---:|---:|---|---|---|",
        ]
        for r in self.step_results:
            lines.append(
                f"| {r.target} | {r.reached} | {r.hold_started_at} | {r.ended_at} | {r.note} |"
            )
        skipped = self.steps[len(self.step_results) :]
        if skipped:
            lines.append(f"| {', '.join(map(str, skipped))} | not run | | | |")
        lines += [
            "",
            f"- Sessions opened: {len(opened)}",
            f"- Failed to open: {sum(1 for s in self.sessions if not s.session_id)}",
            f"- Dropped while open: {sum(1 for s in self.sessions if s.dropped)}",
            f"- Never received video: {sum(1 for s in opened if s.frames_received == 0)}",
            "",
            "Session ids are in `sessions.csv` next to this summary (the artifact, in CI).",
        ]
        (self.out / "summary.md").write_text("\n".join(lines) + "\n")
        print("\n" + "\n".join(lines), flush=True)
        print(f"\nFiles in {self.out}/", flush=True)


def parse_steps(text: str) -> list[int]:
    steps = [int(s) for s in text.replace(" ", "").split(",") if s]
    if not steps or any(s <= 0 for s in steps) or steps != sorted(steps):
        raise SystemExit("--steps must be positive session counts, going up (e.g. 10,25,50).")
    return steps


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--steps", default="10,25,50", help="session counts to hold, going up (default 10,25,50)"
    )
    parser.add_argument(
        "--hold-minutes", type=float, default=10, help="minutes to hold each step (default 10)"
    )
    parser.add_argument(
        "--connect-interval",
        type=float,
        default=1.0,
        help="seconds between opening sessions (default 1.0, i.e. 60/min, under the 100/min quota)",
    )
    parser.add_argument(
        "--out", default="load-test-results", help="output directory (default load-test-results)"
    )
    args = parser.parse_args()

    if not API_KEY:
        raise SystemExit("Export INTEGRATION_TESTS_REACTOR_API_KEY first.")
    steps = parse_steps(args.steps)
    out = Path(args.out) / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True, exist_ok=True)
    print(
        f"Load test against {API_URL} ({MODEL}): steps {steps}, {args.hold_minutes:g} min each, "
        f"{FPS} fps {WIDTH}x{HEIGHT} video per session. Started {utc_now()}.",
        flush=True,
    )

    test = LoadTest(steps, args.hold_minutes * 60, args.connect_interval, out)

    async def runner() -> None:
        loop = asyncio.get_running_loop()

        def request_stop() -> None:
            if not test.stop.is_set():
                print("\nStopping...", flush=True)
                test.stop.set()

        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, request_stop)
        await test.run()

    asyncio.run(runner())
    return 0 if test.passed() else 1


if __name__ == "__main__":
    sys.exit(main())
