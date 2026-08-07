<div align="center">

<img src="assets/banner.png" alt="Reactor Client SDKs" width="100%" />

**Client SDKs for real-time world models — connect, watch it run, steer it live.**

[🌐 Reactor](https://reactor.inc) · [⚙️ Runtime](https://github.com/reactor-team/reactor-runtime) · [🎥 WebRTC](https://github.com/reactor-team/reactor-webrtc) · [📖 Cookbook](https://github.com/reactor-team/reactor-cookbook)

[![CI](https://github.com/reactor-team/reactor-client-sdks/actions/workflows/ci.yml/badge.svg)](https://github.com/reactor-team/reactor-client-sdks/actions/workflows/ci.yml)
[![PyPI: reactor-sdk](https://img.shields.io/pypi/v/reactor-sdk.svg?label=reactor-sdk)](https://pypi.org/project/reactor-sdk/)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

</div>

---

Use these SDKs to connect your app to a live Reactor world model: receive
its streaming video, and send commands that steer what it generates while
it runs. Some models also accept your own audio/video as input over a
dedicated track.

```python
import asyncio
from reactor import Reactor

async def main():
    async with Reactor("https://api.reactor.inc", "my-model", jwt=TOKEN) as r:
        r.on("frame", lambda data, w, h, *_: render(data, w, h))  # live video, as it's generated
        await r.connect()

        r.send_command("set_prompt", {"text": "a neon-lit city at night"})  # steer it, live
        await asyncio.sleep(60)

asyncio.run(main())
```

That's the whole loop — connect, watch, steer — in under 15 lines. See
[`sdks/python/README.md`](sdks/python/README.md) for the full quick start
(`render` above is just "however your app displays a BGRA frame").

## Getting started

- 🐍 **[Python](sdks/python/README.md)** — install, quick start, full API reference.

## Documentation

- [`docs/concepts.md`](docs/concepts.md) — sessions and connection state,
  tracks and capabilities, application vs. runtime scope, callback
  threading. Start here.
- [`docs/messaging.md`](docs/messaging.md) — sending commands, receiving
  messages, capabilities negotiation, and track publish/pause/resume.
- [`docs/recording.md`](docs/recording.md) — clips and full-session
  recordings.
- [`docs/frame-metadata.md`](docs/frame-metadata.md) — tagging and reading
  per-frame metadata trailers on video tracks.
- [`docs/README.md`](docs/README.md) is the index if you're looking for
  something specific.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for dev setup, code style, commit
conventions, and how to open a pull request.

## Licensing

This repository is **Apache-2.0** licensed — see [`LICENSE`](LICENSE).
