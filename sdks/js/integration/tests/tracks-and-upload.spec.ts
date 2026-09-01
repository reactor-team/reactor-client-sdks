import { test, expect } from '@playwright/test';

// KNOWN BUG, currently failing on purpose (2026-09-01): `reactor/echo` in
// production carries per-session model state (`effect`, `intensity`,
// `_overlay`) across sessions that should be isolated — reproduced by
// uploading a solid-color overlay at full strength in one session, then
// seeing a brand-new session (different session id, different published
// color) come back showing that same overlay, with no client-side way to
// clear it. `~/dev/reactor-runtime/examples/echo/echo.py`'s own
// `session_started` hook does reset this state correctly, so the leak is in
// the coordinator/runtime's session-to-worker lifecycle (private infra, not
// this repo) rather than in the model or the SDK. Once a shared worker gets
// into this state every session landing on it fails the effect assertions
// below, regardless of what that session actually did — left as real
// (failing) assertions rather than skipped, so this stays visible until
// that's fixed upstream.

const NAME = 'tracks';

test('publishTrack() puts a sender behind the slot; pause/resume/unpublish all reflect in state', async ({
  page,
}) => {
  await page.goto('/');
  await page.evaluate(async (name) => {
    window.__harness.create(name);
    await window.__harness.get(name).connect(await window.__harness.fetchToken());
  }, NAME);

  await test.step('publishTrack(webcam) + publishTrack(mic) succeed', async () => {
    await page.evaluate(async (name) => {
      const reactor = window.__harness.get(name);
      await reactor.publishTrack('webcam', window.__harness.makeVideoTrack('#ff2222'));
      await reactor.publishTrack('mic', window.__harness.makeAudioTrack());
    }, NAME);
  });

  await test.step('the model echoes both published tracks back', async () => {
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
  });

  await test.step('grayscale actually desaturates the echoed frame — R, G, B nearly equal', async () => {
    await page.evaluate((name) => window.__harness.get(name).sendCommand('set_effect', { effect: 'grayscale' }), NAME);
    // Same race as invert below: the command's ack lands before the media
    // pipeline necessarily has, so poll a pass/fail read instead of one shot.
    await expect
      .poll(
        () =>
          page
            .evaluate((name) => window.__harness.samplePixelFor(name, 'main_video'), NAME)
            .then(({ r, g, b }) => Math.abs(r - g) < 12 && Math.abs(g - b) < 12),
        { timeout: 10_000 },
      )
      .toBe(true);
  });

  await test.step('invert flips a saturated red input toward cyan', async () => {
    await page.evaluate((name) => window.__harness.get(name).sendCommand('set_effect', { effect: 'invert' }), NAME);
    // The command's reply is a control-channel ack, independent of the media
    // pipeline's own timeline — sampling the very next frame can still catch
    // one still in flight from before the effect landed. Poll a pass/fail
    // boolean instead of a single sample so a real regression (wrong color,
    // not just late) is what actually fails this.
    await expect
      .poll(
        () =>
          page.evaluate((name) => window.__harness.samplePixelFor(name, 'main_video'), NAME).then(
            // Source is solid #ff2222; inverted should read low red, high green/blue.
            ({ r, g, b }) => r < 120 && g > 150 && b > 150,
          ),
        { timeout: 10_000 },
      )
      .toBe(true);
  });

  await page.evaluate((name) => window.__harness.get(name).sendCommand('set_effect', { effect: 'none' }), NAME);

  await test.step('pauseTrack() / pausedTracks() / resumeTrack() agree with each other', async () => {
    // pauseTrack()/resumeTrack() are for a *received* track — "the receiver
    // goes inactive and the runtime stops producing it" (see reactor.ts) —
    // not the sendonly track this test just published.
    await page.evaluate((name) => window.__harness.get(name).pauseTrack('main_video'), NAME);
    let paused = await page.evaluate((name) => window.__harness.get(name).pausedTracks(), NAME);
    expect(paused).toContain('main_video');

    await page.evaluate((name) => window.__harness.get(name).resumeTrack('main_video'), NAME);
    paused = await page.evaluate((name) => window.__harness.get(name).pausedTracks(), NAME);
    expect(paused).not.toContain('main_video');
  });

  await test.step('setTrackBitrate() is accepted on a published track', async () => {
    await page.evaluate(
      (name) => window.__harness.get(name).setTrackBitrate('webcam', { maxBps: 2_000_000 }),
      NAME,
    );
  });

  await test.step('unpublishTrack() then a second unpublish is refused, not silently accepted twice', async () => {
    await page.evaluate((name) => window.__harness.get(name).unpublishTrack('webcam'), NAME);
    const error = await page.evaluate(async (name) => {
      try {
        await window.__harness.get(name).unpublishTrack('webcam');
        return null;
      } catch (err) {
        return err instanceof Error ? err.message : String(err);
      }
    }, NAME);
    expect(error).toBeTruthy();
  });

  await test.step('getTrackByName() / getStreamByName() resolve the tracks the model declared', async () => {
    const found = await page.evaluate((name) => {
      const reactor = window.__harness.get(name);
      return {
        track: Boolean(reactor.getTrackByName('main_video')),
        stream: Boolean(reactor.getStreamByName('main_video')),
        missing: reactor.getTrackByName('not_a_real_track'),
      };
    }, NAME);
    expect(found.track).toBe(true);
    expect(found.stream).toBe(true);
    expect(found.missing).toBeUndefined();
  });

  await test.step('trackMapping() lists every negotiated track with a mid', async () => {
    const mapping = await page.evaluate((name) => window.__harness.get(name).trackMapping(), NAME);
    expect(mapping.length).toBeGreaterThan(0);
    for (const entry of mapping) expect(entry.mid).toBeTruthy();
  });

  await page.evaluate((name) => window.__harness.destroy(name), NAME);
});

test('uploadFile() + a file-taking command actually changes what the model renders', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async (name) => {
    window.__harness.create(name);
    const reactor = window.__harness.get(name);
    await reactor.connect(await window.__harness.fetchToken());
    await reactor.publishTrack('webcam', window.__harness.makeVideoTrack('#22ff22'));
  }, NAME);

  await expect
    .poll(() =>
      page.evaluate(
        (name) => window.__harness.events[name]!.some((e) => e.type === 'trackReceived'),
        NAME,
      ),
    )
    .toBe(true);

  const before = await page.evaluate((name) => window.__harness.samplePixelFor(name, 'main_video'), NAME);

  await test.step('uploadFile() resolves a FileRef the SDK recognizes', async () => {
    const isRef = await page.evaluate(async (name) => {
      const reactor = window.__harness.get(name);
      const ref = await reactor.uploadFile(window.__harness.makeTestImageFile());
      (window as unknown as { __lastRef: unknown }).__lastRef = ref;
      return window.__harness.isFileRef(ref);
    }, NAME);
    expect(isRef).toBe(true);
  });

  await test.step('set_overlay_image with that FileRef is accepted and visibly blends in', async () => {
    await page.evaluate((name) => {
      const ref = (window as unknown as { __lastRef: unknown }).__lastRef;
      return window.__harness
        .get(name)
        .sendCommand('set_overlay_image', { overlay_image: ref, overlay_strength: 1 });
    }, NAME);

    await expect
      .poll(() => page.evaluate((name) => window.__harness.samplePixelFor(name, 'main_video'), NAME))
      .not.toEqual(before);
  });

  await page.evaluate((name) => window.__harness.destroy(name), NAME);
});
