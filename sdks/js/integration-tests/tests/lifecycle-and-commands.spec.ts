import { test, expect } from '@playwright/test';

// One connected session per test, walked through the surface a caller
// actually uses on it — connect, inspect, command, disconnect, reconnect —
// via `test.step()` rather than one test per method. Real sessions against
// `reactor/echo` aren't free or instant; this keeps the suite to one
// connect/disconnect pair per concern instead of one per assertion.

const NAME = 'lifecycle';

// Guarantees every session this file opens (whatever name it used) ends up
// disconnected server-side even when a test fails partway through — a page
// close alone drops the transport without sending the SDK's own
// disconnect(), leaving the session to consume model capacity until timeout.
test.afterEach(async ({ page }) => {
  await page.evaluate(() => window.__harness.destroyAll());
});

test('connect walks disconnected -> connecting -> waiting -> ready, and getters agree', async ({ page }) => {
  await page.goto('/');

  await page.evaluate((name) => window.__harness.create(name), NAME);

  await test.step('status starts disconnected', async () => {
    const status = await page.evaluate((name) => window.__harness.get(name).getStatus(), NAME);

    expect(status).toBe('disconnected');
  });

  await test.step('connect() resolves once ready', async () => {
    await page.evaluate(async (name) => {
      const jwt = await window.__harness.fetchToken();

      await window.__harness.get(name).connect(jwt);
    }, NAME);

    const status = await page.evaluate((name) => window.__harness.get(name).getStatus(), NAME);

    expect(status).toBe('ready');
  });

  await test.step('statusChanged fired the expected sequence, in order', async () => {
    const statuses = await page.evaluate(
      (name) => window.__harness.events[name]!.filter((e) => e.type === 'statusChanged').map((e) => e.detail),
      NAME,
    );

    // "connecting" and "waiting" are allowed to repeat under retry, but the
    // sequence must never go backwards and must end on "ready" — checked
    // below by rank, not just by first/last value, so a status silently
    // skipped or a step going backward still fails this.
    const rank: Record<string, number> = { disconnected: 0, connecting: 1, waiting: 2, ready: 3 };

    expect(statuses[0]).toBe('connecting');
    expect(statuses.at(-1)).toBe('ready');
    expect(statuses).toContain('waiting');
    for (let i = 1; i < statuses.length; i++) {
      expect(rank[statuses[i] as string]).toBeGreaterThanOrEqual(rank[statuses[i - 1] as string]);
    }
  });

  await test.step('getSessionId() is a non-empty string once ready', async () => {
    const sessionId = await page.evaluate((name) => window.__harness.get(name).getSessionId(), NAME);

    expect(sessionId).toBeTruthy();
  });

  await test.step('getCapabilities() reports the tracks this model declares', async () => {
    const capabilities = await page.evaluate((name) => window.__harness.get(name).getCapabilities(), NAME);

    expect(capabilities?.tracks.map((t) => t.name).sort()).toEqual(
      ['main_audio', 'main_video', 'mic', 'webcam'].sort(),
    );
  });

  await test.step('requestSchema() / getSchema() agree on the command surface', async () => {
    // getSchema() only reflects the auto-request `reactor-core` fires once
    // the session is "ready" — a race against that, not a guarantee, right
    // after connect() resolves. requestSchema() is the one that actually
    // waits, so it goes first; getSchema() should then just be its cache.
    const [requested, cached] = await page.evaluate(async (name) => {
      const reactor = window.__harness.get(name);
      const requested = await reactor.requestSchema();

      return [requested, reactor.getSchema()];
    }, NAME);
    const names = (s: typeof cached) => Object.keys(s?.paths ?? {}).sort();

    expect(names(requested)).toEqual(
      ['/events/set_effect', '/events/set_intensity', '/events/set_overlay_image', '/events/get_status'].sort(),
    );
    expect(names(cached)).toEqual(names(requested));
  });

  await test.step('getConnectionTimings() is populated once, with a positive total', async () => {
    const timings = await page.evaluate((name) => window.__harness.get(name).getConnectionTimings(), NAME);

    expect(timings?.totalMs).toBeGreaterThan(0);
  });
});

test('sendCommand() round-trips a reply, and the model broadcasts the matching event', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async (name) => {
    window.__harness.create(name);
    await window.__harness.get(name).connect(await window.__harness.fetchToken());
  }, NAME);

  for (const effect of ['grayscale', 'sepia', 'edges', 'invert', 'blur', 'pixelate', 'none'] as const) {
    await test.step(`set_effect(${effect})`, async () => {
      await page.evaluate(
        (args) => window.__harness.get(args.name).sendCommand('set_effect', { effect: args.effect }),
        { name: NAME, effect },
      );

      const last = await page.evaluate((name) => {
        const messages = window.__harness.events[name]!.filter((e) => e.type === 'message');

        return messages.at(-1)?.detail;
      }, NAME);

      expect((last as { type?: string; data?: { effect?: string } })?.type).toBe('effect_changed');
      expect((last as { data?: { effect?: string } }).data?.effect).toBe(effect);
    });
  }

  await test.step('set_intensity(0) is accepted and broadcasts the new value', async () => {
    await page.evaluate((name) => window.__harness.get(name).sendCommand('set_intensity', { intensity: 0 }), NAME);
    const last = await page.evaluate((name) => {
      const messages = window.__harness.events[name]!.filter((e) => e.type === 'message');

      return messages.at(-1)?.detail as { data?: { intensity?: number } };
    }, NAME);

    expect(last?.data?.intensity).toBe(0);
  });

  await test.step('sendCommand() never rejects (its documented contract), even for an unknown command', async () => {
    // Documented on Reactor.sendCommand(): unlike every other method here,
    // it never rejects — a failure is reported through the `error` event /
    // getLastError() instead, specifically so a fire-and-forget caller never
    // sees an unhandled rejection. So the contract to verify is "resolves
    // without throwing", not "rejects" — asserting the latter would just be
    // testing a wrong assumption about this one method.
    const reply = await page.evaluate(
      (name) => window.__harness.get(name).sendCommand('this_command_does_not_exist', {}),
      NAME,
    );

    expect(reply).toBeUndefined();
  });
});

