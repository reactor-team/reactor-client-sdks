import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactorMessage, ReactorStatus } from './internal/reactor-wasm.types';

const { FakeReactorClient } = vi.hoisted(() => {
  class FakeReactorClient {
    static instances: FakeReactorClient[] = [];

    sendCommandCalls: Array<{
      command: string;
      data: Record<string, unknown> | undefined;
      uploads: Record<string, unknown> | undefined;
    }> = [];
    requestSchemaCalls = 0;
    schemaResult: unknown = { commands: ['set_image'] };
    schemaError: Error | undefined;
    // Overridable so a test can hold requestSchema() open, to simulate a
    // stale reply landing after the client was replaced or superseded by a
    // newer refresh.
    requestSchemaImpl: (() => Promise<unknown>) | undefined;
    // The real binding's serializer hands back `null`, not `undefined`, for
    // a bodyless ack — despite its own typed signature saying otherwise. See
    // `Reactor.sendCommand()`'s normalization.
    sendCommandReply: ReactorMessage | null = { type: 'ack', data: null };

    private statusListener: ((status: ReactorStatus) => void) | undefined;
    private messageListener: ((message: ReactorMessage) => void) | undefined;
    private runtimeMessageListener: ((message: ReactorMessage) => void) | undefined;
    private errorListener: ((error: unknown) => void) | undefined;

    constructor(
      readonly options: unknown,
      readonly jwt: unknown,
    ) {
      FakeReactorClient.instances.push(this);
    }

    // Overridable so a test can hold connect() open (e.g. `() =>
    // someDeferred.promise`) to simulate disconnect() racing an in-flight
    // connect().
    connectImpl: (() => Promise<void>) | undefined;
    disconnectCalls = 0;
    freeCalls = 0;

    setJwt(): void {}
    async connect(): Promise<void> {
      if (this.connectImpl) await this.connectImpl();
    }
    disconnect(): Promise<void> {
      this.disconnectCalls += 1;
      return Promise.resolve();
    }
    async reconnect(): Promise<void> {}
    free(): void {
      this.freeCalls += 1;
    }
    status(): ReactorStatus {
      return 'ready';
    }
    sessionId(): string | undefined {
      return undefined;
    }

    sendCommand(
      command: string,
      data: Record<string, unknown> | undefined,
      uploads: Record<string, unknown> | undefined,
    ): Promise<ReactorMessage | undefined> {
      this.sendCommandCalls.push({ command, data, uploads });
      return Promise.resolve(this.sendCommandReply as ReactorMessage | undefined);
    }

    requestSchema(): Promise<unknown> {
      this.requestSchemaCalls += 1;
      if (this.requestSchemaImpl) return this.requestSchemaImpl();
      if (this.schemaError) return Promise.reject(this.schemaError);
      return Promise.resolve(this.schemaResult);
    }

    onStatusChanged(listener: (status: ReactorStatus) => void): void {
      this.statusListener = listener;
    }
    onSessionIdChanged(): void {}
    onError(listener: (error: unknown) => void): void {
      this.errorListener = listener;
    }
    onMessage(listener: (message: ReactorMessage) => void): void {
      this.messageListener = listener;
    }
    onRuntimeMessage(listener: (message: ReactorMessage) => void): void {
      this.runtimeMessageListener = listener;
    }

    emitReady(): void {
      this.statusListener?.('ready');
    }
    emitMessage(message: ReactorMessage): void {
      this.messageListener?.(message);
    }
    emitRuntimeMessage(message: ReactorMessage): void {
      this.runtimeMessageListener?.(message);
    }
  }

  return { FakeReactorClient };
});

vi.mock('./internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Import after the mock so `Reactor` picks up the faked wasm loader.
const { Reactor } = await import('./reactor');

/** Forces the current client into existence without a real `connect()`,
 *  mirroring how `getOrCreateClient()` is reached from any public method. */
async function currentClient(reactor: InstanceType<typeof Reactor>) {
  await reactor.connect();
  const client = FakeReactorClient.instances.at(-1);
  if (!client) throw new Error('no FakeReactorClient was constructed');
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

  it('extracts FileRef values into uploads before calling the binding', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const fileRef = { upload_id: 'up_1', name: 'a.jpg', mime_type: 'image/jpeg', size: 10 };

    await reactor.sendCommand('set_image', { image: fileRef, caption: 'a cat' });

    expect(client.sendCommandCalls).toEqual([
      {
        command: 'set_image',
        data: { caption: 'a cat' },
        uploads: { image: fileRef },
      },
    ]);
  });

  it('waits out an in-flight connect() before disconnecting or freeing the client', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const gate = createDeferred<void>();
    client.connectImpl = () => gate.promise;

    const connectPromise = reactor.connect();
    const disconnectPromise = reactor.disconnect();

    // Let every pending microtask run: disconnect() should still be blocked
    // on the in-flight connect() and must not have touched the client yet —
    // racing them is what corrupts it for real (see disconnect()'s docs).
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
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

  it('surfaces a failed auto-request through the error event instead of throwing', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const schemaError = Object.assign(new Error('boom'), { code: 'TIMEOUT', name: 'ReactorError' });
    client.schemaError = schemaError;

    const onError = vi.fn();
    reactor.on('error', onError);

    client.emitReady();
    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith(schemaError));
    expect(reactor.getSchema()).toBeUndefined();
  });

  it('requestSchema() calls the binding directly, independent of the cache', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);

    const result = await reactor.requestSchema();

    expect(result).toEqual({ commands: ['set_image'] });
    expect(client.requestSchemaCalls).toBe(1);
  });

  it('emits a schema event once the auto-request on ready lands', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    const onSchema = vi.fn();
    reactor.on('schema', onSchema);

    client.emitReady();

    await vi.waitFor(() => expect(onSchema).toHaveBeenCalledWith({ commands: ['set_image'] }));
  });

  it('does not emit a schema event when the auto-request fails', async () => {
    const reactor = new Reactor({ modelName: 'test-model' });
    const client = await currentClient(reactor);
    client.schemaError = Object.assign(new Error('boom'), { code: 'TIMEOUT' });
    const onSchema = vi.fn();
    reactor.on('schema', onSchema);

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
