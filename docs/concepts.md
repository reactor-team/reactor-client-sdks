# Concepts

How the platform concepts at [docs.reactor.inc](https://docs.reactor.inc)
map onto this repo's actual SDKs. Read that site first for what a session
is and how reconnection works — this page assumes it, and covers the SDK
mechanics on top: the exact event/method names, and things no single
model's docs would tell you. Read this once before
[messaging.md](messaging.md) and [recording.md](recording.md), which build
on it.

## Sessions and connection state

A session's four states (see the official docs above) are reported through
a `status_changed` event: `connecting` → `waiting` → `ready`, back to
`disconnected` on `disconnect()` or a fatal transport error. `ready` is the
state to wait for before sending commands or pushing media.

`disconnect()` preserves the session so `reconnect()` can resume it later —
there's no separate "closed forever" state exposed to the SDK; a session
that's truly done just never becomes `ready` again.

## Tracks

A **track** is a named audio or video stream, always one-directional from
your point of view:

- **`recvonly`** — model output. You subscribe; you don't create these.
- **`sendonly`** — client input (camera, mic, generated frames). You must
  `publish_track(name)` before pushing frames, and `unpublish_track(name)`
  when done.

Track names and directions are **model-defined, not fixed by the SDK** —
you learn them from a `capabilities_received` event once the session is
ready, and use those exact names everywhere else (`publish_track`,
`push_video_frame`, `pause_track`, ...). `capabilities_received` also lists
which commands (see below) the model accepts.

`pause_track` / `resume_track` stop and restart delivery on a `recvonly`
track without tearing down the subscription — cheaper than
unpublish/republish for something you'll resume shortly.

## Commands and messages

Everything that isn't raw media goes over two logical channels, exposed
through `send_command` and a few events — see
[messaging.md](messaging.md) for the full reference:

- **`application`** scope (the default) — commands specific to the model
  you're talking to, and the messages it emits back.
- **`runtime`** scope — platform-level traffic: capabilities, recording
  requests (see [recording.md](recording.md)), moderation, ping.

## One shared core, growing platform support

The concepts on this page hold across every SDK, but they're not all built
the same way. Python and the other native platforms on the same C
interface (iOS, Android, ...) share one Rust implementation of
session/signaling logic, so protocol quirks and edge cases you learn on
one hold on every other one. The JavaScript SDK, `@reactor-team/js-sdk`, is
its own implementation — a browser drives WebRTC through its own APIs, not
through this repo's native code — that only shares the wire protocol (see
[the root README](../README.md#supported-sdks)) — it should
behave the same, but it isn't *guaranteed* identical the way the
C-ABI-based SDKs are to each other.

## Where handlers run

Not every event runs in the same place, because `frame` and `audio` apply
backpressure and the rest don't — but exactly what that means (which
thread, how to hand off to your own event loop) is a detail of each
binding's runtime, not a shared concept. See your SDK's own README:
[Python](../sdks/python/README.md#where-handlers-run).
