<div align="center">

<img src="assets/banner.png" alt="Reactor Client SDKs" width="100%" />

**Client SDKs for real-time world models — connect, watch it run, steer it live.**

[🌐 Reactor](https://reactor.inc) · [📚 Docs](https://docs.reactor.inc) · [⚙️ Runtime](https://github.com/reactor-team/reactor-runtime) · [🎥 WebRTC](https://github.com/reactor-team/reactor-webrtc) · [📖 Cookbook](https://github.com/reactor-team/reactor-cookbook)

[![CI](https://github.com/reactor-team/reactor-client-sdks/actions/workflows/ci.yml/badge.svg)](https://github.com/reactor-team/reactor-client-sdks/actions/workflows/ci.yml)
[![npm: js-sdk](https://img.shields.io/npm/v/@reactor-team/js-sdk.svg?label=js-sdk)](https://www.npmjs.com/package/@reactor-team/js-sdk)
[![PyPI: reactor-sdk](https://img.shields.io/pypi/v/reactor-sdk.svg?label=reactor-sdk)](https://pypi.org/project/reactor-sdk/)
[![GitHub release: cpp-sdk](https://img.shields.io/github/v/release/reactor-team/reactor-client-sdks?filter=cpp-*&label=cpp-sdk)](https://github.com/reactor-team/reactor-client-sdks/releases?q=cpp-)
[![GitHub release: swift-sdk](https://img.shields.io/github/v/release/reactor-team/reactor-client-sdks?filter=v*&label=swift-sdk)](https://github.com/reactor-team/reactor-client-sdks/releases?q=Swift)
[![Maven Central: reactor-sdk](https://img.shields.io/maven-central/v/inc.reactor/reactor-sdk.svg?label=java-sdk)](https://central.sonatype.com/artifact/inc.reactor/reactor-sdk)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

</div>

---

Use these SDKs to connect your app to a live Reactor world model: receive
its streaming video, and send commands that steer what it generates while
it runs. Some models also accept your own audio/video as input over a
dedicated track.

## Supported SDKs

- **[JavaScript](sdks/js/README.md)** — `npm install @reactor-team/js-sdk`
- **[Python](sdks/python/README.md)** — `pip install reactor-sdk`
- **[C++](sdks/cpp/README.md)** — C++17, over the same native core
- **[Swift](sdks/swift/README.md)** — macOS and iOS, via Swift Package Manager
- **[Java](sdks/java/README.md)** — `inc.reactor:reactor-sdk`, desktop JVM over
  the Foreign Function & Memory API
- **[Kotlin (desktop)](sdks/java/README.md#from-kotlin)** —
  `inc.reactor:reactor-sdk-kotlin`, a `suspend`-and-`Flow` facade over that same
  binding, versioned in lockstep with it
- **[Android](sdks/android/README.md)** — `inc.reactor:reactor-sdk-android`,
  a separate Kotlin binding over JNI, because FFM does not exist on Android

## Documentation

- 📚 **[Full documentation](https://docs.reactor.inc/overview)** — platform
  concepts, model reference, and the API across every language.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for dev setup, code style, commit
conventions, and how to open a pull request.

## Licensing

This repository is **Apache-2.0** licensed — see [`LICENSE`](LICENSE).
