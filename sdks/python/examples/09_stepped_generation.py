#!/usr/bin/env python3
"""09 · Stepped generation — one step at a time instead of a stream.

A client created with `generation="stepped"` asks for a session that generates
only when told to. Each `step()` sends the model's state, a list of commands and
every frame pushed since the previous step, and resolves with what the model
produced on each output track — as numpy arrays, not a video stream. A step
the model ran but failed still resolves, with `error` set; `files` names any
extra files the model kept with it.

Pushing into an input track does not send anything in a stepped session: the
frame waits for the next step. A runtime without stepped generation ignores the
request, so `connect()` checks and raises `GenerationModeMismatchError` rather
than leaving this waiting on a stream.

    REACTOR_MODEL=owner/model uv run python examples/09_stepped_generation.py

Set REACTOR_INPUT_TRACK to a sendonly video track the model declares to push a
frame into every step, and REACTOR_STEP_COMMAND to a command the model takes
with no arguments to run it in every step. REACTOR_PROMPT sets the state's
`prompt`.

Docs: https://docs.reactor.inc/concepts/tracks
"""

from __future__ import annotations

import asyncio
import os
import sys

from reactor_sdk import DEFAULT_API_URL, Reactor, StepCommand

API_KEY = os.environ.get("REACTOR_API_KEY")
MODEL = os.environ.get("REACTOR_MODEL")
STEPS = int(os.environ.get("REACTOR_STEPS", "3"))
PROMPT = os.environ.get("REACTOR_PROMPT", "a lighthouse in a storm")
INPUT_TRACK = os.environ.get("REACTOR_INPUT_TRACK")
COMMAND = os.environ.get("REACTOR_STEP_COMMAND")
WIDTH, HEIGHT = 320, 180


def frame(seq: int) -> bytes:
    """A solid BGRA frame whose colour follows `seq`."""
    return bytes([seq * 7 % 256, seq * 13 % 256, seq * 29 % 256, 255]) * (WIDTH * HEIGHT)


async def main() -> None:
    if not MODEL:
        sys.exit("set REACTOR_MODEL to a model with stepped generation, as owner/name")
    if not API_KEY and os.environ.get("REACTOR_LOCAL") != "1":
        sys.exit("set REACTOR_API_KEY — https://www.reactor.inc/account/api-keys")

    reactor = Reactor(
        model_name=MODEL,
        api_key=API_KEY,
        api_url=os.environ.get("REACTOR_API_URL", DEFAULT_API_URL),
        local=os.environ.get("REACTOR_LOCAL") == "1",
        generation="stepped",
    )

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        source = await reactor.track(INPUT_TRACK).publish() if INPUT_TRACK else None

        for seq in range(STEPS):
            if source is not None:
                # Buffered, not sent: the step below carries it.
                source.push_frame(frame(seq), width=WIDTH, height=HEIGHT)

            result = await reactor.step(
                # State on the first step only: the model keeps it until changed.
                state={"prompt": PROMPT} if seq == 0 else None,
                commands=[StepCommand(COMMAND)] if COMMAND else [],
            )
            if result.error is not None:
                print(f"step {result.step}: the model failed it: {result.error}")
            for track in result.tracks:
                shapes = ", ".join(str(f.array.shape) for f in track.frames)
                print(
                    f"step {result.step}: {track.name} ({track.kind.value}) "
                    f"{len(track.frames)} frame(s) {shapes}"
                )
            for file in result.files:
                print(f"step {result.step}: kept {file.name} ({file.content_type}, {file.size} B)")


if __name__ == "__main__":
    asyncio.run(main())
