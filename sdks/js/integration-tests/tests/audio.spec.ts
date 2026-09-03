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
// KNOWN BUG, found by this test, not yet filed as its own ticket: publishing
// an audio track never actually sends any RTP. Confirmed three ways —
//   1. `getStats()` on the outbound-rtp audio report shows `packetsSent: 0`
//      / `bytesSent: 0` for the whole session, while the sibling video
//      outbound-rtp report shows real frames encoded and sent.
//   2. The track handed to `publishTrack('mic', ...)` is independently
//      verified to carry real audio — recording it directly with
//      `MediaRecorder`, bypassing this SDK and WebRTC entirely, captures a
//      non-trivial blob (thousands of bytes over 500ms) from the same
//      `makeAudioTrack()` call.
//   3. The AudioContext driving `makeAudioTrack()`'s oscillator is
//      genuinely running (`state: "running"`, `currentTime` advancing) —
//      not a suspended-context artifact.
// So the track is real, and reaches `Reactor.publishTrack()` — which calls
// straight into the wasm-bound `client.publishTrack()` (`reactor.ts`). The
// bug is somewhere from there down: `crates/reactor-wasm`, or `reactor-core`
// underneath it. That is Rust, not TypeScript, and out of scope for this
// suite to fix — flagged here rather than guessed at.
test.fail(true, 'publishTrack() sends no RTP for an audio track — see this file\'s own header comment');

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
