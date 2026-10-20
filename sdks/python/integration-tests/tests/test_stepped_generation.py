"""Stepped generation against a real runtime — `generation="stepped"`, `step()`.

Needs a model with stepped generation, which `reactor/echo` is not yet: set
REACTOR_STEPPED_MODEL_NAME (and, for a local runtime, REACTOR_LOCAL=true with
REACTOR_API_URL) or every test here is skipped. REACTOR_STEPPED_INPUT_TRACK
names a sendonly video track to push into; without it the input test skips.

The streamed `reactor/echo` model doubles as the negative case: a runtime that
does not start a stepped session must fail `connect()` typed, not stream.
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


async def test_a_runtime_that_streams_fails_a_stepped_connect() -> None:
    client = stepped(MODEL_NAME)
    try:
        with pytest.raises(GenerationModeMismatchError):
            await paced_connect(client)
    finally:
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
