# Documentation

Platform concepts that hold across every language SDK — what a session
is, how commands/messages work — live at
[docs.reactor.inc](https://docs.reactor.inc). The guides here cover how
those concepts map onto this repo's actual API calls and events; they
don't repeat what's already explained there.

Start with [concepts.md](concepts.md) if you're new here — everything else
builds on it.

| Guide | Covers |
|-------|--------|
| [concepts.md](concepts.md) | Sessions and connection state, tracks and capabilities, application vs. runtime scope |
| [messaging.md](messaging.md) | Sending commands, receiving messages, capabilities negotiation, track publish/pause/resume |
| [recording.md](recording.md) | Requesting clips and full-session recordings |
| [frame-metadata.md](frame-metadata.md) | Tagging and reading per-frame metadata trailers on video tracks |

For each SDK's own quick start, API reference, and event-delivery/threading
model, see its README: [`sdks/python`](../sdks/python/README.md). For
contributing to this repo, see [`CONTRIBUTING.md`](../CONTRIBUTING.md).
