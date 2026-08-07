# Documentation

Deep dives that don't fit in an SDK's own README. Start with
[concepts.md](concepts.md) if you're new here — everything else builds on
it. These guides are written for developers **consuming** an SDK — how
things work and the concepts you need, not the Rust internals underneath.

| Guide | Covers |
|-------|--------|
| [concepts.md](concepts.md) | Sessions and connection state, tracks and capabilities, application vs. runtime scope, callback threading |
| [messaging.md](messaging.md) | Sending commands, receiving messages, capabilities negotiation, track publish/pause/resume |
| [recording.md](recording.md) | Requesting clips and full-session recordings |
| [frame-metadata.md](frame-metadata.md) | Tagging and reading per-frame metadata trailers on video tracks |

For each SDK's own quick start and API reference, see its README:
[`sdks/python`](../sdks/python/README.md) (the only one shipping today).
For contributing to this repo, see [`CONTRIBUTING.md`](../CONTRIBUTING.md).