test('sendCommand(get_status) returns correlated data (REA-5973)', async ({ page }) => {
  // echo 1.8.0+: the first echo command whose reply actually carries data,
  // rather than undefined — every other command (set_effect, set_intensity,
  // set_overlay_image) resolves with no reply body.
  await page.goto('/');
  await page.evaluate(async (name) => {
    window.__harness.create(name);
    await window.__harness.get(name).connect(await window.__harness.fetchToken());
  }, NAME);

  const first = await page.evaluate((name) => window.__harness.get(name).sendCommand('get_status', {}), NAME);

  expect(first).toEqual({ type: 'status', data: { effect: 'none', intensity: 1 } });

  await page.evaluate((name) => window.__harness.get(name).sendCommand('set_effect', { effect: 'invert' }), NAME);
  await page.evaluate((name) => window.__harness.get(name).sendCommand('set_intensity', { intensity: 0.42 }), NAME);

  const updated = await page.evaluate((name) => window.__harness.get(name).sendCommand('get_status', {}), NAME);

  expect(updated).toEqual({ type: 'status', data: { effect: 'invert', intensity: 0.42 } });
});

// Genuinely testing "reconnect() resumes a session the transport dropped
// without disconnect() ever being called" needs a real, uncontrolled
// connectivity loss — and neither obvious way to fake one from here is
// actually valid:
//   - getPeerConnection()?.close() looked promising, but per the WebRTC
//     spec close() fires no connectionstatechange event at all (confirmed
//     against two bare RTCPeerConnections, no SDK involved) — so nothing
//     downstream ever has a reason to notice.
//   - context.setOffline(true) only affects the browser's HTTP layer; a
//     same-page loopback WebRTC connection (confirmed the same way) stays
//     "connected" straight through it.
// Real coverage for this belongs at the reactor-core level, where a fake
// PeerTransport can just emit PeerEvent::ConnectionStateChanged(Disconnected)
// directly — that's what on_peer_connection_state (reactor.rs, ~line 677)
// exists to react to. What's left testable from here is reconnect()'s other
// half: refusing cleanly when there's truly nothing to resume.
test("reconnect() refuses cleanly when there's no session to resume", async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async (name) => {
    window.__harness.create(name);
    await window.__harness.get(name).connect(await window.__harness.fetchToken());
  }, NAME);

  await test.step("reconnect() after an explicit disconnect() is refused — the binding's own disconnect() always ends the session server-side, recoverable or not, so there is nothing left to resume", async () => {
    await page.evaluate((name) => window.__harness.get(name).disconnect(true), NAME);
    const error = await page.evaluate(async (name) => {
      try {
        await window.__harness.get(name).reconnect();
        return null;
      } catch (err) {
        return err instanceof Error ? err.message : String(err);
      }
    }, NAME);

    expect(error).toBeTruthy();
  });

  await test.step('disconnect() (non-recoverable) frees the client; a command afterward reports a failure through getLastError(), not a thrown rejection', async () => {
    await page.evaluate((name) => window.__harness.get(name).disconnect(), NAME);
    const [reply, lastError] = await page.evaluate(async (name) => {
      const reactor = window.__harness.get(name);
      const reply = await reactor.sendCommand('set_effect', { effect: 'none' });

      return [reply, reactor.getLastError()?.message];
    }, NAME);

    expect(reply).toBeUndefined();
    expect(lastError).toBeTruthy();
  });
});

test('connect() with a garbage token is refused with a clear error, not a hang', async ({ page }) => {
  // REACTOR_LOCAL=true (see README's "Pointing this at a local runtime")
  // is unauthenticated — a local runtime never validates the JWT at all,
  // so connect() succeeds instead of throwing. This is this suite's one
  // documented permanent gap in local mode; not testable there.
  test.skip(process.env.REACTOR_LOCAL === 'true', 'auth-error paths need a real (production) coordinator');

  await page.goto('/');
  const error = await page.evaluate(async (name) => {
    window.__harness.create(name);
    try {
      await window.__harness.get(name).connect('not-a-real-jwt');
      return null;
    } catch (err) {
      return err instanceof Error ? err.message : String(err);
    }
  }, 'bad-token');

  expect(error).toBeTruthy();
});
