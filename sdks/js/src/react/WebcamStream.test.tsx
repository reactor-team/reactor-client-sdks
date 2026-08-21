/** @vitest-environment jsdom */
import { act, render, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';
import type { Reactor } from '../reactor';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// jsdom doesn't implement getUserMedia or MediaStream/MediaStreamTrack.
class FakeMediaStreamTrack {
  constructor(readonly kind: 'video' | 'audio') {}
  stop = vi.fn();
}
class FakeMediaStream {
  private readonly tracks: FakeMediaStreamTrack[];
  constructor(tracks: FakeMediaStreamTrack[] = []) {
    this.tracks = tracks;
  }
  getTracks(): FakeMediaStreamTrack[] {
    return this.tracks;
  }
  getVideoTracks(): FakeMediaStreamTrack[] {
    return this.tracks.filter((t) => t.kind === 'video');
  }
  getAudioTracks(): FakeMediaStreamTrack[] {
    return this.tracks.filter((t) => t.kind === 'audio');
  }
}
vi.stubGlobal('MediaStream', FakeMediaStream);

// Import after the mock so `Reactor` (transitively, via `./store`) picks up
// the faked wasm loader.
const { ReactorProvider } = await import('./ReactorProvider');
const { useReactor } = await import('./hooks');
const { WebcamStream } = await import('./WebcamStream');

function currentClient(): FakeReactorClient {
  const client = FakeReactorClient.instances.at(-1);

  if (!client) {
    throw new Error('no FakeReactorClient constructed yet');
  }

  return client;
}

function Provider({ children }: { children: ReactNode }) {
  return <ReactorProvider modelName="test-model">{children}</ReactorProvider>;
}

let getUserMedia: ReturnType<typeof vi.fn<(constraints: MediaStreamConstraints) => Promise<FakeMediaStream>>>;

beforeEach(() => {
  getUserMedia = vi.fn<(constraints: MediaStreamConstraints) => Promise<FakeMediaStream>>().mockResolvedValue(
    new FakeMediaStream([new FakeMediaStreamTrack('video')]),
  );
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('WebcamStream', () => {
  it('captures video-only by default and auto-publishes once ready', async () => {
    let reactor: Reactor | undefined;
    const onPublished = vi.fn();

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      return <WebcamStream track="main_video" onPublished={onPublished} />;
    }

    render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await waitFor(() => expect(getUserMedia).toHaveBeenCalled());
    expect(getUserMedia.mock.calls[0]?.[0]?.audio).toBe(false);
    await act(() => reactor?.connect() ?? Promise.resolve());

    act(() => currentClient().emitReady());
    await waitFor(() => expect(currentClient().publishTrackCalls).toHaveLength(1));

    expect(currentClient().publishTrackCalls[0]?.name).toBe('main_video');
    expect(onPublished).toHaveBeenCalled();
  });

  it('captures audio too when audio + audioTrack are both set', async () => {
    getUserMedia.mockResolvedValue(
      new FakeMediaStream([new FakeMediaStreamTrack('video'), new FakeMediaStreamTrack('audio')]),
    );

    let reactor: Reactor | undefined;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      return <WebcamStream track="main_video" audio audioTrack="main_audio" />;
    }

    render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await waitFor(() => expect(getUserMedia).toHaveBeenCalled());
    expect(getUserMedia.mock.calls[0]?.[0]?.audio).toBe(true);
    await act(() => reactor?.connect() ?? Promise.resolve());

    act(() => currentClient().emitReady());
    await waitFor(() => expect(currentClient().publishTrackCalls).toHaveLength(2));

    expect(currentClient().publishTrackCalls.map((c) => c.name).sort()).toEqual(['main_audio', 'main_video']);
  });

  it('unpublishes when the reactor stops being ready', async () => {
    let reactor: Reactor | undefined;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      return <WebcamStream track="main_video" />;
    }

    render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await waitFor(() => expect(getUserMedia).toHaveBeenCalled());
    await act(() => reactor?.connect() ?? Promise.resolve());
    act(() => currentClient().emitReady());
    await waitFor(() => expect(currentClient().publishTrackCalls).toHaveLength(1));

    act(() => currentClient().emitDisconnected());
    await waitFor(() => expect(currentClient().unpublishTrackCalls).toContain('main_video'));
  });

  it('reports permission denial without calling onError', async () => {
    const onPermissionDenied = vi.fn();
    const onError = vi.fn();

    getUserMedia.mockRejectedValue(new DOMException('nope', 'NotAllowedError'));

    render(
      <Provider>
        <WebcamStream track="main_video" onPermissionDenied={onPermissionDenied} onError={onError} />
      </Provider>,
    );

    await waitFor(() => expect(onPermissionDenied).toHaveBeenCalled());
    expect(onError).not.toHaveBeenCalled();
  });

  it('unpublishes on unmount', async () => {
    let reactor: Reactor | undefined;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      return <WebcamStream track="main_video" />;
    }

    const { unmount } = render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await waitFor(() => expect(getUserMedia).toHaveBeenCalled());
    await act(() => reactor?.connect() ?? Promise.resolve());
    act(() => currentClient().emitReady());
    await waitFor(() => expect(currentClient().publishTrackCalls).toHaveLength(1));

    unmount();

    await waitFor(() => expect(currentClient().unpublishTrackCalls).toContain('main_video'));
  });
});
