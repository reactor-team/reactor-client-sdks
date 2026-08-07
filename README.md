<div align="center">

<img src="assets/banner.png" alt="Reactor Client SDKs" width="100%" />

**Native client SDKs for real-time Reactor sessions — one Rust core, thin bindings per platform.**

[🌐 Reactor](https://reactor.inc) · [⚙️ Runtime](https://github.com/reactor-team/reactor-runtime) · [🎥 WebRTC](https://github.com/reactor-team/reactor-webrtc) · [📖 Cookbook](https://github.com/reactor-team/reactor-cookbook)

[![CI](https://github.com/reactor-team/reactor-client-sdks/actions/workflows/ci.yml/badge.svg)](https://github.com/reactor-team/reactor-client-sdks/actions/workflows/ci.yml)
[![PyPI: reactor-sdk](https://img.shields.io/pypi/v/reactor-sdk.svg?label=reactor-sdk)](https://pypi.org/project/reactor-sdk/)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

</div>

---

Reactor client SDKs connect an application to a live Reactor model over
WebRTC: session lifecycle, audio/video tracks, application commands, and
recording — behind one small API per language. All the protocol logic
(signaling, reconnection, message framing) lives once in a Rust core; each
language SDK is a thin binding on top, so behavior stays identical across
platforms.

## Repository layout

```
crates/
  reactor-protocol/   wire-protocol types shared by every SDK
  reactor-core/       session lifecycle, signaling, messaging — platform-agnostic
  reactor-ffi/        C ABI that exposes reactor-core to native language SDKs
sdks/
  python/             Python SDK (reactor-sdk on PyPI)
```

`reactor-protocol`, `reactor-core` and `reactor-ffi` are internal
implementation crates, not published to crates.io — they exist to give every
language SDK the same behavior for free. As an SDK consumer you never touch
them directly; see [`docs/concepts.md`](docs/concepts.md) for the mental
model you do need.

## Getting started

**🐍 Python** — the only SDK shipping today. Quick start, install steps
(the native library currently needs to be built from source — see why in the
package README), and full API reference:
[`sdks/python/README.md`](sdks/python/README.md).

**More platforms** — `reactor-ffi` already targets iOS and Android
(`crates/reactor-ffi/Cargo.toml`), and `reactor-core`'s own docs list
Swift, Kotlin and Go as bindings built on the same C ABI. None of those
SDKs exist in this repo yet; when one lands it gets its own
`sdks/<platform>/README.md` alongside this one.

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
