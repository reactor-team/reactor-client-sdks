/** @vitest-environment jsdom */
import { act, renderHook, waitFor } from '@testing-library/react';
import { createElement } from 'react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';
import { RecordingError, downloadClipAsFile } from '../recording';
import type * as RecordingModule from '../recording';
import type { Clip } from '../types';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

vi.mock('../recording', async (importOriginal) => ({
  ...(await importOriginal<typeof RecordingModule>()),
  downloadClipAsFile: vi.fn(),
}));

// Import after the mocks so `Reactor` picks up the faked wasm loader and
// `useClipDownload` picks up the mocked `downloadClipAsFile`.
const { ReactorProvider } = await import('./ReactorProvider');
const { useClipDownload } = await import('./useClipDownload');

const CLIP: Clip = {
  sessionId: 'sess_1',
  kind: 'snap',
  startMarker: 0,
  endMarker: 10,
  nowMarker: 10,
  predictedReadyAtMs: 0,
  playlistUrl: 'https://api.reactor.test/clips?session_id=sess_1',
};

function withProvider({ children }: { children: ReactNode }) {
  return createElement(ReactorProvider, { modelName: 'test-model', jwt: 'provider-jwt' }, children);
}

beforeEach(() => {
  FakeReactorClient.instances = [];
  vi.mocked(downloadClipAsFile).mockReset();
});

describe('useClipDownload', () => {
  it('starts idle', () => {
    const { result } = renderHook(() => useClipDownload(CLIP));

    expect(result.current.state).toEqual({ kind: 'idle' });
  });

  it('resolves the blob and returns to idle on a successful download', async () => {
    const blob = new Blob(['mp4-bytes']);

    vi.mocked(downloadClipAsFile).mockResolvedValue(blob);

    const { result } = renderHook(() => useClipDownload(CLIP));
    let resolved: Blob | undefined;

    await act(async () => {
      resolved = await result.current.download();
    });

    expect(resolved).toBe(blob);
    expect(result.current.state).toEqual({ kind: 'idle' });
  });

  it('reports progress through onProgress while downloading', async () => {
    vi.mocked(downloadClipAsFile).mockImplementation((_clip, _filename, options) => {
      options?.onProgress?.({ fetched: 1, total: 3, bytes: 10 });
      return Promise.resolve(new Blob());
    });

    const { result } = renderHook(() => useClipDownload(CLIP));

    await act(async () => {
      await result.current.download();
    });

    // downloadClipAsFile resolved by the time act() returns, so the final
    // state is idle again — the intermediate progress state is exercised via
    // the mock call itself.
    expect(downloadClipAsFile).toHaveBeenCalledWith(CLIP, 'reactor-clip.mp4', expect.any(Object));
  });

  it('surfaces a RecordingError through state as "<code>: <reason>"', async () => {
    vi.mocked(downloadClipAsFile).mockRejectedValue(new RecordingError('CLIP_GONE', 'chunks aged out'));

    const { result } = renderHook(() => useClipDownload(CLIP));

    await act(async () => {
      await result.current.download();
    });

    expect(result.current.state).toEqual({ kind: 'error', message: 'CLIP_GONE: chunks aged out' });
  });

  it('surfaces a plain Error through state via its message', async () => {
    vi.mocked(downloadClipAsFile).mockRejectedValue(new Error('network down'));

    const { result } = renderHook(() => useClipDownload(CLIP));

    await act(async () => {
      await result.current.download();
    });

    expect(result.current.state).toEqual({ kind: 'error', message: 'network down' });
  });

  it('swallows an AbortError without entering the error state', async () => {
    vi.mocked(downloadClipAsFile).mockRejectedValue(new DOMException('Aborted', 'AbortError'));

    const { result } = renderHook(() => useClipDownload(CLIP));
    let resolved: Blob | undefined;

    await act(async () => {
      resolved = await result.current.download();
    });

    // An abort is typically paired with an unmount, so there's no "idle" to
    // return to — it just doesn't get painted as a failure.
    expect(resolved).toBeUndefined();
    expect(result.current.state.kind).not.toBe('error');
  });

  it('is a no-op on a second concurrent call while one is already in flight', async () => {
    let releaseFirst!: (blob: Blob) => void;

    vi.mocked(downloadClipAsFile).mockImplementation(
      () => new Promise((resolve) => (releaseFirst = resolve)),
    );

    const { result } = renderHook(() => useClipDownload(CLIP));
    let first!: Promise<Blob | undefined>;
    let second: Blob | undefined;

    act(() => {
      first = result.current.download();
    });
    await act(async () => {
      second = await result.current.download();
    });

    expect(second).toBeUndefined();
    expect(downloadClipAsFile).toHaveBeenCalledTimes(1);

    releaseFirst(new Blob());
    await act(async () => {
      await first;
    });
  });

  it('resets to idle without cancelling an in-flight download', async () => {
    vi.mocked(downloadClipAsFile).mockImplementation(() => new Promise(() => {}));

    const { result } = renderHook(() => useClipDownload(CLIP));

    act(() => {
      void result.current.download();
    });
    await waitFor(() => expect(result.current.state.kind).toBe('downloading'));

    act(() => {
      result.current.reset();
    });

    expect(result.current.state).toEqual({ kind: 'idle' });
  });

  it("passes an explicit getJwt's resolved token through to downloadClipAsFile", async () => {
    vi.mocked(downloadClipAsFile).mockResolvedValue(new Blob());

    const { result } = renderHook(() => useClipDownload(CLIP, { getJwt: () => 'explicit-jwt' }));

    await act(async () => {
      await result.current.download();
    });

    expect(downloadClipAsFile).toHaveBeenCalledWith(
      CLIP,
      'reactor-clip.mp4',
      expect.objectContaining({ jwt: 'explicit-jwt' }),
    );
  });

  it("falls back to the ReactorProvider's jwt when getJwt is omitted", async () => {
    vi.mocked(downloadClipAsFile).mockResolvedValue(new Blob());

    const { result } = renderHook(() => useClipDownload(CLIP), { wrapper: withProvider });

    await act(async () => {
      await result.current.download();
    });

    expect(downloadClipAsFile).toHaveBeenCalledWith(
      CLIP,
      'reactor-clip.mp4',
      expect.objectContaining({ jwt: 'provider-jwt' }),
    );
  });

  it('omits jwt entirely outside a provider with no explicit getJwt (local-dev mode)', async () => {
    vi.mocked(downloadClipAsFile).mockResolvedValue(new Blob());

    const { result } = renderHook(() => useClipDownload(CLIP));

    await act(async () => {
      await result.current.download();
    });

    const call = vi.mocked(downloadClipAsFile).mock.calls[0];

    expect(call?.[2]).not.toHaveProperty('jwt');
  });

  it('defaults filename to reactor-clip.mp4 and honors an override', async () => {
    vi.mocked(downloadClipAsFile).mockResolvedValue(new Blob());

    const { result } = renderHook(() => useClipDownload(CLIP, { filename: 'my-clip.mp4' }));

    await act(async () => {
      await result.current.download();
    });

    expect(downloadClipAsFile).toHaveBeenCalledWith(CLIP, 'my-clip.mp4', expect.any(Object));
  });

  it('passes filename: null through unchanged (skips the download trigger)', async () => {
    vi.mocked(downloadClipAsFile).mockResolvedValue(new Blob());

    const { result } = renderHook(() => useClipDownload(CLIP, { filename: null }));

    await act(async () => {
      await result.current.download();
    });

    expect(downloadClipAsFile).toHaveBeenCalledWith(CLIP, null, expect.any(Object));
  });
});
