/** @vitest-environment jsdom */
import { act, render, waitFor, within } from '@testing-library/react';
import { createElement } from 'react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';
import { RecordingError, createPlayableManifestUrl, fetchPlaylist } from '../recording';
import type * as RecordingModule from '../recording';
import type { Clip } from '../types';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

vi.mock('../recording', async (importOriginal) => ({
  ...(await importOriginal<typeof RecordingModule>()),
  fetchPlaylist: vi.fn(),
  createPlayableManifestUrl: vi.fn(),
}));

class FakeHls {
  static isSupportedResult = true;
  static instances: FakeHls[] = [];
  static readonly Events = { MANIFEST_PARSED: 'hlsManifestParsed', ERROR: 'hlsError' } as const;
  static isSupported(): boolean {
    return FakeHls.isSupportedResult;
  }

  loadSourceCalls: string[] = [];
  attachMediaCalls: HTMLMediaElement[] = [];
  destroyCalls = 0;
  private readonly listeners = new Map<string, (evt: unknown, data: unknown) => void>();

  constructor() {
    FakeHls.instances.push(this);
  }
  loadSource(url: string): void {
    this.loadSourceCalls.push(url);
  }
  attachMedia(el: HTMLMediaElement): void {
    this.attachMediaCalls.push(el);
  }
  on(event: string, cb: (evt: unknown, data: unknown) => void): void {
    this.listeners.set(event, cb);
  }
  emit(event: string, data: unknown): void {
    this.listeners.get(event)?.(undefined, data);
  }
  destroy(): void {
    this.destroyCalls += 1;
  }
}

vi.mock('hls.js', () => ({ default: FakeHls }));

// jsdom doesn't implement HTMLMediaElement playback control at all — the
// cleanup path in ClipPlayer's effect calls all three on every unmount/re-run.
HTMLMediaElement.prototype.play = () => Promise.resolve();
HTMLMediaElement.prototype.pause = () => undefined;
HTMLMediaElement.prototype.load = () => undefined;

// Import after the mocks so `Reactor` picks up the faked wasm loader and
// `ClipPlayer` picks up the mocked recording helpers / hls.js.
const { ReactorProvider } = await import('./ReactorProvider');
const { ClipPlayer } = await import('./ClipPlayer');

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
  FakeHls.instances = [];
  FakeHls.isSupportedResult = true;
  vi.mocked(fetchPlaylist).mockReset().mockResolvedValue('#EXTM3U\n');
  vi.mocked(createPlayableManifestUrl).mockReset().mockReturnValue('blob:test-manifest');
  HTMLVideoElement.prototype.canPlayType = vi.fn().mockReturnValue('');
});

describe('ClipPlayer', () => {
  it('attaches via hls.js when native HLS is unsupported, and reaches ready on MANIFEST_PARSED', async () => {
    const { container } = render(<ClipPlayer clip={CLIP} getJwt={() => 'x'} />);

    await waitFor(() => expect(fetchPlaylist).toHaveBeenCalled());
    await waitFor(() => expect(FakeHls.instances).toHaveLength(1));

    const hls = FakeHls.instances[0]!;

    expect(hls.loadSourceCalls).toEqual(['blob:test-manifest']);
    expect(hls.attachMediaCalls).toEqual([container.querySelector('video')]);

    act(() => hls.emit(FakeHls.Events.MANIFEST_PARSED, undefined));

    await waitFor(() => expect(within(container).queryByText(/waiting|loading/i)).toBeNull());
  });

  it('attaches natively when the browser supports HLS playback', async () => {
    HTMLVideoElement.prototype.canPlayType = vi.fn().mockReturnValue('maybe');

    const { container } = render(<ClipPlayer clip={CLIP} getJwt={() => 'x'} />);

    await waitFor(() => {
      const video = container.querySelector('video') as HTMLVideoElement;

      expect(video.src).toBe('blob:test-manifest');
    });
    expect(FakeHls.instances).toHaveLength(0);
  });

  it('surfaces a fatal hls.js error and calls onError', async () => {
    const onError = vi.fn();

    const { container } = render(<ClipPlayer clip={CLIP} getJwt={() => 'x'} onError={onError} />);

    await waitFor(() => expect(FakeHls.instances).toHaveLength(1));

    act(() => FakeHls.instances[0]!.emit(FakeHls.Events.ERROR, { fatal: true, details: 'bufferStalledError' }));

    await waitFor(() => expect(onError).toHaveBeenCalled());
    expect(within(container).getByText(/Playback error: bufferStalledError/)).toBeTruthy();
  });

  it('surfaces a RecordingError from a failed playlist fetch', async () => {
    vi.mocked(fetchPlaylist).mockRejectedValue(new RecordingError('CLIP_GONE', 'chunks aged out'));
    const onError = vi.fn();

    const { container } = render(<ClipPlayer clip={CLIP} getJwt={() => 'x'} onError={onError} />);

    await waitFor(() => expect(within(container).getByText('CLIP_GONE: chunks aged out')).toBeTruthy());
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'CLIP_GONE' }));
  });

  it('surfaces a "browser cannot play HLS" error when hls.js reports unsupported', async () => {
    FakeHls.isSupportedResult = false;

    const { container } = render(<ClipPlayer clip={CLIP} getJwt={() => 'x'} />);

    await waitFor(() => expect(within(container).getByText(/cannot play HLS clips/)).toBeTruthy());
  });

  it("passes an explicit getJwt's resolved token to fetchPlaylist", async () => {
    render(<ClipPlayer clip={CLIP} getJwt={() => 'explicit-jwt'} />);

    await waitFor(() =>
      expect(fetchPlaylist).toHaveBeenCalledWith(CLIP.playlistUrl, expect.objectContaining({ jwt: 'explicit-jwt' })),
    );
  });

  it("falls back to the ReactorProvider's jwt when getJwt is omitted", async () => {
    render(<ClipPlayer clip={CLIP} />, { wrapper: withProvider });

    await waitFor(() =>
      expect(fetchPlaylist).toHaveBeenCalledWith(CLIP.playlistUrl, expect.objectContaining({ jwt: 'provider-jwt' })),
    );
  });

  it('tears down the hls.js instance and revokes the manifest blob URL on unmount', async () => {
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const { unmount } = render(<ClipPlayer clip={CLIP} getJwt={() => 'x'} />);

    await waitFor(() => expect(FakeHls.instances).toHaveLength(1));
    const hls = FakeHls.instances[0]!;

    unmount();

    expect(hls.destroyCalls).toBe(1);
    expect(revokeSpy).toHaveBeenCalledWith('blob:test-manifest');
  });
});
