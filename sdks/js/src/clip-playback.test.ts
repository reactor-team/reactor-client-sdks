/** @vitest-environment jsdom */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { attachClipPlayback } from './clip-playback';
import type { HlsConstructor, HlsErrorData, HlsInstance } from './clip-playback';

const MANIFEST_URL = 'blob:https://app.example/manifest';
const MP4_URL = 'blob:https://app.example/assembled-mp4';

const scope = globalThis as Record<string, unknown>;

let createObjectURL: ReturnType<typeof vi.fn>;
let revokeObjectURL: ReturnType<typeof vi.fn>;

// Every browser that can run `hls.js` exposes a MediaSource of some kind, so
// that's the default here; `withoutMediaSource()` opts a test into the one
// that can't (iOS before 17.1).
beforeEach(() => {
  createObjectURL = vi.fn(() => MP4_URL);
  revokeObjectURL = vi.fn();
  URL.createObjectURL = createObjectURL as unknown as typeof URL.createObjectURL;
  URL.revokeObjectURL = revokeObjectURL as unknown as typeof URL.revokeObjectURL;
  scope.MediaSource = class {};
});

afterEach(() => {
  delete scope.MediaSource;
  delete scope.ManagedMediaSource;
  vi.restoreAllMocks();
});

function withoutMediaSource() {
  delete scope.MediaSource;
  delete scope.ManagedMediaSource;
}

function fakeHls(isSupported: boolean) {
  const instance: HlsInstance = {
    loadSource: vi.fn(),
    attachMedia: vi.fn(),
    on: vi.fn(),
    destroy: vi.fn(),
  };
  const ctor = function () {
    return instance;
  } as unknown as HlsConstructor;

  Object.assign(ctor, { isSupported: () => isSupported, Events: { ERROR: 'hlsError' } });
  return { ctor, instance, loadHls: () => Promise.resolve(ctor) };
}

function emitHlsError(instance: HlsInstance, data: HlsErrorData) {
  const call = vi.mocked(instance.on).mock.calls.find(([event]) => event === 'hlsError');

  (call?.[1] as (evt: unknown, data: HlsErrorData) => void)(undefined, data);
}

function setup(overrides: { loadHls?: () => Promise<HlsConstructor> } = {}) {
  const video = document.createElement('video');
  const onReady = vi.fn();
  const onError = vi.fn();
  const assembleMp4 = vi.fn(() => Promise.resolve(new Blob([new Uint8Array([1, 2, 3])], { type: 'video/mp4' })));
  const playback = attachClipPlayback(
    video,
    { manifestUrl: MANIFEST_URL, assembleMp4 },
    {
      autoPlay: false,
      onReady,
      onError,
      loadHls: () => Promise.reject(new Error('hls.js not installed')),
      ...overrides,
    },
  );

  return { video, playback, onReady, onError, assembleMp4 };
}

function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('attachClipPlayback path selection', () => {
  it('streams with hls.js when MediaSource exists and hls.js supports playback', async () => {
    const { ctor, instance, loadHls } = fakeHls(true);
    const { video, assembleMp4 } = setup({ loadHls });

    await flush();

    expect(instance.loadSource).toHaveBeenCalledWith(MANIFEST_URL);
    expect(instance.attachMedia).toHaveBeenCalledWith(video);
    expect(assembleMp4).not.toHaveBeenCalled();
    void ctor;
  });

  it('falls back to the assembled MP4 when hls.js reports unsupported', async () => {
    const { loadHls } = fakeHls(false);
    const { video, assembleMp4 } = setup({ loadHls });

    await flush();

    expect(assembleMp4).toHaveBeenCalledTimes(1);
    expect(video.src).toContain(MP4_URL);
  });

  it('falls back to the assembled MP4 when hls.js fails to load', async () => {
    const { video, assembleMp4 } = setup();

    await flush();

    expect(assembleMp4).toHaveBeenCalledTimes(1);
    expect(video.src).toContain(MP4_URL);
  });

  it('skips loading hls.js entirely when no MediaSource exists', async () => {
    withoutMediaSource();
    const loadHls = vi.fn(() => Promise.resolve(fakeHls(true).ctor));
    const { video, assembleMp4 } = setup({ loadHls });

    await flush();

    expect(loadHls).not.toHaveBeenCalled();
    expect(assembleMp4).toHaveBeenCalledTimes(1);
    expect(video.src).toContain(MP4_URL);
  });
});

describe('attachClipPlayback readiness', () => {
  it('reports ready only once the element fires loadedmetadata, and plays if autoPlay is set', async () => {
    const { loadHls } = fakeHls(true);
    const { video, onReady } = setup({ loadHls });

    await flush();
    expect(onReady).not.toHaveBeenCalled();

    video.dispatchEvent(new Event('loadedmetadata'));
    expect(onReady).toHaveBeenCalledTimes(1);
  });

  it('does not report ready a second time', async () => {
    const { loadHls } = fakeHls(true);
    const { video, onReady } = setup({ loadHls });

    await flush();
    video.dispatchEvent(new Event('loadedmetadata'));
    video.dispatchEvent(new Event('loadedmetadata'));

    expect(onReady).toHaveBeenCalledTimes(1);
  });

  it('ignores late metadata after a failure was already reported', async () => {
    const { loadHls } = fakeHls(true);
    const { video, onReady, onError } = setup({ loadHls });

    await flush();
    video.dispatchEvent(new Event('error'));
    video.dispatchEvent(new Event('loadedmetadata'));

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onReady).not.toHaveBeenCalled();
  });
});

describe('attachClipPlayback failures', () => {
  it('surfaces the element error event as a displayable message', async () => {
    const { loadHls } = fakeHls(true);
    const { video, onError } = setup({ loadHls });

    await flush();
    Object.defineProperty(video, 'error', {
      configurable: true,
      value: { code: 4, message: 'DEMUXER_ERROR_COULD_NOT_OPEN' },
    });
    video.dispatchEvent(new Event('error'));

    expect(onError).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'This browser cannot play this clip. Use Download instead. (DEMUXER_ERROR_COULD_NOT_OPEN)',
      }),
    );
  });

  it('surfaces a fatal hls.js error', async () => {
    const { instance, loadHls } = fakeHls(true);
    const { onError } = setup({ loadHls });

    await flush();
    emitHlsError(instance, { fatal: true, details: 'bufferStalledError' });

    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'Playback error: bufferStalledError' }));
  });

  it('warns but does not fail on a non-fatal hls.js error', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { instance, loadHls } = fakeHls(true);
    const { onError } = setup({ loadHls });

    await flush();
    emitHlsError(instance, { fatal: false, details: 'fragParsingError' });

    expect(onError).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalled();
  });
});

describe('attachClipPlayback teardown', () => {
  it('destroys the hls.js instance and removes listeners', async () => {
    const { instance, loadHls } = fakeHls(true);
    const { video, playback, onReady } = setup({ loadHls });

    await flush();
    playback.destroy();
    video.dispatchEvent(new Event('loadedmetadata'));

    expect(instance.destroy).toHaveBeenCalledTimes(1);
    expect(onReady).not.toHaveBeenCalled();
  });

  it('revokes the assembled MP4 blob URL', async () => {
    const { loadHls } = fakeHls(false);
    const { playback } = setup({ loadHls });

    await flush();
    playback.destroy();

    expect(revokeObjectURL).toHaveBeenCalledWith(MP4_URL);
  });
});
