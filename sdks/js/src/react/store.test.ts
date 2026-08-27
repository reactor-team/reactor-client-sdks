/** @vitest-environment jsdom */
import { describe, expect, it, vi } from 'vitest';
import { NetworkError } from '../errors';
import { FakeReactorClient } from '../internal/fake-reactor-client';

vi.mock('../internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Import after the mock so `Reactor` (transitively, via `./store`) picks up
// the faked wasm loader.
const { createReactorStore } = await import('./store');

function currentClient(): FakeReactorClient {
  const client = FakeReactorClient.instances.at(-1);

  if (!client) {
    throw new Error('no FakeReactorClient constructed yet');
  }

  return client;
}

describe('createReactorStore', () => {
  it('starts disconnected with no session, error, or message', () => {
    const store = createReactorStore({ modelName: 'test-model' });

    expect(store.getState()).toMatchObject({
      status: 'disconnected',
      sessionId: undefined,
      lastError: undefined,
      lastMessage: undefined,
    });
  });

  it('mirrors statusChanged into status', async () => {
    const store = createReactorStore({ modelName: 'test-model' });

    await store.getState().connect();
    currentClient().emitConnecting();
    expect(store.getState().status).toBe('connecting');

    currentClient().emitReady();
    expect(store.getState().status).toBe('ready');
  });

  it('mirrors sessionIdChanged into sessionId', async () => {
    const store = createReactorStore({ modelName: 'test-model' });

    await store.getState().connect();
    currentClient().emitSessionIdChanged('session-123');
    expect(store.getState().sessionId).toBe('session-123');
  });

  it('mirrors an error event into lastError', async () => {
    const store = createReactorStore({ modelName: 'test-model' });

    await store.getState().connect();
    currentClient().emitError({ code: 'NETWORK_ERROR', message: 'boom' });
    expect(store.getState().lastError).toBeInstanceOf(NetworkError);
  });

  it('mirrors a message event into lastMessage', async () => {
    const store = createReactorStore({ modelName: 'test-model' });

    await store.getState().connect();
    currentClient().emitMessage({ type: 'reply', data: { ok: true } });
    expect(store.getState().lastMessage).toEqual({ type: 'reply', data: { ok: true } });
  });

  it('applies defaultConnectOptions, with a call-site option winning', async () => {
    const store = createReactorStore({ modelName: 'test-model' }, { maxAttempts: 3 });

    await store.getState().connect(undefined, { maxAttempts: 5 });
    expect(currentClient().connectCalls.at(-1)).toEqual({ maxAttempts: 5 });
  });

  it('binds publish/unpublish/pauseTrack/resumeTrack to the underlying reactor', async () => {
    const store = createReactorStore({ modelName: 'test-model' });

    await store.getState().connect();
    const track = {} as MediaStreamTrack;

    await store.getState().publish('camera', track);
    await store.getState().unpublish('camera');
    await store.getState().pauseTrack('camera');
    await store.getState().resumeTrack('camera');

    const client = currentClient();

    expect(client.publishTrackCalls).toEqual([{ name: 'camera', track }]);
    expect(client.unpublishTrackCalls).toEqual(['camera']);
    expect(client.pauseTrackCalls).toEqual(['camera']);
    expect(client.resumeTrackCalls).toEqual(['camera']);
  });

  it('exposes the same Reactor instance through internal.reactor', () => {
    const store = createReactorStore({ modelName: 'test-model' });

    expect(store.getState().internal.reactor).toBe(store.getState().internal.reactor);
  });

  it('mirrors trackReceived into tracks, keyed by name, and clears it on disconnect', async () => {
    const store = createReactorStore({ modelName: 'test-model' });

    await store.getState().connect();
    const track = {} as MediaStreamTrack;

    currentClient().emitTrackReceived('output', undefined);
    // FakeReactorClient's trackByName default is undefined — Reactor only
    // emits `trackReceived` once both track/stream resolve, so drive the
    // store's listener directly via the fake's resolved getters instead.
    currentClient().trackByNameResult = track;
    currentClient().streamByNameResult = {} as MediaStream;
    currentClient().emitTrackReceived('output', undefined);

    expect(store.getState().tracks).toEqual({ output: track });

    currentClient().emitDisconnected();
    expect(store.getState().tracks).toEqual({});
  });

  it('exposes the jwt and defaultConnectOptions it was created with', () => {
    const store = createReactorStore({ modelName: 'test-model', jwt: 'token' }, { autoResumeTracks: false });

    expect(store.getState().jwtToken).toBe('token');
    expect(store.getState().connectOptions).toEqual({ autoResumeTracks: false });
  });

  it('binds uploadFile to the underlying reactor, translating the wire FileRef to camelCase', async () => {
    const store = createReactorStore({ modelName: 'test-model' });

    await store.getState().connect();
    const file = new Blob(['hi']);

    const result = await store.getState().uploadFile(file, { name: 'a.txt' });

    expect(currentClient().uploadFileCalls).toEqual([{ file, name: 'a.txt' }]);
    expect(result).toEqual({ uploadId: 'up_1', name: 'upload', mimeType: 'application/octet-stream', size: 0 });
  });

  it('binds requestClip/requestRecording to the underlying reactor', async () => {
    const store = createReactorStore({ modelName: 'test-model' });

    await store.getState().connect();

    const clip = await store.getState().requestClip(10);
    const recording = await store.getState().requestRecording();

    expect(currentClient().requestClipCalls).toEqual([10]);
    expect(currentClient().requestRecordingCalls).toBe(1);
    expect(clip.kind).toBe('snap');
    expect(recording.kind).toBe('recording');
  });

  it('binds downloadClipAsFile to the underlying reactor', async () => {
    const store = createReactorStore({ modelName: 'test-model' });
    const blob = new Blob(['clip']);
    const spy = vi
      .spyOn(store.getState().internal.reactor, 'downloadClipAsFile')
      .mockResolvedValue(blob);
    const clip = {
      sessionId: 'sess_1',
      kind: 'snap' as const,
      startMarker: 0,
      endMarker: 10,
      nowMarker: 10,
      predictedReadyAtMs: 0,
      playlistUrl: 'https://api.reactor.test/clips?session_id=sess_1',
    };

    const result = await store.getState().downloadClipAsFile(clip, 'out.mp4');

    expect(spy).toHaveBeenCalledWith(clip, 'out.mp4', undefined);
    expect(result).toBe(blob);
  });
});
