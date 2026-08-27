import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DisconnectedError,
  InvalidStateError,
  ReactorError,
  RequestTimeoutError,
  UnauthorizedError,
} from './errors';
import { FakeReactorClient } from './internal/fake-reactor-client';
import { toPublicFileRef } from './internal/file-ref';
import { FileRef } from './file-ref';
import { toPublicClip } from './internal/recording';
import { STATS_INTERVAL_MS } from './internal/stats';
import type * as RecordingModule from './recording';
import type { ConnectOptions, ReactorMessage } from './internal/reactor-wasm.types';

vi.mock('./internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Only `Reactor.downloadClipAsFile()`'s delegation is under test here — the
// standalone helper's own behavior (HLS parsing, mp4box remux, …) is covered
// by `recording.test.ts`.
vi.mock('./recording', async (importOriginal) => ({
  ...(await importOriginal<typeof RecordingModule>()),
  downloadClipAsFile: vi.fn(),
}));

// Import after the mock so `Reactor` picks up the faked wasm loader.
const { Reactor } = await import('./reactor');

/** Forces the current client into existence without a real `connect()`,
 *  mirroring how `getOrCreateClient()` is reached from any public method. */
async function currentClient(reactor: InstanceType<typeof Reactor>) {
  await reactor.connect();
  const client = FakeReactorClient.instances.at(-1);

  if (!client) {
    throw new Error('no FakeReactorClient was constructed');
  }
  return client;
}

function createDeferred<T = void>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });

  return { promise, resolve };
}

beforeEach(() => {
  FakeReactorClient.instances = [];
});

describe('Reactor.reconnect', () => {
  it('forwards options straight through to the binding', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    await reactor.reconnect({ maxAttempts: 3 });

    expect(client.reconnectCalls).toEqual([{ maxAttempts: 3 }]);
  });

  it('is callable with no options, same as v2', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    await reactor.reconnect();

    expect(client.reconnectCalls).toEqual([undefined]);
  });
});

describe('Reactor.sendCommand', () => {
  it("awaits the binding's correlated reply", async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    const reply = await reactor.sendCommand('set_caption', { text: 'hi' });

    expect(reply).toEqual({ type: 'ack', data: null });
    expect(client.sendCommandCalls).toEqual([
      { command: 'set_caption', data: { text: 'hi' }, uploads: undefined },
    ]);
  });

  it('accepts a params interface with no index signature, not just Record<string, unknown>', async () => {
    interface SetCaptionParams {
      text: string;
    }

    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const params: SetCaptionParams = { text: 'hi' };

    await reactor.sendCommand('set_caption', params);

    expect(client.sendCommandCalls).toEqual([
      { command: 'set_caption', data: { text: 'hi' }, uploads: undefined },
    ]);
  });

  it('extracts FileRef values into uploads, translated to the wire shape, before calling the binding', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const fileRef = new FileRef('up_1', 'a.jpg', 'image/jpeg', 10);

    await reactor.sendCommand('set_image', { image: fileRef, caption: 'a cat' });

    expect(client.sendCommandCalls).toEqual([
      {
        command: 'set_image',
        data: { caption: 'a cat' },
        uploads: { image: { upload_id: 'up_1', name: 'a.jpg', mime_type: 'image/jpeg', size: 10 } },
      },
    ]);
  });

  it('waits out an in-flight connect() before disconnecting or freeing the client', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const gate = createDeferred<void>();

    FakeReactorClient.nextConnectImpl = () => gate.promise;

    const connectPromise = reactor.connect();
    const disconnectPromise = reactor.disconnect();

    // Let every pending microtask run: disconnect() should still be blocked
    // on the in-flight connect() and must not have touched the client yet —
    // racing them is what corrupts it for real (see disconnect()'s docs).
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    const client = FakeReactorClient.instances.at(-1);

    if (!client) {
      throw new Error('no FakeReactorClient was constructed');
    }
    expect(client.disconnectCalls).toBe(0);
    expect(client.freeCalls).toBe(0);

    gate.resolve();
    await connectPromise;
    await disconnectPromise;

    expect(client.disconnectCalls).toBe(1);
    expect(client.freeCalls).toBe(1);
  });

  it("normalizes the binding's null (a bodyless ack) to undefined", async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.sendCommandReply = null;

    const reply = await reactor.sendCommand('start');

    expect(reply).toBeUndefined();
  });
});

