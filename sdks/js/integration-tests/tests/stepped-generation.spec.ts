import { test, expect, type Page } from '@playwright/test';

// Stepped generation against a real runtime, with the step frames encoded
// on WebCodecs. `reactor/echo` from 1.10.0 runs the default step loop, so a
// stepped session carries the pushed webcam frame and steps it back inverted
// on main_video. An older echo, or a runtime that refuses its stepped start
// (START_REFUSED) or streams instead (a mode mismatch), is accepted as the
// native SDKs' suites accept it: a stepped connect that silently streams is
// not.

const NAME = 'stepped';

test.afterEach(async ({ page }) => {
  await page.evaluate(() => window.__harness.destroyAll());
});

/** Connect a stepped session that prefers `codecs`, and publish its webcam.
 *  `false` when the runtime does not step echo, which the test accepts. */
async function connectStepped(page: Page, codecs: Array<'h264'> | null): Promise<boolean> {
  return page.evaluate(
    async ({ name, codecs }) => {
      window.__harness.create(name, { generation: 'stepped' });
      const reactor = window.__harness.get(name);

      reactor.setCodecPreference(codecs);
      try {
        await reactor.connect(await window.__harness.fetchToken());
      } catch (cause) {
        const error = cause as Error & { code?: string };

        if (error.code === 'GENERATION_MODE_MISMATCH' || error.message.includes('(START_REFUSED)')) {
          return false;
        }
        throw cause;
      }
      await reactor.publishTrack('webcam');
      return true;
    },
    { name: NAME, codecs },
  );
}

/** Push one solid 64x64 RGB frame on the webcam, run a step that inverts
 *  it, and return the step's number, what the output frame travelled in, its
 *  shape and its centre pixel. */
async function invertOnce(page: Page) {
  return page.evaluate(
    async ({ name }) => {
      const reactor = window.__harness.get(name);
      const data = new Uint8Array(64 * 64 * 3);

      for (let i = 0; i < data.length; i += 3) {
        data.set([10, 20, 30], i);
      }
      await reactor.pushFrame('webcam', { data, width: 64, height: 64, format: 'rgb' });
      const result = await reactor.step({ state: { effect: 'invert' } });

      if (result.error) {
        throw new Error(`step failed: ${result.error}`);
      }
      const frames = result.tracks.find((track) => track.track === 'main_video')?.frames ?? [];
      const [frame] = frames;

      if (frames.length !== 1 || !frame) {
        throw new Error(`expected one main_video frame, got ${frames.length}`);
      }
      const centre = (32 * 64 + 32) * 3;

      return {
        step: result.step,
        encoding: frame.encoding,
        shape: frame.shape,
        centre: Array.from(frame.data.slice(centre, centre + 3)),
      };
    },
    { name: NAME },
  );
}

function expectInverted(centre: number[], tolerance: number): void {
  const off = centre.map((value, i) => Math.abs(value - ([245, 235, 225][i] as number)));

  expect(Math.max(...off), `centre pixel ${JSON.stringify(centre)}`).toBeLessThanOrEqual(tolerance);
}

for (const [label, codecs, expected] of [
  ['h264', ['h264'], 'h264'],
  ['encoding off', [], 'raw'],
] as const) {
  test(`a stepped session carries its frames in the codec it chose: ${label}`, async ({ page }) => {
    await page.goto('/');
    if (!(await connectStepped(page, [...codecs]))) {
      return;
    }
    // The frames travel encoded both ways when both ends share a codec, so
    // the inverted pixel comes back close rather than exact.
    const tolerance = expected === 'raw' ? 0 : 8;

    await test.step('each track negotiated the expected encoding', async () => {
      const encodings = await page.evaluate((name) => window.__harness.get(name).getStepFrameEncodings(), NAME);

      expect(encodings.main_video).toBe(expected);
      // A track the connection negotiated no step codec for may be left out:
      // it is raw.
      expect(encodings.webcam ?? 'raw').toBe(expected);
    });

    const first = await test.step('a pushed frame comes back inverted', async () => {
      const result = await invertOnce(page);

      expect(result.encoding).toBe(expected);
      expect(result.shape).toEqual([64, 64, 3]);
      expectInverted(result.centre, tolerance);
      return result;
    });

    await test.step('the next step continues the same stream', async () => {
      const again = await invertOnce(page);

      expect(again.step).toBe(first.step + 1);
      expectInverted(again.centre, tolerance);
    });
  });
}

test('a reconnect starts both streams again at keyframes', async ({ page }) => {
  await page.goto('/');
  if (!(await connectStepped(page, ['h264']))) {
    return;
  }
  const before = await invertOnce(page);

  expectInverted(before.centre, 8);
  await page.evaluate((name) => window.__harness.get(name).reconnect(), NAME);
  await page.evaluate((name) => window.__harness.get(name).publishTrack('webcam'), NAME);

  // The encoder and the decoder of the connection that ended are gone: the
  // new connection's first frames each way are keyframes, so both decode.
  const after = await invertOnce(page);

  expect(after.encoding).toBe('h264');
  expectInverted(after.centre, 8);
});
