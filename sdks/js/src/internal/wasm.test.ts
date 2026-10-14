import { afterEach, describe, expect, it, vi } from 'vitest';

// The real module only exists in dist/wasm after a build — vitest.config.ts
// aliases it to this stub, and each test controls what its init does.
const wasmModule = vi.hoisted(() => ({ load: vi.fn() }));

vi.mock('./reactor-wasm.stub', () => ({
  get default() {
    return wasmModule.load;
  },
  ReactorClient: class {},
}));

afterEach(() => {
  vi.resetModules();
  wasmModule.load.mockReset();
});

const freshLoader = async () => (await import('./wasm')).loadReactorWasm;

describe('loadReactorWasm', () => {
  it('initializes the module once and shares it across calls', async () => {
    wasmModule.load.mockResolvedValue(undefined);
    const load = await freshLoader();

    const [first, second] = await Promise.all([load(), load()]);
    const third = await load();

    expect(first).toBe(second);
    expect(third).toBe(first);
    expect(wasmModule.load).toHaveBeenCalledTimes(1);
  });

  it('names the underlying failure in the message and keeps it as `cause`', async () => {
    const failure = new WebAssembly.CompileError('expected magic word 00 61 73 6d');

    wasmModule.load.mockRejectedValue(failure);
    const load = await freshLoader();

    const error = (await load().then(
      () => undefined,
      (e: unknown) => e as Error,
    ))!;

    expect(error.message).toBe('reactor-wasm failed to load: CompileError: expected magic word 00 61 73 6d');
    expect(error.cause).toBe(failure);
  });

  it('retries after a failed load instead of caching the failure', async () => {
    wasmModule.load.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValue(undefined);
    const load = await freshLoader();

    await expect(load()).rejects.toThrow('reactor-wasm failed to load: TypeError: Failed to fetch');
    await expect(load()).resolves.toBeDefined();
    expect(wasmModule.load).toHaveBeenCalledTimes(2);
  });
});
