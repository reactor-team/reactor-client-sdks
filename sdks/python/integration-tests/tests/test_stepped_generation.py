"""Stepped generation against a real runtime — `generation="stepped"`, `step()`.

The generic tests need a model with stepped generation: set
REACTOR_STEPPED_MODEL_NAME (and, for a local runtime, REACTOR_LOCAL=true with
REACTOR_API_URL) or they are skipped. REACTOR_STEPPED_INPUT_TRACK names a
sendonly video track to push into; without it the input test skips.

`reactor/echo` always runs: a stepped connect to it must step or fail, never
stream. Echo steps from 1.10.0; until that release is deployed everywhere this
suite runs, an older echo refuses the start, and the test accepts either.
"""

from __future__ import annotations

import os

import numpy as np
import pytest
from conftest import API_KEY, API_URL, LOCAL, MODEL_NAME, paced_connect, solid_rgb_frame

from reactor_sdk import (
    GenerationModeMismatchError,
    MessageTooLargeError,
    Reactor,
    ReactorStatus,
    SessionTerminalError,
    StepResult,
)

STEPPED_MODEL = os.environ.get("REACTOR_STEPPED_MODEL_NAME")
INPUT_TRACK = os.environ.get("REACTOR_STEPPED_INPUT_TRACK")

needs_stepped_model = pytest.mark.skipif(
    not STEPPED_MODEL, reason="REACTOR_STEPPED_MODEL_NAME not set"
)


def stepped(model_name: str) -> Reactor:
    return Reactor(
        model_name=model_name,
        api_key=API_KEY,
        api_url=API_URL,
        local=LOCAL,
        generation="stepped",
    )


async def test_a_stepped_connect_to_echo_steps_or_fails() -> None:
    # Echo from 1.10.0 runs the default step loop, so the session is stepped:
    # a step carries the pushed webcam frame and comes back inverted on
    # main_video. An older echo drives its own run(); a runtime from 3.9.0
    # refuses that stepped start, and the error names the reason the platform
    # recorded, START_REFUSED. A runtime before 3.9.0 streams instead, which
    # the client reports as a mode mismatch. Either refusal is right; a stepped
    # connect that silently streams is not, and the swift and android suites
    # accept the same outcomes.
    client = stepped(MODEL_NAME)
    connected = False
    try:
        try:
            await paced_connect(client)
        except (GenerationModeMismatchError, SessionTerminalError) as refused:
            if isinstance(refused, SessionTerminalError):
                assert "(START_REFUSED)" in str(refused)
            return
        connected = True
        assert client.status == ReactorStatus.READY

        webcam = await client.track("webcam").publish()
        webcam.push_frame(solid_rgb_frame(64, 64, (10, 20, 30)))
        result = await client.step(state={"effect": "invert"})

        assert result.error is None
        [frame] = result.track("main_video").frames
        assert frame.array.shape == (64, 64, 3)
        assert frame.array[32, 32].tolist() == [245, 235, 225]
    finally:
        if connected:
            await client.disconnect()
        client.close()


@needs_stepped_model
async def test_steps_resolve_in_order() -> None:
    client = stepped(STEPPED_MODEL)
    try:
        await paced_connect(client)
        assert client.status == ReactorStatus.READY

        first = await client.step(state={"prompt": "a lighthouse in a storm"})
        second = await client.step()

        assert isinstance(first, StepResult)
        assert second.step == first.step + 1
        assert first.elapsed >= 0
        assert first.error is None
        for file in first.files:
            assert file.name and file.size >= 0
        for track in first.tracks:
            assert track.rate >= 0
            for frame in track.frames:
                assert frame.array.shape == frame.shape
                assert frame.array.dtype == np.dtype(frame.dtype)
    finally:
        await client.disconnect()
        client.close()


@needs_stepped_model
@pytest.mark.skipif(not INPUT_TRACK, reason="REACTOR_STEPPED_INPUT_TRACK not set")
async def test_pushed_frames_ride_the_next_step() -> None:
    client = stepped(STEPPED_MODEL)
    try:
        await paced_connect(client)
        source = await client.track(INPUT_TRACK).publish()
        source.push_frame(solid_rgb_frame(64, 64, (255, 0, 0)))
        result = await client.step(state={"prompt": "repaint it"})
        assert result.step >= 1

        # Proof the push rode the step whatever the model makes of it: a frame
        # over the 64 MiB a chunked data channel carries can only fail a step
        # it went into. A push dropped on the way would let this step through.
        source.push_frame(solid_rgb_frame(5000, 5000, (255, 0, 0)))
        with pytest.raises(MessageTooLargeError):
            await client.step()
    finally:
        await client.disconnect()
        client.close()
