/** @vitest-environment jsdom */
import { act, cleanup, render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FakeReactorClient } from '../internal/fake-reactor-client';
import type { Reactor } from '../reactor';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// jsdom doesn't implement the WebRTC media APIs; `ReactorView` only needs
// `getTracks()` off whatever `MediaStream` its `<video>` element receives.
class FakeMediaStream {
  private readonly tracks: MediaStreamTrack[];
  constructor(tracks: MediaStreamTrack[] = []) {
    this.tracks = tracks;
  }
  getTracks(): MediaStreamTrack[] {
    return this.tracks;
  }
}
vi.stubGlobal('MediaStream', FakeMediaStream);
// jsdom doesn't implement playback either.
HTMLMediaElement.prototype.play = () => Promise.resolve();

// Import after the mock so `Reactor` (transitively, via `./store`) picks up
// the faked wasm loader.
const { ReactorProvider } = await import('./ReactorProvider');
const { useReactor } = await import('./hooks');
const { ReactorView } = await import('./ReactorView');

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

function fakeVideoTrack(): MediaStreamTrack {
  return { kind: 'video', addEventListener: vi.fn(), removeEventListener: vi.fn() } as unknown as MediaStreamTrack;
}

afterEach(() => {
  cleanup();
});

describe('ReactorView', () => {
  it('renders with the video element hidden before any track arrives', async () => {
    let reactor: Reactor | undefined;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      return <ReactorView />;
    }

    const { container } = render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await act(() => reactor?.connect() ?? Promise.resolve());

    const video = container.querySelector('video');

    expect(video).not.toBeNull();
    expect(video?.style.display).toBe('none');
    expect(video?.muted).toBe(true);
  });

  it('attaches the named track once received and shows the video element', async () => {
    let reactor: Reactor | undefined;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      return <ReactorView track="main_video" />;
    }

    const { container } = render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await act(() => reactor?.connect() ?? Promise.resolve());

    const track = fakeVideoTrack();

    currentClient().trackByNameResult = track;
    currentClient().streamByNameResult = new MediaStream();
    act(() => currentClient().emitTrackReceived('main_video', 'mid-1'));

    const video = container.querySelector('video') as HTMLVideoElement;

    expect(video.style.display).toBe('block');
    expect(video.srcObject).toBeInstanceOf(MediaStream);
    expect((video.srcObject as MediaStream).getTracks()).toContain(track);
  });

  it('ignores trackReceived events for a different track name', async () => {
    let reactor: Reactor | undefined;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      return <ReactorView track="main_video" />;
    }

    const { container } = render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await act(() => reactor?.connect() ?? Promise.resolve());

    currentClient().trackByNameResult = fakeVideoTrack();
    currentClient().streamByNameResult = new MediaStream();
    act(() => currentClient().emitTrackReceived('other_track', 'mid-1'));

    const video = container.querySelector('video');

    expect(video?.style.display).toBe('none');
  });

  it('defaults muted to false when an audioTrack is set', async () => {
    let reactor: Reactor | undefined;

    function Probe() {
      reactor = useReactor((s) => s.internal.reactor);
      return <ReactorView track="main_video" audioTrack="main_audio" />;
    }

    const { container } = render(
      <Provider>
        <Probe />
      </Provider>,
    );

    await act(() => reactor?.connect() ?? Promise.resolve());

    const video = container.querySelector('video');

    expect(video?.muted).toBe(false);
  });
});