describe('Reactor.sendCommand runtime-scope compatibility shim', () => {
  it('routes ("requestSchema", data, "runtime") to a schema refresh, bypassing the binding\'s sendCommand', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    const reply = await reactor.sendCommand('requestSchema', {}, 'runtime');

    expect(reply).toBeUndefined();
    expect(client.requestSchemaCalls).toBe(1);
    expect(client.sendCommandCalls).toEqual([]);
    expect(reactor.getSchema()).toEqual({ commands: ['set_image'] });
  });

  it('emits both schemaReceived and a runtimeMessage("modelSchema") once the reply lands', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    await currentClient(reactor);
    const onSchema = vi.fn();
    const onRuntimeMessage = vi.fn();

    reactor.on('schemaReceived', onSchema);
    reactor.on('runtimeMessage', onRuntimeMessage);

    await reactor.sendCommand('requestSchema', {}, 'runtime');

    expect(onSchema).toHaveBeenCalledWith({ commands: ['set_image'] });
    expect(onRuntimeMessage).toHaveBeenCalledWith({
      type: 'modelSchema',
      data: { commands: ['set_image'] },
    });
  });

  it('does not reject on a requestSchema() failure, matching sendCommand\'s own never-rejects contract — but still emits it as an error event', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onError = vi.fn<(error: ReactorError) => void>();

    client.schemaError = Object.assign(new Error('boom'), { code: 'TIMEOUT', operation: 'requestSchema' });
    reactor.on('error', onError);

    await expect(reactor.sendCommand('requestSchema', {}, 'runtime')).resolves.toBeUndefined();

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0]![0]).toMatchObject({ code: 'TIMEOUT', message: 'boom' });
    expect(reactor.getLastError()).toBe(onError.mock.calls[0]![0]);
  });

  it('no-ops on ("requestCapabilities", data, "runtime") — nothing to trigger, capabilities are pushed', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    const reply = await reactor.sendCommand('requestCapabilities', {}, 'runtime');

    expect(reply).toBeUndefined();
    expect(client.sendCommandCalls).toEqual([]);
  });

  it('falls through to a normal application-scope send for any other command, with a warning', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const reply = await reactor.sendCommand('set_prompt', { prompt: 'a cat' }, 'runtime');

    expect(reply).toEqual({ type: 'ack', data: null });
    expect(client.sendCommandCalls).toEqual([
      { command: 'set_prompt', data: { prompt: 'a cat' }, uploads: undefined },
    ]);
    expect(warnSpy).toHaveBeenCalledOnce();
    warnSpy.mockRestore();
  });

  it('omitting scope behaves exactly like "application"', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    await reactor.sendCommand('set_caption', { text: 'hi' });

    expect(client.sendCommandCalls).toEqual([
      { command: 'set_caption', data: { text: 'hi' }, uploads: undefined },
    ]);
  });
});

describe('Reactor connect/construction options', () => {
  it("forwards sessionId, connectionId, autoResumeTracks, and maxAttempts to the binding's connect()", async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const options: ConnectOptions = {
      sessionId: 'session-1',
      connectionId: 42,
      autoResumeTracks: false,
      maxAttempts: 3,
    };

    await reactor.connect(undefined, options);

    const client = FakeReactorClient.instances.at(-1);

    expect(client?.connectCalls).toEqual([options]);
  });

  it('connect(jwt) sets the jwt before the client is built, matching a caller with no options', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    await reactor.connect('jwt-token');

    // No client exists yet on a first connect(), so the jwt reaches the
    // binding through its constructor argument (this.jwt), not a live
    // client?.setJwt() call — exercised instead once a client already
    // exists, in the next test.
    const client = FakeReactorClient.instances.at(-1);

    expect(client?.jwt).toBe('jwt-token');
    expect(client?.connectCalls).toEqual([undefined]);
  });

  it('connect(jwt, options) sets the jwt and forwards options', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const options: ConnectOptions = { sessionId: 'session-1' };
    const jwtResolver = () => 'jwt-token';

    await reactor.connect(jwtResolver, options);

    const client = FakeReactorClient.instances.at(-1);

    expect(client?.jwt).toBe(jwtResolver);
    expect(client?.connectCalls).toEqual([options]);
  });

  it('connect(jwt) updates a recoverably-disconnected client via client.setJwt(), not just this.jwt', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    await reactor.disconnect(true); // keeps the client alive, but disconnected

    await reactor.connect('new-jwt-token');

    expect(client.setJwtCalls).toEqual(['new-jwt-token']);
  });

  it('connect() rejects while already connected or connecting', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    await currentClient(reactor);

    await expect(reactor.connect()).rejects.toThrow(/already connected|connecting/i);
  });

  it('forwards construction options — including local and apiUrl — straight through to the binding', async () => {
    const reactor = new Reactor({ modelName: 'test-model', local: true, apiUrl: 'http://example.test' });

    await reactor.connect();

    const client = FakeReactorClient.instances.at(-1);

    expect(client?.options).toEqual({
      modelName: 'test-model',
      local: true,
      apiUrl: 'http://example.test',
    });
  });
});

