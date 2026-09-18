# Examples

Eight scenarios, numbered as in every other Reactor SDK. Each one teaches exactly
one thing, and each is a parity requirement rather than documentation: an example
missing from a binding is a code path that binding has never run.

## Running one

```bash
export REACTOR_API_KEY=rk_...          # https://www.reactor.inc/account/api-keys
mise run build:ffi                     # the native library, once
mise run example:java 01
```

| Variable | What it does |
| --- | --- |
| `REACTOR_API_KEY` | required; exchanged for a token by the example itself |
| `REACTOR_SHOW=1` | **opens a window and draws the video** |
| `REACTOR_SECONDS` | shortens a run; each example has its own sensible default |
| `REACTOR_MODEL` | point an example at another model |
| `REACTOR_API_URL` | point it at another coordinator |

**With the window:**

```bash
REACTOR_API_KEY=rk_... REACTOR_SHOW=1 mise run example:java 01
```

A frame count proves something arrived, not that it was the right something.
`REACTOR_SHOW=1` is how you check the second part, and it is the only thing the
eight examples share a file for.

## The eight

| # | Teaches | Model |
| --- | --- | --- |
| 01 | Connect, send the model's first command, read the reply, count frames | `reactor/helios` |
| 02 | Upload a file, pass the reference into a command | `reactor/helios` |
| 03 | Pause and resume a track | `reactor/helios` |
| 04 | Publish a track and push tagged frames into it | `xmax/x2` |
| 05 | Two clients on one session, the second adopting it by id | `reactor/helios` |
| 06 | Request a clip and download it | `reactor/helios` |
| 07 | Read the per-frame trailer: frame id, sender timestamp, tag | `reactor/helios` |
| 08 | Read what `sendCommand` actually hands back | `reactor/helios` |

04 uses a different model because it needs one that declares a **sendonly**
track to push into; Helios only produces.

## Things these cost to learn

- **Nothing arrives until the model's own minimum is met, and that minimum is per
  model.** Helios emits nothing until `start`, and `start` refuses without a
  prompt. A run that connects, waits and reports zero frames is usually a missing
  command rather than a broken transport.
- **A model name is `owner/name`.** A bare name resolves under `reactor/`, so it
  works by luck of ownership and answers 403 for anyone else's model.
- **Teardown belongs in try-with-resources.** A creator that goes away without
  disconnecting orphans the session, and the next run cannot start until that
  clears.
- **Publishing is what puts a sender behind a slot**, and a publish does not
  survive the session leaving `ready`.
- **Clip readiness is in media time, not wall clock.** Waiting before asking only
  moves the target; the download waits on the session still being alive.
- **A failed production run is not automatically a binding bug.** A busy fleet
  answers `429 no available capacity`, and the SDK reports that as a recoverable
  `RateLimitedException` naming the status and the operation. Read what the
  platform said before changing code.
