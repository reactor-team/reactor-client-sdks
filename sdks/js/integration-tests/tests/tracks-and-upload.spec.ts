import { test, expect } from '@playwright/test';

const NAME = 'tracks';

// Guarantees every session this file opens ends up disconnected server-side
// even when a test fails partway through — a page close alone drops the
// transport without sending the SDK's own disconnect(), leaving the session
// to consume model capacity until timeout.
test.afterEach(async ({ page }) => {
  await page.evaluate(() => window.__harness.destroyAll());
});

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
    await expect
      .poll(
        () =>
          page.evaluate((name) => window.__harness.samplePixelFor(name, 'main_video'), NAME).then(
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

  await test.step("unpublishTrack() twice for the same track doesn't throw or error the second time", async () => {
    // unpublish_track() (reactor-core) sends a fire-and-forget notification —
    // no correlated reply, no local bookkeeping of "is this track currently
    // published" — so there's nothing for a second call to fail against, at
    // any layer. Documented on unpublishTrack() itself: "unlike every other
    // track method, this doesn't reject — a failure is reported through the
    // `error` event instead"; here there isn't even a failure to report.
    await page.evaluate((name) => window.__harness.get(name).unpublishTrack('webcam'), NAME);
    const second = await page.evaluate(async (name) => {
      const reactor = window.__harness.get(name);

      await reactor.unpublishTrack('webcam');
      return reactor.getLastError();
    }, NAME);

    expect(second).toBeUndefined();
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
    for (const entry of mapping) {expect(entry.mid).toBeTruthy();}
  });
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
});