describe('Reactor schema', () => {
  it('is undefined before the session reaches ready', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    await currentClient(reactor);

    expect(reactor.getSchema()).toBeUndefined();
  });

  it('auto-requests and caches the schema exactly once when status reaches ready', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.emitReady();
    await vi.waitFor(() => expect(client.requestSchemaCalls).toBe(1));

    expect(reactor.getSchema()).toEqual({ commands: ['set_image'] });

    // A second, unrelated status transition to "ready" (e.g. a reconnect)
    // refreshes the cache again rather than being suppressed.
    client.emitReady();
    await vi.waitFor(() => expect(client.requestSchemaCalls).toBe(2));
  });

  it('surfaces a failed auto-request through the error event, wrapped as a ReactorError, instead of throwing', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const schemaError = Object.assign(new Error('boom'), {
      code: 'TIMEOUT',
      name: 'ReactorError',
      operation: 'requestSchema',
    });

    client.schemaError = schemaError;

    const onError = vi.fn<(error: ReactorError) => void>();

    reactor.on('error', onError);

    client.emitReady();
    await vi.waitFor(() => expect(onError).toHaveBeenCalledTimes(1));

    const emitted = onError.mock.calls[0]![0];

    expect(emitted).toBeInstanceOf(ReactorError);
    expect(emitted).toMatchObject({ code: 'TIMEOUT', message: 'boom' });
    expect(reactor.getLastError()).toBe(emitted);
    expect(reactor.getSchema()).toBeUndefined();
  });

  it('requestSchema() calls the binding directly, independent of the cache', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    const result = await reactor.requestSchema();

    expect(result).toEqual({ commands: ['set_image'] });
    expect(client.requestSchemaCalls).toBe(1);
  });

  it('requestSchema() resolves undefined, not a null document, when the model has no schema', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.schemaResult = null;

    const result = await reactor.requestSchema();

    expect(result).toBeUndefined();
  });

  it('a null auto-request reply on ready leaves the cache undefined and does not emit schemaReceived', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onSchema = vi.fn();

    client.schemaResult = null;
    reactor.on('schemaReceived', onSchema);

    client.emitReady();
    await vi.waitFor(() => expect(client.requestSchemaCalls).toBe(1));

    expect(reactor.getSchema()).toBeUndefined();
    expect(onSchema).not.toHaveBeenCalled();
  });

  it('emits a schema event once the auto-request on ready lands', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onSchema = vi.fn();

    reactor.on('schemaReceived', onSchema);

    client.emitReady();

    await vi.waitFor(() => expect(onSchema).toHaveBeenCalledWith({ commands: ['set_image'] }));
  });

  it('also emits a runtimeMessage("modelSchema") once the auto-request on ready lands', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onRuntimeMessage = vi.fn();

    reactor.on('runtimeMessage', onRuntimeMessage);

    client.emitReady();

    await vi.waitFor(() =>
      expect(onRuntimeMessage).toHaveBeenCalledWith({
        type: 'modelSchema',
        data: { commands: ['set_image'] },
      }),
    );
  });

  it('does not emit a schema event when the auto-request fails', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.schemaError = Object.assign(new Error('boom'), { code: 'TIMEOUT' });
    const onSchema = vi.fn();

    reactor.on('schemaReceived', onSchema);

    client.emitReady();
    await vi.waitFor(() => expect(client.requestSchemaCalls).toBe(1));

    expect(onSchema).not.toHaveBeenCalled();
  });

  it('discards a stale reply if the client was replaced before it resolved', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const clientA = await currentClient(reactor);
    const gate = createDeferred<unknown>();

    clientA.requestSchemaImpl = () => gate.promise;

    clientA.emitReady();
    await vi.waitFor(() => expect(clientA.requestSchemaCalls).toBe(1));

    // Replace the client: disconnect() frees clientA, a fresh connect()
    // (mirroring a real disconnect/reconnect cycle) builds clientB.
    await reactor.disconnect();
    const clientB = await currentClient(reactor);

    clientB.schemaResult = { commands: ['from-clientB'] };
    clientB.emitReady();
    await vi.waitFor(() => expect(reactor.getSchema()).toEqual({ commands: ['from-clientB'] }));

    // clientA's long-pending request finally resolves — it must not clobber
    // the schema clientB's refresh already committed.
    gate.resolve({ commands: ['STALE-from-clientA'] });
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(reactor.getSchema()).toEqual({ commands: ['from-clientB'] });
  });

  it('discards a stale reply superseded by a newer refresh on the same client', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const gate = createDeferred<unknown>();
    let calls = 0;

    client.requestSchemaImpl = () => {
      calls += 1;
      return calls === 1 ? gate.promise : Promise.resolve({ commands: ['second'] });
    };

    client.emitReady(); // starts the first (slow) refresh
    client.emitReady(); // starts a second refresh on the same client
    await vi.waitFor(() => expect(reactor.getSchema()).toEqual({ commands: ['second'] }));

    // The first, slower refresh resolves after the second already
    // committed — it must not overwrite the newer result.
    gate.resolve({ commands: ['STALE-first'] });
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(reactor.getSchema()).toEqual({ commands: ['second'] });
  });
});

