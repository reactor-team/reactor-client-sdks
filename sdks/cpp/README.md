# Reactor C++ SDK

A C++17 client for [Reactor](https://reactor.inc): real-time generative video
models over WebRTC, driven by commands and delivering decoded frames.

Built on `libreactor_ffi` — the same Rust core the
[Python SDK](../python) binds, so the two agree on the protocol, the error codes
and what a failure means.

```cpp
#include <reactor/reactor.hpp>

reactor::Reactor client{"reactor/helios", reactor::ApiKey{std::getenv("REACTOR_API_KEY")}};

client.connect().get();
client.send_command("set_prompt", {{"prompt", "a red fox in tall grass"}}).get();
client.send_command("start").get();

auto frames = client.track("main_video").on_frame([](const reactor::VideoFrame& frame) {
  render(frame.bgra, frame.width, frame.height);   // borrowed; copy what you keep
});

std::this_thread::sleep_for(std::chrono::seconds(10));
client.disconnect().get();
```

## Install

Each release ships one archive per platform, carrying a prebuilt
`libreactor_ffi`. There is no source archive: the package is useless without that
native library, and building it needs a Rust toolchain and a libwebrtc download.

| Platform | Archive | Requires |
|---|---|---|
| Linux x86_64 | `…-linux-x64.tar.gz` | glibc 2.34+ (Ubuntu 22.04, Debian 12, RHEL 9, Amazon Linux 2023) |
| Linux aarch64 | `…-linux-arm64.tar.gz` | glibc 2.34+ |
| macOS arm64 | `…-macos-arm64.tar.gz` | macOS 11+ |
| macOS x86_64 | `…-macos-x64.tar.gz` | macOS 13+ — libwebrtc's floor on this architecture |
| Windows x86_64 | `…-windows-x64.zip` | Windows 10+ |

Anything outside that table — musl distributions, glibc older than 2.34, 32-bit,
Windows on ARM — has no archive, and has to build `libreactor_ffi` from this
repository. See [Development](#development).

Every row is checked rather than promised. The Linux archives are built against
AlmaLinux 9's glibc, and the release refuses one that asks for anything newer
than 2.34 or that was built for a later macOS than its row here says.

Extract an archive and point CMake at it:

```bash
tar xzf reactor-sdk-cpp-1.0.0-linux-x64.tar.gz
cmake -S . -B build -DCMAKE_PREFIX_PATH=$PWD/reactor-sdk-cpp-1.0.0-linux-x64
```

```cmake
find_package(reactor-sdk REQUIRED)
target_link_libraries(app PRIVATE reactor::sdk)
```

One target carries everything: the include directories, the C++17 requirement,
`nlohmann_json`, and `libreactor_ffi`. Nothing has to be set to run from your
build tree — CMake points the binary at the archive it linked against.

### Shipping your application

The SDK is two files and they are not the same kind. `libreactor_sdk.a` is a
static library and disappears into your binary. `libreactor_ffi` is a *shared*
library — it carries libwebrtc and the Rust core, exports 29 symbols and hides
everything else — so it stays a separate file, and it has to travel with what
you ship.

The rpath CMake gives your binary in the build tree is an absolute path on the
machine that built it. For an installed application, say it relatively and put
the library where it points:

```cmake
set_target_properties(app PROPERTIES
  INSTALL_RPATH "$<IF:$<PLATFORM_ID:Darwin>,@loader_path,$ORIGIN>")

install(TARGETS app RUNTIME DESTINATION bin)
install(FILES "$<TARGET_FILE:reactor::ffi>" DESTINATION bin)
```

`$<TARGET_FILE:reactor::ffi>` is the `.so`, the `.dylib` or the `.dll` — the
file that runs, on every platform. Windows needs no rpath; the DLL beside the
executable is how the loader finds it.

## The shape of the API

**Async calls return `std::future`.** Failures arrive as exceptions from `.get()`,
so a caller can handle a specific one or a whole class of them:

```cpp
try {
  client.connect().get();
} catch (const reactor::UnauthorizedError&) {
  token = refresh();                      // a specific, actionable failure
} catch (const reactor::ReactorError& error) {
  if (error.recoverable()) {              // a class of failures, by property
    client.reconnect().get();
  }
}
```

**Events return a `Subscription`.** It is RAII: hold it for as long as you want
the handler, or call `detach()` to say the handler should outlive it.

```cpp
auto status = client.on_status([](reactor::Status now) { … });   // live while `status` is
```

> This is the one place the surface differs from the Python SDK, which offers
> `off(event, handler)`. Two `std::function`s cannot be compared, so a token is the
> only honest removal.

**Tracks are asked for by name.** `client.tracks()` and its filters are for
discovering what a session declared:

```cpp
auto video  = client.track("main_video");                            // an app that knows its model
auto output = client.tracks().with_direction(reactor::TrackDirection::RecvOnly)
                             .with_kind(reactor::TrackKind::Video).one();
```

A model name is `owner/name`. A bare name resolves under `reactor/`, so it works
by luck of ownership and answers 403 for anyone else's model.

## Tracks

### Receiving

```cpp
auto frames = client.track("main_video").on_frame([](const reactor::VideoFrame& frame) {
  // BGRA, width * height * 4 bytes, plus the trailer: frame_id, timestamp_us,
  // user_data. Borrowed — gone when this returns.
});
auto audio = client.track("main_audio").on_audio([](const reactor::AudioFrame& frame) {
  // Interleaved int16 PCM.
});
```

**Frame handlers run inline, on the library's delivery thread, and blocking in one
is the backpressure**: while it runs, the FFI keeps only the newest video frame
and drops the ones in between. That is deliberate — handing frames to a queue of
your own trades a bounded drop for unbounded latency and memory. The audio queue
is short and keeps its backlog instead, because there the queue is the jitter
buffer and a hole in it is audible.

Control events (`on_status`, `on_error`, `on_message`, `on_runtime_message`,
`on_track`) are different: they run on a thread the SDK owns, one at a time, so a
handler never runs on a library thread and never races another handler. A host
with a loop of its own can take them instead:

```cpp
reactor::Options options;
options.executor = [&](std::function<void()> work) { my_loop.post(std::move(work)); };
```

Futures are never settled through the executor, so `connect().get()` on the same
loop cannot deadlock against it.

### Sending

```cpp
auto input = client.track("source");
input.publish().get();                       // puts a sender behind the slot

reactor::Track::FrameOptions options;
options.capture_time_us = reactor::time_micros();   // one read per captured moment
input.push_frame(reactor::Bytes{bgra.data(), bgra.size()}, width, height, options);

input.unpublish();                           // synchronous: no round trip
```

Read `time_micros()` **once per unit of produced media** and stamp every track
with that one value: tracks are synchronised by sharing a capture time, not by
reaching the encoder at the same moment.

### Audio devices

Real devices are an opt-in extra in a target of their own, because the core is
pinned to the synthetic audio module and cannot be talked out of it — nothing on
the mandatory path can open a microphone.

```cmake
target_link_libraries(app PRIVATE reactor::sdk reactor::sdk_audio)
```

```cpp
reactor::audio::Speaker speaker{client.track("main_audio")};
speaker.start();
```

`Speaker` reports `dropped_ms()` (the device is slower than the stream) and
`under_runs()` (the stream is slower than the device) — two different problems
that a single "glitches" counter would hide.

This is the one part of the SDK with a dependency the archive does not carry,
and only on Linux: the backend is loaded at run time from whichever of
`libasound.so.2` (ALSA), `libpulse.so.0` or `libjack.so.0` is present. A slim
container image usually has none, and `start()` then throws rather than
playing silence — `apt install libasound2` / `dnf install alsa-lib` is the fix.
Nothing else in the archive needs it: `libreactor_ffi.so` loads `libc`, `libm`,
`libgcc_s` and the dynamic loader, and that is the whole list. macOS and Windows
use the system frameworks and need nothing installed.

## What the SDK refuses

The native layer is permissive: pushing into a track that does not exist, or that
points the other way, or that was never published, reaches it, finds nothing to
do, and returns. The caller then sees a loop pushing at 30fps and a model
receiving nothing. Every one of these throws instead, with the fix in the message:

| | |
|---|---|
| a track name the session never declared | `NotFoundError`, listing the names it does declare |
| `on_frame` on a sendonly track | `InvalidStateError` — it would never fire |
| a video handler on an audio track | `InvalidStateError`, naming `on_audio` |
| `push_frame` on a recvonly track | `InvalidStateError`, naming the direction |
| `push_frame` before `publish()` | `InvalidStateError` |
| a pixel buffer that is not `width * height * 4` | `BadRequestError`, naming both sizes |
| anything after the session leaves `Ready` | `InvalidStateError`, naming the status |
| `one()` matching zero or several tracks | `NotFoundError` / `InvalidStateError` |

Publishing state is not something the session records, so the SDK keeps it — and
**clears it whenever the status leaves `Ready`**. A reconnect resumes recvonly
tracks and nothing else, so a slot published before one is not published after it.

## Errors

One base `reactor::ReactorError` and sixteen subclasses over the codes the core
defines. The same type is what a failed call throws *and* what `on_error`
delivers — they used to disagree in the Python SDK, and anything listening to the
event reconnected in a loop against a token that would never work.

`recoverable()` is the property to branch on when the specific code does not
matter. Codes are open-ended: a command the model refuses reports the model's own
code, which arrives on `ReactorError` itself with `code()` set to it — match on
`code()` for anything not in the list, and never treat an unrecognised one as a
parse failure.

## Recordings

```cpp
auto clip = client.request_clip(10.0).get();     // accepted, not ready
clip.download("last-ten-seconds.mp4").get();     // this is what waits
```

The wait is bounded by **the session still being alive**, not by a number of
seconds. A clip becomes ready because the model keeps generating, so once the
session is gone a "not yet" is a "not yet" forever — and a model generating at a
tenth of real time takes ten times as long to get there. Pass
`DownloadOptions::ready_timeout_seconds` if you want a wall-clock bound anyway —
it is grace *past* the runtime's own prediction of readiness, not a budget from
the call, so a clip expected in ten seconds with five of grace has fifteen.

A clip is also clamped to the media that exists: asking for ten seconds after
eight seconds of wall clock gets you whatever the model has generated.

## Connection statistics

```cpp
const auto stats = client.get_stats().get();
if (stats.rtt_ms) {
  std::cout << *stats.rtt_ms << " ms\n";
}
```

- The two measured bitrates are derived **against the previous call**, so the first
  one after connecting leaves them empty, as does a call made less than 200 ms
  after the last. Everything else is on every call. Poll a couple of seconds apart
  for a continuous reading.
- An empty `std::optional` means the engine has not measured that field yet, which
  is not the same as zero — hence the optionals rather than a sentinel.
- `candidate_type` is `"relay"` when the session is going through TURN, which is
  the first thing worth knowing when latency is bad.
- `inbound`, `outbound` and `candidate_pairs` carry the engine's own per-stream
  report: SSRCs, stream kinds, frame counters, NACK counts, retransmissions,
  decode time, and each candidate pair's own totals.
- Throws `InvalidStateError` unless the session is `Ready`. Asynchronous rather
  than a plain getter because the engine collects a report on its own thread and
  waits for it.

These are the same numbers the
[JS SDK](https://docs.reactor.inc/sdk-reference/types#connectionstats) reports for
the same connection: the same candidate pair (the one ICE nominated), the same
byte counters, the same video stream. Two deliberate differences:

- **An audio-only session still reports `jitter_s` and `packet_loss_ratio`.** Both
  come from the received video stream, as in the browser; with no video stream
  this falls back to the receive streams there are, where the browser reports
  nothing.
- **`inbound`, `outbound`, `candidate_pairs` and `relay_protocol` are extra.** The
  browser's own report has most of that; its SDK does not surface it.

## Examples

Seven, one capability each, in [`examples/`](examples) — and each one has been run
against a published model in production. Start with
[`01_connect_and_receive.cpp`](examples/01_connect_and_receive.cpp); the diff
against it is the lesson in every other one. `examples/README.md` has the matrix,
including the two results that look like faults and are not.

## Development

```bash
mise run build:ffi   # cargo build -p reactor-ffi --release
mise run build:cpp   # cmake + ninja: the SDK, its tests and the examples
mise run test:cpp    # ctest
mise run lint:cpp    # clang-format --dry-run + clang-tidy
```

Building the native library is a **separate** step, not a dependency of
`build:cpp`. On Linux it compiles reactor-webrtc-sys's C++ glue with a pinned
conda clang++, and that toolchain brings a sysroot whose glibc cannot resolve the
symbols the resulting `.so` needs — so the SDK itself must be built with the
platform compiler. Two compilers, two commands.

To build against a library somewhere else — a release archive's `lib/`, or another
checkout:

```bash
cmake -S sdks/cpp -B sdks/cpp/build -G Ninja -DREACTOR_FFI_LIB_DIR=/path/to/lib
```

**Rebuild the native library after pulling changes under `crates/`.** A signature
that moved in the FFI but not in your build still links, and then corrupts the
stack at the call — which looks like a hang, not a version error.
`reactor_abi_version()` turns that into a message naming both versions, but only
because the SDK checks it; the library on disk still has to be the one you think
it is.

### Layout

| Path | |
|---|---|
| `include/reactor/` | the public headers. A consumer includes `<reactor/…>` and never `<reactor_ffi.h>` |
| `src/detail/` | the FFI boundary: the symbol table, RAII strings, the dispatcher |
| `src/audio/` | the optional device helpers |
| `tests/` | Catch2 unit tests, run against a fake library |
| `examples/` | the seven |

### Dependencies

`nlohmann_json` (public: `reactor::Json` is an alias for it), and `miniaudio` for
the optional audio target. Both are fetched by CMake, pinned, and yield to a copy
already on the prefix path. `reactor::sdk` links no audio library at all.

## Documentation

The [full documentation](https://docs.reactor.inc/sdk-reference/using-the-sdk)
covers platform concepts and the other language SDKs. A model's own reference page
is the thing to check when a command is rejected or no frame arrives;
`client.request_schema()` returns the same document from the running model, which
is the more current of the two.

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the toolchain,
DCO and commit conventions.

## License

Apache-2.0 — see [`LICENSE`](../../LICENSE).
