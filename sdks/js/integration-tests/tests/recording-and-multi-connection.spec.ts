import { test, expect } from '@playwright/test';

// Safety net for a test that fails before reaching its own explicit
// destroy() calls below — destroy() is idempotent, so this is a no-op on
// the happy path where cleanup already ran in the right order.
test.afterEach(async ({ page }) => {
  await page.evaluate(() => window.__harness.destroyAll());
});

test('requestClip() + downloadClip() produce a real, non-empty file', async ({ page }) => {
  const name = 'clip';

  await page.goto('/');
  const jwt = await page.evaluate(async (n) => {
    window.__harness.create(n);
    const reactor = window.__harness.get(n);
    const jwt = await window.__harness.fetchToken();

    await reactor.connect(jwt);
    await reactor.publishTrack('webcam', window.__harness.makeVideoTrack('#ffcc00'));
    await reactor.publishTrack('mic', window.__harness.makeAudioTrack());
    return jwt;
  }, name);

  // Give the recorder something to have actually captured — a clip
  // requested the instant tracks are up asks for a window mostly in the
  // future, per the sdk-from-ffi skill's own note on clip timing.
  await page.waitForTimeout(6_000);

  const clip = await page.evaluate((n) => window.__harness.get(n).requestClip(5), name);

  expect(clip.playlistUrl).toBeTruthy();
  expect(clip.endMarker).toBeGreaterThan(0);

  const { byteLength } = await page.evaluate(
    (args) => window.__harness.downloadClip(args.clip, 'integration-test-clip.mp4', args.jwt),
    { clip, jwt },
  );

  expect(byteLength).toBeGreaterThan(0);

  await page.evaluate((n) => window.__harness.destroy(n), name);
});

test('a second client adopts the first client\'s session by id and sees the same tracks', async ({ page }) => {
  const creator = 'creator';
  const joiner = 'joiner';

  await page.goto('/');

  const sessionId = await page.evaluate(async (name) => {
    window.__harness.create(name);
    const reactor = window.__harness.get(name);
    // Both clients connect with this same token — reading a session back
    // requires the token that created it, so the joiner can't mint its own.
    const jwt = await window.__harness.fetchToken();

    (window as unknown as { __sharedJwt: string }).__sharedJwt = jwt;
    await reactor.connect(jwt);
    await reactor.publishTrack('webcam', window.__harness.makeVideoTrack('#00aaff'));
    return reactor.getSessionId();
  }, creator);

  expect(sessionId).toBeTruthy();

  await test.step('joiner adopts by session id, using the creator\'s own token', async () => {
    await page.evaluate(
      async (args) => {
        window.__harness.create(args.joiner);
        const jwt = (window as unknown as { __sharedJwt: string }).__sharedJwt;

        await window.__harness.get(args.joiner).connect(jwt, { sessionId: args.sessionId });
      },
      { joiner, sessionId },
    );
    const status = await page.evaluate((name) => window.__harness.get(name).getStatus(), joiner);

    expect(status).toBe('ready');
  });

  await test.step('the joiner receives the same output tracks as the creator', async () => {
    await expect
      .poll(() =>
        page.evaluate(
          (name) => window.__harness.events[name]!.filter((e) => e.type === 'trackReceived').length,
          joiner,
        ),
      )
      .toBeGreaterThan(0);
  });

  await test.step('a command from the joiner is visible to the creator\'s side too', async () => {
    await page.evaluate((name) => window.__harness.get(name).sendCommand('set_effect', { effect: 'sepia' }), joiner);
    await expect
      .poll(() =>
        page.evaluate((name) => {
          const last = window.__harness.events[name]!.filter((e) => e.type === 'message').at(-1);

          return (last?.detail as { data?: { effect?: string } })?.data?.effect;
        }, creator),
      )
      .toBe('sepia');
  });

  // Teardown order matters: the joiner only watches. Disconnecting it first
  // and the creator last is what ends the session cleanly server-side — the
  // opposite order is exactly the orphaned-session bug this scenario exists
  // to catch (see the sdk-from-ffi skill's own note on example 05).
  await page.evaluate((name) => window.__harness.destroy(name), joiner);
  await page.evaluate((name) => window.__harness.destroy(name), creator);
});