describe('Reactor capabilities', () => {
  it('is undefined before capabilitiesReceived fires', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    await currentClient(reactor);

    expect(reactor.getCapabilities()).toBeUndefined();
  });

  it('caches the capabilities and translates the wire shape to camelCase once received', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.emitCapabilitiesReceived({
      protocol_version: '1.0',
      tracks: [{ name: 'output', kind: 'video', direction: 'recvonly' }],
      commands: [{ name: 'set_image' }],
      emission_fps: 30,
    });

    expect(reactor.getCapabilities()).toEqual({
      protocolVersion: '1.0',
      tracks: [{ name: 'output', kind: 'video', direction: 'recvonly' }],
      commands: [{ name: 'set_image' }],
      emissionFps: 30,
    });
  });

  it('omits optional fields the wire payload left out, rather than passing them through as undefined', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.emitCapabilitiesReceived({ protocol_version: '1.0', tracks: [] });

    expect(reactor.getCapabilities()).toEqual({ protocolVersion: '1.0', tracks: [] });
  });

  it('emits a capabilitiesReceived event with the translated value', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onCapabilities = vi.fn();

    reactor.on('capabilitiesReceived', onCapabilities);
    client.emitCapabilitiesReceived({ protocol_version: '1.0', tracks: [] });

    expect(onCapabilities).toHaveBeenCalledWith({ protocolVersion: '1.0', tracks: [] });
  });

  it('is cleared on disconnect and on dispose', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.emitCapabilitiesReceived({ protocol_version: '1.0', tracks: [] });
    expect(reactor.getCapabilities()).not.toBeUndefined();

    await reactor.disconnect();
    expect(reactor.getCapabilities()).toBeUndefined();
  });

  it('is cleared on a recoverable disconnect(true) too, not just the default disconnect()', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.emitCapabilitiesReceived({ protocol_version: '1.0', tracks: [] });
    expect(reactor.getCapabilities()).not.toBeUndefined();

    await reactor.disconnect(true);
    expect(reactor.getCapabilities()).toBeUndefined();
  });
});

describe('Reactor.getSessionInfo', () => {
  it('is undefined before a client exists', () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    expect(reactor.getSessionInfo()).toBeUndefined();
  });

  it('reads live off the binding rather than caching a snapshot', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.sessionInfoResult = { session_id: 'sess_1', state: 'ready' };

    expect(reactor.getSessionInfo()).toEqual({ session_id: 'sess_1', state: 'ready' });
  });
});

describe('Reactor.requestClip / requestRecording / downloadClipAsFile', () => {
  it('requestClip forwards durationSeconds and translates the wire Clip to camelCase', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    const clip = await reactor.requestClip(10);

    expect(client.requestClipCalls).toEqual([10]);
    expect(clip).toEqual(toPublicClip(client.requestClipResult));
  });

  it('requestRecording takes no arguments and translates the wire Clip to camelCase', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    const clip = await reactor.requestRecording();

    expect(client.requestRecordingCalls).toBe(1);
    expect(clip).toEqual(toPublicClip(client.requestRecordingResult));
  });

  it('downloadClipAsFile delegates to the standalone helper with the same arguments', async () => {
    const { downloadClipAsFile } = await import('./recording');
    const reactor = new Reactor({ modelName: 'test-model' });
    const clip = toPublicClip((await currentClient(reactor)).requestClipResult);
    const blob = new Blob(['mp4-bytes']);

    vi.mocked(downloadClipAsFile).mockResolvedValueOnce(blob);

    const result = await reactor.downloadClipAsFile(clip, 'out.mp4', { jwt: 'jwt-token' });

    expect(result).toBe(blob);
    expect(downloadClipAsFile).toHaveBeenCalledWith(clip, 'out.mp4', { jwt: 'jwt-token' });
  });
});

