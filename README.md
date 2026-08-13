<div align="center">

<img src="assets/banner.png" alt="Reactor Client SDKs" width="100%" />

**Client SDKs for real-time world models — connect, watch it run, steer it live.**

[🌐 Reactor](https://reactor.inc) · [📚 Docs](https://docs.reactor.inc) · [⚙️ Runtime](https://github.com/reactor-team/reactor-runtime) · [🎥 WebRTC](https://github.com/reactor-team/reactor-webrtc) · [📖 Cookbook](https://github.com/reactor-team/reactor-cookbook)

[![CI](https://github.com/reactor-team/reactor-client-sdks/actions/workflows/ci.yml/badge.svg)](https://github.com/reactor-team/reactor-client-sdks/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

</div>

---

Use these SDKs to connect your app to a live Reactor world model: receive
its streaming video, and send commands that steer what it generates while
it runs. Some models also accept your own audio/video as input over a
dedicated track.

## Supported SDKs

- 🐍 **[Python](sdks/python/README.md)** — install, quick start, full API reference.

## Full documentation

See the [full documentation](https://docs.reactor.inc/overview) for
platform concepts, model reference, and the API across every language.

## SDK-specific guides

The guides below are specific to this repo: how the platform concepts
above map onto this repo's actual API calls and events. They don't repeat
what's already covered there.

- [`docs/concepts.md`](docs/concepts.md) — sessions and connection state,
  tracks and capabilities, application vs. runtime scope. Start here.
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
