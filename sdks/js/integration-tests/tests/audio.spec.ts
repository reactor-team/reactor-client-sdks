import { test, expect } from '@playwright/test';

// The received-audio content check that never existed here — new to this
// suite, not a regression. `tracks-and-upload.spec.ts` already publishes
// `mic` (via `makeAudioTrack()`, a real oscillator tone) and waits for
// `main_audio` to arrive, but only ever checks that the *event* fired, never
// that the track it names actually carries sound — the audio counterpart of
// `samplePixel()`'s role for video was simply missing. `reactor/echo` passes
// audio through unchanged (see `echo_model.py`'s `EchoOutput`), so a genuine
// round trip is exactly this suite's own tone, echoed back.
//
// NOT an SDK bug (corrected after initially being filed as one): this test
// briefly appeared to prove `publishTrack()` never sends RTP for an audio
// track — `getStats()`'s outbound-rtp audio report read `packetsSent: 0`
// while the sibling video report showed real frames, and a same-track
// `MediaRecorder` independently captured real audio, seeming to point at
// `client.publishTrack()` (`reactor.ts`) or lower (`crates/reactor-wasm`,
// `reactor-core`). Root-caused instead to `makeAudioTrack()`'s fixture: in
// headless Chromium (no real audio output device), a
// `MediaStreamAudioDestinationNode`'s graph is never actually pulled/rendered
// in real time unless something in it also reaches `ctx.destination` —
// `MediaRecorder` pulls independently of that, which is why it alone saw real
// samples. Fixed by fanning the oscillator out to `ctx.destination` through a
// zero-gain node (see `fixtures.ts`'s own comment) — confirmed via
// `getStats()` both with that fix and, independently, by swapping in a real
// `getUserMedia()`-sourced track (Chromium's fake device): both send real
// RTP through the exact same `Reactor.publishTrack()` path this test drives.

const NAME = 'audio';

test.afterEach(async ({ page }) => {
  await page.evaluate(() => window.__harness.destroyAll());
});

test('publishTrack(mic) is actually audible on the echoed main_audio track', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async (name) => {
    window.__harness.create(name);
    await window.__harness.get(name).connect(await window.__harness.fetchToken());
  }, NAME);

  // reactor/echo's own tick loop only advances on a webcam read (see
  // echo_model.py's run()) — main_audio never emits unless webcam is also
  // being published, regardless of what mic carries.
  await page.evaluate(async (name) => {
    const reactor = window.__harness.get(name);

    await reactor.publishTrack('webcam', window.__harness.makeVideoTrack('#2222ff'));
    await reactor.publishTrack('mic', await window.__harness.makeAudioTrack());
  }, NAME);

  await expect
    .poll(
      () =>
        page.evaluate((name) => {
          const names = window.__harness.events[name]!.filter((e) => e.type === 'trackReceived').map(
            (e) => (e.detail as { name: string }).name,
          );

          return names.sort();
        }, NAME),
      { timeout: 20_000 },
    )
    .toEqual(['main_audio', 'main_video']);

  // A silent (or never-arrived) track reads near 0; a real tone round-tripped
  // through Opus reads well above it. No attempt to match makeAudioTrack's
  // exact frequency/amplitude — lossy encode/decode is the same reason
  // samplePixel-based assertions use a tolerance rather than exact equality.
  await expect
    .poll(() => page.evaluate((name) => window.__harness.sampleAudioLevelFor(name, 'main_audio'), NAME), {
      timeout: 10_000,
    })
    .toBeGreaterThan(0.01);
});