describe('Reactor tracks', () => {
  it('delegates publishTrack/unpublishTrack/pauseTrack/resumeTrack to the binding', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const track = {} as MediaStreamTrack;

    await reactor.publishTrack('camera', track);
    await reactor.pauseTrack('camera');
    await reactor.resumeTrack('camera');
    await reactor.unpublishTrack('camera');

    expect(client.publishTrackCalls).toEqual([{ name: 'camera', track }]);
    expect(client.pauseTrackCalls).toEqual(['camera']);
    expect(client.resumeTrackCalls).toEqual(['camera']);
    expect(client.unpublishTrackCalls).toEqual(['camera']);
  });

  it('unpublishTrack() never rejects — a failure is reported via the error event', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.unpublishTrack = () =>
      Promise.reject(
        Object.assign(new Error('boom'), {
          name: 'ReactorError',
          code: 'TRANSPORT_ERROR',
          operation: 'unpublishTrack',
        }),
      );
    const onError = vi.fn<(error: ReactorError) => void>();

    reactor.on('error', onError);

    await expect(reactor.unpublishTrack('camera')).resolves.toBeUndefined();

    expect(onError).toHaveBeenCalledTimes(1);
    const error = onError.mock.calls[0]![0];

    expect(error).toBeInstanceOf(ReactorError);
    expect(error.message).toBe('boom');
  });

  it('reads tracks()/trackMapping()/pausedTracks() straight off the binding', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.tracksResult = [{ name: 'model-output', kind: 'video', direction: 'recvonly' }];
    client.trackMappingResult = [
      { name: 'model-output', kind: 'video', direction: 'recvonly', mid: '0' },
    ];
    client.pausedTracksResult = ['model-output'];

    expect(reactor.tracks()).toEqual(client.tracksResult);
    expect(reactor.trackMapping()).toEqual(client.trackMappingResult);
    expect(reactor.pausedTracks()).toEqual(['model-output']);
  });

  it('falls back to empty introspection results before a client exists', () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    expect(reactor.tracks()).toEqual([]);
    expect(reactor.trackMapping()).toEqual([]);
    expect(reactor.pausedTracks()).toEqual([]);
  });

  it('re-emits trackReceived resolved to (name, track, stream, mid)', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const track = {} as MediaStreamTrack;
    const stream = {} as MediaStream;

    client.trackByNameResult = track;
    client.streamByNameResult = stream;
    const onTrackReceived = vi.fn();

    reactor.on('trackReceived', onTrackReceived);

    client.emitTrackReceived('model-output', '0');

    expect(onTrackReceived).toHaveBeenCalledWith('model-output', track, stream, '0');
  });

  it('does not emit if the track/stream cannot be resolved (structurally shouldn’t happen)', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onTrackReceived = vi.fn();

    reactor.on('trackReceived', onTrackReceived);

    // `reactor-core` only ever dispatches this event once the track is
    // already resolvable; simulating the (should-be-impossible) case where
    // it isn't, to check this doesn't emit a lie about the non-optional type.
    client.emitTrackReceived('model-output', undefined);

    expect(onTrackReceived).not.toHaveBeenCalled();
  });

  it('waits out an in-flight publishTrack() before disconnecting or freeing the client', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const gate = createDeferred<void>();
    const originalPublishTrack = client.publishTrack.bind(client);

    client.publishTrack = (name, track) => {
      const call = originalPublishTrack(name, track);

      return gate.promise.then(() => call);
    };

    const publishPromise = reactor.publishTrack('camera', {} as MediaStreamTrack);
    const disconnectPromise = reactor.disconnect();

    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(client.disconnectCalls).toBe(0);
    expect(client.freeCalls).toBe(0);

    gate.resolve();
    await publishPromise;
    await disconnectPromise;

    expect(client.disconnectCalls).toBe(1);
    expect(client.freeCalls).toBe(1);
  });

  it('resolves media through the getXByMid/getXByName escape hatches', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const peerConnection = {} as RTCPeerConnection;
    const track = {} as MediaStreamTrack;
    const stream = {} as MediaStream;

    client.peerConnectionResult = peerConnection;
    client.trackByMidResult = track;
    client.streamByMidResult = stream;
    client.trackByNameResult = track;
    client.streamByNameResult = stream;

    expect(reactor.getPeerConnection()).toBe(peerConnection);
    expect(reactor.getTrackByMid('0')).toBe(track);
    expect(reactor.getStreamByMid('0')).toBe(stream);
    expect(reactor.getTrackByName('model-output')).toBe(track);
    expect(reactor.getStreamByName('model-output')).toBe(stream);
  });

  it('falls back to undefined escape hatches before a client exists', () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    expect(reactor.getPeerConnection()).toBeUndefined();
    expect(reactor.getTrackByMid('0')).toBeUndefined();
    expect(reactor.getStreamByMid('0')).toBeUndefined();
    expect(reactor.getTrackByName('model-output')).toBeUndefined();
    expect(reactor.getStreamByName('model-output')).toBeUndefined();
  });
});

