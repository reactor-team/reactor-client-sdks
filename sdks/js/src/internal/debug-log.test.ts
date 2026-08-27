import { afterEach, describe, expect, it, vi } from 'vitest';

// Vitest itself runs on Vite, so `import.meta.env.DEV` is `true` by default
// in every test here unless overridden — it has to be forced per-test, the
// same way `NODE_ENV` is stubbed, to exercise each signal independently.
const originalImportMetaDev = import.meta.env?.DEV;

function setImportMetaDev(value: boolean | undefined) {
  (import.meta.env as { DEV?: boolean | undefined }).DEV = value;
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
  setImportMetaDev(originalImportMetaDev);
  vi.resetModules();
});

describe('debugLog()', () => {
  it('logs via console.debug when NODE_ENV is "development" (webpack/Next.js-style)', async () => {
    vi.stubEnv('NODE_ENV', 'development');
    setImportMetaDev(false);
    const { debugLog } = await import('./debug-log');
    const spy = vi.spyOn(console, 'debug').mockImplementation(() => {});

    debugLog('hello', { a: 1 });

    expect(spy).toHaveBeenCalledWith('hello', { a: 1 });
  });

  it('logs via console.debug when import.meta.env.DEV is true (Vite-style)', async () => {
    vi.stubEnv('NODE_ENV', 'production');
    setImportMetaDev(true);
    const { debugLog } = await import('./debug-log');
    const spy = vi.spyOn(console, 'debug').mockImplementation(() => {});

    debugLog('hello', { a: 1 });

    expect(spy).toHaveBeenCalledWith('hello', { a: 1 });
  });

  it('stays silent when neither signal indicates a development build', async () => {
    vi.stubEnv('NODE_ENV', 'production');
    setImportMetaDev(false);
    const { debugLog } = await import('./debug-log');
    const spy = vi.spyOn(console, 'debug').mockImplementation(() => {});

    debugLog('hello');

    expect(spy).not.toHaveBeenCalled();
  });

  it('stays silent when NODE_ENV is unset and import.meta.env.DEV is false', async () => {
    vi.stubEnv('NODE_ENV', '');
    setImportMetaDev(false);
    const { debugLog } = await import('./debug-log');
    const spy = vi.spyOn(console, 'debug').mockImplementation(() => {});

    debugLog('hello');

    expect(spy).not.toHaveBeenCalled();
  });
});
