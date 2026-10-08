# Reactor Android SDK examples

Seven scenarios, one per capability, numbered as every other Reactor SDK numbers
them. That shared numbering is the point: **an example missing from a binding is
a code path that binding has never run**, so the set doubles as a conformance
grid.

| # | Teaches | Model |
| --- | --- | --- |
| 01 | Connect, send the model's first command, read the reply, count frames | `reactor/helios` |
| 02 | Upload, then pass the `FileRef` into a command | `reactor/helios` |
| 03 | `pause()` / `resume()` — nothing is generated while paused | `reactor/helios` |
| 04 | Publish an input track and push tagged frames into it | `xmax/x2` |
| 05 | Two clients on one session, the second adopting it by id | `reactor/helios` |
| 06 | Request a clip and download it | `reactor/helios` |
| 07 | The per-frame trailer: id, sender timestamp, `user_data` | `reactor/helios` |

Every example shares one spine — connect, wait for ready, give the model the
minimum it needs, receive frames — and adds one call on top. The diff against 01
is the lesson.

## Running them

```sh
gradle --project-dir sdks/android :examples:installDebug -PreactorApiKey=rk_…
```

Then pick a scenario on the device.

**The key is passed at install time and never compiled into a committed file.** A
shipped app would mint a short-lived token on its own backend and pass it as
`jwt` — a key inside an APK is a key anyone can extract. The examples use one
only because an example has no backend, and they say so where a reader will see
it.

## Two things that will otherwise waste your afternoon

**Nothing arrives until the model's own minimum is met, and that minimum is per
model.** Helios stays silent until it has `set_prompt` *and* `start`. X2 needs a
prompt but no start — it edits the live track as soon as it has one. Each example
spells its own out at the top, taken from the model's published schema, and that
is the first place to look when no frames appear.

**Model names are `owner/name`.** A bare name resolves under `reactor/`, so it
works by luck of ownership and answers 403 for anybody else's model.

## What "verified" means for this set

Running against a **published production model with a real key**. That is the
bar, and a local runtime does not discharge it: production is the only place the
whole path exists — the coordinator serving `/clips`, segments presigned onto
another host, the codecs the fleet negotiates, and model contracts as deployed
rather than as declared in a manifest.

A local runtime is the fallback for the two cases production cannot serve: a
scenario needing a shape no published model has, and isolating a suspected
binding bug from a platform one. Treat a green local run as evidence about the
binding, never as a passed scenario.

And a failed production run is not automatically a bug here. Billing enforcement
can close a session mid-run, and every symptom then wears a disguise — a clip
that never becomes ready, a track that reports itself unpublished, a peer
connection that only says `Disconnected`. Read the session's own reason first.