describe('Reactor stats', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('getStats() and getConnectionTimings() are undefined before a client exists', () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    expect(reactor.getStats()).toBeUndefined();
    expect(reactor.getConnectionTimings()).toBeUndefined();
  });

  it('computes connectionTimings from the connecting/waiting/ready status sequence', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'setInterval', 'performance'] });
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.emitConnecting();
    vi.advanceTimersByTime(100);
    client.emitWaiting();
    vi.advanceTimersByTime(250);
    client.emitReady();

    expect(reactor.getConnectionTimings()).toEqual({
      sessionCreationMs: 100,
      transportConnectingMs: 250,
      totalMs: 350,
    });
  });

  it('polls getPeerConnection().getStats() every STATS_INTERVAL_MS once ready, emitting statsUpdate', async () => {
    vi.useFakeTimers();
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const report = { forEach: () => {}, get: () => undefined } as unknown as RTCStatsReport;
    const getStats = vi.fn().mockResolvedValue(report);

    client.peerConnectionResult = { getStats } as unknown as RTCPeerConnection;
    const onStatsUpdate = vi.fn();

    reactor.on('statsUpdate', onStatsUpdate);

    client.emitReady();
    expect(getStats).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS);
    expect(getStats).toHaveBeenCalledTimes(1);
    await vi.waitFor(() => expect(onStatsUpdate).toHaveBeenCalledTimes(1));
    expect(reactor.getStats()).toEqual(onStatsUpdate.mock.calls[0]?.[0]);

    await vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS);
    expect(getStats).toHaveBeenCalledTimes(2);
  });

  it('folds the current connectionTimings into every statsUpdate sample', async () => {
    vi.useFakeTimers();
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const report = { forEach: () => {}, get: () => undefined } as unknown as RTCStatsReport;

    client.peerConnectionResult = { getStats: vi.fn().mockResolvedValue(report) } as unknown as RTCPeerConnection;

    client.emitConnecting();
    client.emitWaiting();
    client.emitReady();

    await vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS);

    expect(reactor.getStats()?.connectionTimings).toBe(reactor.getConnectionTimings());
  });

  it('stops polling and clears stats on disconnect()', async () => {
    vi.useFakeTimers();
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const report = { forEach: () => {}, get: () => undefined } as unknown as RTCStatsReport;
    const getStats = vi.fn().mockResolvedValue(report);

    client.peerConnectionResult = { getStats } as unknown as RTCPeerConnection;

    client.emitReady();
    await vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS);
    expect(getStats).toHaveBeenCalledTimes(1);

    await reactor.disconnect();
    expect(reactor.getStats()).toBeUndefined();
    expect(reactor.getConnectionTimings()).toBeUndefined();

    await vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS * 2);
    expect(getStats).toHaveBeenCalledTimes(1);
  });

  it('discards an in-flight getStats() sample that resolves after polling was stopped', async () => {
    vi.useFakeTimers();
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const gate = createDeferred<RTCStatsReport>();
    const getStats = vi.fn().mockReturnValue(gate.promise);

    client.peerConnectionResult = { getStats } as unknown as RTCPeerConnection;
    const onStatsUpdate = vi.fn();

    reactor.on('statsUpdate', onStatsUpdate);

    client.emitReady();
    await vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS);
    expect(getStats).toHaveBeenCalledTimes(1); // in flight, not yet resolved

    // Recoverable: stops polling but keeps this same `client` (and its
    // `getPeerConnection()`) alive — the scenario a plain `this.client !==
    // client` check inside the pending `.then()` couldn't have caught.
    await reactor.disconnect(true);
    expect(reactor.getStats()).toBeUndefined();

    gate.resolve({ forEach: () => {}, get: () => undefined } as unknown as RTCStatsReport);
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();

    expect(reactor.getStats()).toBeUndefined();
    expect(onStatsUpdate).not.toHaveBeenCalled();
  });

  it('does not poll while getPeerConnection() is undefined', async () => {
    vi.useFakeTimers();
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.peerConnectionResult = undefined;
    const onStatsUpdate = vi.fn();

    reactor.on('statsUpdate', onStatsUpdate);

    client.emitReady();
    await vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS * 2);

    expect(onStatsUpdate).not.toHaveBeenCalled();
    expect(reactor.getStats()).toBeUndefined();
  });

  it('stops polling on any other status transition too, not just an explicit disconnect()', async () => {
    vi.useFakeTimers();
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const report = { forEach: () => {}, get: () => undefined } as unknown as RTCStatsReport;
    const getStats = vi.fn().mockResolvedValue(report);

    client.peerConnectionResult = { getStats } as unknown as RTCPeerConnection;

    client.emitReady();
    await vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS);
    expect(getStats).toHaveBeenCalledTimes(1);

    // e.g. a transport error dropping straight to "disconnected" without
    // going through Reactor.disconnect() at all.
    client.emitDisconnected();
    expect(reactor.getStats()).toBeUndefined();

    await vi.advanceTimersByTimeAsync(STATS_INTERVAL_MS * 2);
    expect(getStats).toHaveBeenCalledTimes(1);
  });
});

