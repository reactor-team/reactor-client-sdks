# Python SDK for Reactor

[![PyPI: reactor-sdk](https://img.shields.io/pypi/v/reactor-sdk.svg?label=reactor-sdk)](https://pypi.org/project/reactor-sdk/)
[![PyPI Downloads](https://img.shields.io/pypi/dm/reactor-sdk.svg?label=downloads)](https://pypi.org/project/reactor-sdk/)
[![build](https://img.shields.io/github/actions/workflow/status/reactor-team/reactor-client-sdks/ci.yml?branch=main)](https://github.com/reactor-team/reactor-client-sdks/actions)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](../../LICENSE)

Use this SDK to connect your Python app to a live [Reactor](https://reactor.inc)
model: send commands and receive real-time video and audio back. Built for
scripts, servers, and computer-vision pipelines — it authenticates directly
with your API key, server-side.

## Getting Started

```bash
pip install reactor-sdk
```

Requires Python 3.10+.

## Usage Example

```python
import asyncio
from reactor_sdk import Reactor, ReactorStatus

API_KEY = "..."  # Insert your API key here.

async def main():
    async with Reactor(model_name="my-model", api_key=API_KEY) as r:

        @r.on_status(ReactorStatus.READY)
        async def on_ready(status):
            await r.send_command("set_prompt", {"prompt": "a forest at dawn"})

        def on_frame(bgra, width, height, frame_id, timestamp_us, user_data):
            print(f"frame: {width}x{height}")

        r.on("frame", on_frame)

        await r.connect()
        await asyncio.sleep(30)  # keep the session open while frames arrive

asyncio.run(main())
```

## Documentation & Resources

See the [full documentation](https://docs.reactor.inc/sdk-reference/using-the-sdk#python) for platform
concepts and the other language SDKs.

## Samples

Runnable scripts in [`examples/`](examples/), each driven by
`REACTOR_API_URL` / `REACTOR_MODEL` / `REACTOR_JWT` / `REACTOR_LOCAL`
environment variables (see [`reactor_client.py`](examples/reactor_client.py)):

| Script | Demonstrates |
|---|---|
| [`main.py`](examples/main.py) | Minimal connect → send a command → disconnect. |
| [`push_video.py`](examples/push_video.py) | Stream generated frames into a `sendonly` video track. |
| [`push_audio.py`](examples/push_audio.py) | Stream a sine tone or a WAV file into a `sendonly` audio track. |
| [`pause_resume.py`](examples/pause_resume.py) | Pause and resume a `recvonly` track subscription. |
| [`record.py`](examples/record.py) | Request a clip or full-session recording and download the HLS segments. |
| [`frame_metadata.py`](examples/frame_metadata.py) | Read the per-frame metadata trailer off an incoming track. |
| [`frame_metadata_roundtrip.py`](examples/frame_metadata_roundtrip.py) | Tag outgoing frames and match them against the ones that come back. |
| [`metadata_publisher.py`](examples/metadata_publisher.py) | Publish tagged frames with no UI — the sending half of a two-process demo (pair with `pygame_app/`). |
| [`pygame_app/`](examples/pygame_app/) | A pygame application: live video display plus a dynamic control UI built from the model's declared capabilities. |

Run `main.py` directly; every other example (aside from `pygame_app/`,
which is its own standalone app — see its own
[README](examples/pygame_app/README.md)) imports its sibling
`reactor_client.py` with a relative import, so run it as a module instead
(both from `sdks/python/`):

```bash
REACTOR_MODEL=my-model REACTOR_JWT=<token> python examples/main.py

REACTOR_MODEL=my-model REACTOR_JWT=<token> python -m examples.push_video --track video_input
```

## Development

```bash
mise run lint:python     # ruff check + format
mise run test:python     # pytest
mise run build:wheel     # cargo build --release, then a wheel with it bundled
```

Tests skip themselves if the compiled library isn't present, so `pytest` runs
clean on a fresh checkout. `mise run build:wheel` without one still produces a
wheel, with a warning that it's pure-Python — fine for an editable install,
but not something to publish as a release.

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for everything
else (DCO, commit conventions, opening a PR).

## License

Apache-2.0 — see [`LICENSE`](../../LICENSE).