describe('Reactor.uploadFile', () => {
  it('passes the file and optional name through to the binding, returning a camelCase FileRef', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const file = new Blob(['hi']);

    const ref = await reactor.uploadFile(file, { name: 'photo.jpg' });

    expect(ref).toEqual(toPublicFileRef(client.uploadFileResult));
    expect(client.uploadFileCalls).toEqual([{ file, name: 'photo.jpg' }]);
  });

  it('omits name when no options are given, leaving the binding to default it', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const file = new Blob(['hi']);

    await reactor.uploadFile(file);

    expect(client.uploadFileCalls).toEqual([{ file, name: undefined }]);
  });

  it('round-trips: an uploaded FileRef passed into sendCommand lands in uploads, translated back to the wire shape', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const file = new Blob(['hi']);

    const ref = await reactor.uploadFile(file, { name: 'photo.jpg' });

    await reactor.sendCommand('set_image', { image: ref, caption: 'a cat' });

    expect(client.sendCommandCalls).toEqual([
      {
        command: 'set_image',
        data: { caption: 'a cat' },
        uploads: { image: client.uploadFileResult },
      },
    ]);
  });

  it('waits out an in-flight uploadFile() before disconnecting or freeing the client', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const gate = createDeferred<void>();
    const originalUploadFile = client.uploadFile.bind(client);

    client.uploadFile = (file, name) => {
      const call = originalUploadFile(file, name);

      return gate.promise.then(() => call);
    };

    const uploadPromise = reactor.uploadFile(new Blob(['hi']));
    const disconnectPromise = reactor.disconnect();

    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(client.disconnectCalls).toBe(0);
    expect(client.freeCalls).toBe(0);

    gate.resolve();
    await uploadPromise;
    await disconnectPromise;

    expect(client.disconnectCalls).toBe(1);
    expect(client.freeCalls).toBe(1);
  });
});

describe('Reactor messaging events', () => {
  it('re-emits application messages via the message event', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onMessage = vi.fn();

    reactor.on('message', onMessage);

    const payload: ReactorMessage = { type: 'greeting', data: { text: 'hi' } };

    client.emitMessage(payload);

    expect(onMessage).toHaveBeenCalledWith(payload);
  });

  it('re-emits platform messages via the runtimeMessage event', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onRuntimeMessage = vi.fn();

    reactor.on('runtimeMessage', onRuntimeMessage);

    const payload: ReactorMessage = { type: 'moderation', data: { flagged: false } };

    client.emitRuntimeMessage(payload);

    expect(onRuntimeMessage).toHaveBeenCalledWith(payload);
  });
});

describe('Reactor error handling', () => {
  it('wraps an onError payload into the typed subclass matching its code', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onError = vi.fn<(error: ReactorError) => void>();

    reactor.on('error', onError);

    client.emitError({
      code: 'UNAUTHORIZED',
      message: 'token expired',
      recoverable: false,
      status: 401,
      operation: 'connect',
      timestamp_ms: 123,
    });

    expect(onError).toHaveBeenCalledTimes(1);
    const error = onError.mock.calls[0]![0];

    expect(error).toBeInstanceOf(UnauthorizedError);
    expect(error).toBeInstanceOf(ReactorError);
    // `code` is reactor-core's own canonical code, untouched by `operation`.
    expect(error).toMatchObject({
      code: 'UNAUTHORIZED',
      message: 'token expired',
      recoverable: false,
      status: 401,
      operation: 'connect',
      timestamp_ms: 123,
    });
  });

  it('falls back to the base ReactorError class for a code with no matching subclass', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onError = vi.fn<(error: ReactorError) => void>();

    reactor.on('error', onError);

    client.emitError({
      code: 'SOME_NEW_PLATFORM_CODE',
      message: 'model rejected the request',
      operation: 'requestSchema',
    });

    const error = onError.mock.calls[0]![0];

    expect(error).toBeInstanceOf(ReactorError);
    expect(error.constructor).toBe(ReactorError);
    expect(error.code).toBe('SOME_NEW_PLATFORM_CODE');
  });

  it('wraps a rejected connect() into the matching typed ReactorError subclass', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });

    // A rejected connect() never reaches the fake's "ready" transition, so
    // status stays 'disconnected' and the guard lets a second attempt
    // through below — no currentClient()/disconnect() cycle needed.
    FakeReactorClient.nextConnectImpl = () =>
      Promise.reject(
        Object.assign(new Error('the connection dropped'), {
          name: 'ReactorError',
          code: 'DISCONNECTED',
          operation: 'connect',
          recoverable: true,
          timestamp_ms: 456,
        }),
      );

    await expect(reactor.connect()).rejects.toBeInstanceOf(DisconnectedError);
    await expect(reactor.connect()).rejects.toMatchObject({
      code: 'DISCONNECTED',
      message: 'the connection dropped',
      recoverable: true,
    });
    // A rejected call updates getLastError() too, not just the error event —
    // see getLastError()'s doc comment.
    expect(reactor.getLastError()).toMatchObject({ code: 'DISCONNECTED' });
  });

  it('wraps a rejected publishTrack() the same way as connect()', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    client.publishTrack = () =>
      Promise.reject(
        Object.assign(new Error('timed out'), {
          name: 'ReactorError',
          code: 'REQUEST_TIMEOUT',
          operation: 'publishTrack',
        }),
      );

    await expect(reactor.publishTrack('camera', {} as MediaStreamTrack)).rejects.toBeInstanceOf(
      RequestTimeoutError,
    );
  });

  describe('sendCommand', () => {
    it('never rejects: a failure resolves undefined, sets lastError, and emits error', async () => {
      const reactor = new Reactor({ modelName: 'test-model' });
      const client = await currentClient(reactor);

      client.sendCommand = () =>
        Promise.reject(
          Object.assign(new Error('not ready'), {
            name: 'ReactorError',
            code: 'INVALID_STATE',
            operation: 'sendCommand',
          }),
        );
      const onError = vi.fn<(error: ReactorError) => void>();

      reactor.on('error', onError);

      const reply = await reactor.sendCommand('set_prompt', { prompt: 'a cat' });

      expect(reply).toBeUndefined();
      expect(onError).toHaveBeenCalledTimes(1);
      const error = onError.mock.calls[0]![0];

      expect(error).toBeInstanceOf(InvalidStateError);
      expect(error.code).toBe('INVALID_STATE');
      expect(reactor.getLastError()).toBe(error);
    });

    it('still resolves with the reply on success, leaving lastError untouched', async () => {
      const reactor = new Reactor({ modelName: 'test-model' });
      const client = await currentClient(reactor);

      client.emitError({ code: 'DISCONNECTED', message: 'earlier, unrelated failure' });
      const priorError = reactor.getLastError();

      const reply = await reactor.sendCommand('set_caption', { text: 'hi' });

      expect(reply).toEqual({ type: 'ack', data: null });
      expect(reactor.getLastError()).toBe(priorError);
    });
  });

  it('getLastError() reflects the most recent failure, from an error event or a rejected call alike', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    expect(reactor.getLastError()).toBeUndefined();

    client.emitError({ code: 'SERVER_ERROR', message: 'boom', operation: 'requestSchema' });

    expect(reactor.getLastError()?.code).toBe('SERVER_ERROR');

    // A later rejected call — one that only throws, never emits `error` —
    // must still update getLastError(), or it goes stale after that call.
    client.pauseTrack = () =>
      Promise.reject(
        Object.assign(new Error('not ready'), {
          name: 'ReactorError',
          code: 'INVALID_STATE',
          operation: 'pauseTrack',
        }),
      );

    await expect(reactor.pauseTrack('camera')).rejects.toBeInstanceOf(InvalidStateError);
    expect(reactor.getLastError()?.code).toBe('INVALID_STATE');
  });
});
