import { beforeEach, describe, expect, it, vi } from 'vitest';
import { InvalidStateError, StepRefusedError, type ReactorError } from './errors';
import { FakeReactorClient } from './internal/fake-reactor-client';
import type { StepResult } from './internal/reactor-wasm.types';

vi.mock('./internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Import after the mock so `Reactor` picks up the faked wasm loader.
const { Reactor } = await import('./reactor');

type ReactorInstance = InstanceType<typeof Reactor>;

async function connected(generation?: 'streamed' | 'stepped') {
  const reactor = new Reactor({ modelName: 'test-model', ...(generation ? { generation } : {}) });

  await reactor.connect();
  const client = FakeReactorClient.instances.at(-1);

  if (!client) {
    throw new Error('no FakeReactorClient was constructed');
  }
  return { reactor, client };
}

function createDeferred<T = void>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });

  return { promise, resolve };
}

/** The binding's rejected-call shape: an `Error` with the core's details
 *  `Object.assign`ed on. */
function bindingError(code: string, message: string) {
  return Object.assign(new Error(message), {
    name: 'ReactorError',
    code,
    recoverable: false,
    operation: 'step',
    timestamp_ms: 1,
  });
}

/** Just enough of a WebCodecs `VideoFrame` for `pushFrame()` to read. */
function fakeVideoFrame(format: string, width: number, height: number, fill: number) {
  const copyTo = vi.fn((destination: Uint8Array, _options?: unknown) => {
    destination.fill(fill);
    return Promise.resolve([]);
  });

  return {
    frame: {
      format,
      codedWidth: width,
      codedHeight: height,
      displayWidth: width,
      displayHeight: height,
      visibleRect: { x: 0, y: 0, width, height },
      copyTo,
    } as unknown as VideoFrame,
    copyTo,
  };
}

beforeEach(() => {
  FakeReactorClient.instances = [];
});

describe('Reactor generation option', () => {
  it('passes generation and stepTimeoutMs straight through to the binding', async () => {
    const reactor = new Reactor({ modelName: 'm', generation: 'stepped', stepTimeoutMs: 1000 });

    await reactor.connect();

    expect(FakeReactorClient.instances.at(-1)?.options).toMatchObject({
      generation: 'stepped',
      stepTimeoutMs: 1000,
    });
  });
});

describe('Reactor.step', () => {
  it('sends state and commands and resolves with the binding’s result', async () => {
    const { reactor, client } = await connected('stepped');
    const result: StepResult = {
      index: 3,
      tracks: [
        {
          track: 'main_video',
          kind: 'video',
          frames: [{ encoding: 'raw', dtype: 'uint8', shape: [1, 1, 3], data: new Uint8Array([1, 2, 3]) }],
        },
      ],
    };

    client.stepResult = result;

    await expect(
      reactor.step({ state: { prompt: 'a cat' }, commands: [{ command: 'jump', data: { h: 2 } }] }),
    ).resolves.toBe(result);
    expect(client.stepCalls).toEqual([
      { state: { prompt: 'a cat' }, commands: [{ command: 'jump', data: { h: 2 } }] },
    ]);
  });

  it('sends no state and no commands when given none', async () => {
    const { reactor, client } = await connected('stepped');

    await reactor.step();

    expect(client.stepCalls).toEqual([{ state: null, commands: [] }]);
  });

  it('rejects with the typed class for the core’s code, recorded as the last error', async () => {
    const { reactor, client } = await connected('stepped');

    client.stepImpl = () => Promise.reject(bindingError('step_refused', 'step refused: no'));

    const error = await reactor.step().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(StepRefusedError);
    expect(error).toMatchObject({ code: 'step_refused', operation: 'step' });
    expect(reactor.getLastError()).toBe(error);
  });

  it('re-emits the binding’s stepAccepted as an event', async () => {
    const { reactor, client } = await connected('stepped');
    const accepted: number[] = [];

    reactor.on('stepAccepted', (index) => accepted.push(index));
    client.emitStepAccepted(7);

    expect(accepted).toEqual([7]);
  });

  it('waits for a push called before it, however long its pixels take to read', async () => {
    const { reactor, client } = await connected('stepped');
    const read = createDeferred();
    const { frame } = fakeVideoFrame('BGRA', 1, 1, 9);

    (frame as unknown as { copyTo: unknown }).copyTo = async (destination: Uint8Array) => {
      await read.promise;
      destination.fill(9);
      return [];
    };
    const pushing = reactor.pushFrame('camera', frame);
    const stepping = reactor.step();

    await Promise.resolve();
    expect(client.stepCalls).toEqual([]);

    read.resolve();
    await Promise.all([pushing, stepping]);

    expect(client.pushVideoFrameCalls).toHaveLength(1);
    expect(client.stepCalls).toHaveLength(1);
  });

  it('clearStepInputs() delegates to the binding', async () => {
    const { reactor, client } = await connected('stepped');

    reactor.clearStepInputs();

    expect(client.clearStepInputsCalls).toBe(1);
  });
});

describe('Reactor.pushFrame', () => {
  it('pushes raw pixels as RGBA by default, with the frame’s extras', async () => {
    const { reactor, client } = await connected('stepped');
    const data = new Uint8Array(2 * 1 * 4);
    const metadata = new Uint8Array([7]);

    await reactor.pushFrame('camera', { data, width: 2, height: 1 }, { metadata, captureTimeUs: 42 });

    expect(client.pushVideoFrameCalls).toEqual([
      { name: 'camera', data, width: 2, height: 1, format: 'rgba', metadata, captureTimeUs: 42 },
    ]);
  });

  it('passes an explicit pixel format through, and no extras as null', async () => {
    const { reactor, client } = await connected('stepped');

    await reactor.pushFrame('camera', { data: new Uint8Array(3), width: 1, height: 1, format: 'rgb' });

    expect(client.pushVideoFrameCalls[0]).toMatchObject({
      format: 'rgb',
      metadata: null,
      captureTimeUs: null,
    });
  });

  it('pushes ImageData-shaped clamped bytes as a view over the same memory, not a copy', async () => {
    const { reactor, client } = await connected('stepped');
    const pixels = new Uint8ClampedArray([1, 2, 3, 4]);

    await reactor.pushFrame('camera', { data: pixels, width: 1, height: 1 });

    const pushed = client.pushVideoFrameCalls[0]?.data;

    expect(pushed).toBeInstanceOf(Uint8Array);
    expect(pushed?.buffer).toBe(pixels.buffer);
    expect(Array.from(pushed ?? [])).toEqual([1, 2, 3, 4]);
  });

  it('copies an RGB-family VideoFrame out as it is', async () => {
    const { reactor, client } = await connected('stepped');
    const { frame, copyTo } = fakeVideoFrame('BGRX', 2, 2, 5);

    await reactor.pushFrame('camera', frame);

    expect(copyTo).toHaveBeenCalledWith(expect.any(Uint8Array), {
      layout: [{ offset: 0, stride: 8 }],
    });
    expect(client.pushVideoFrameCalls[0]).toMatchObject({ width: 2, height: 2, format: 'bgra' });
    expect(client.pushVideoFrameCalls[0]?.data).toEqual(new Uint8Array(16).fill(5));
  });

  it('asks a YUV VideoFrame’s copyTo() to convert to RGBA', async () => {
    const { reactor, client } = await connected('stepped');
    const { frame, copyTo } = fakeVideoFrame('I420', 2, 1, 3);

    await reactor.pushFrame('camera', frame);

    expect(copyTo).toHaveBeenCalledWith(expect.any(Uint8Array), {
      format: 'RGBA',
      layout: [{ offset: 0, stride: 8 }],
    });
    expect(client.pushVideoFrameCalls[0]).toMatchObject({ width: 2, height: 1, format: 'rgba' });
  });

  it('pushes audio as interleaved PCM, mono unless told otherwise', async () => {
    const { reactor, client } = await connected('stepped');
    const mono = new Int16Array([1, 2]);
    const stereo = new Int16Array([1, 2, 3, 4]);

    await reactor.pushFrame('mic', { samples: mono, sampleRate: 16000 });
    await reactor.pushFrame('mic', { samples: stereo, sampleRate: 48000, channels: 2 });

    expect(client.pushAudioFrameCalls).toEqual([
      { name: 'mic', samples: mono, sampleRate: 16000, channels: 1 },
      { name: 'mic', samples: stereo, sampleRate: 48000, channels: 2 },
    ]);
  });

  it('keeps call order even when an earlier push is slower to read', async () => {
    const { reactor, client } = await connected('stepped');
    const read = createDeferred();
    const { frame } = fakeVideoFrame('RGBA', 1, 1, 0);
    const order: string[] = [];
    const pushVideo = client.pushVideoFrame.bind(client);
    const pushAudio = client.pushAudioFrame.bind(client);

    client.pushVideoFrame = (...args) => {
      order.push('video');
      pushVideo(...args);
    };
    client.pushAudioFrame = (...args) => {
      order.push('audio');
      pushAudio(...args);
    };
    (frame as unknown as { copyTo: unknown }).copyTo = async () => {
      await read.promise;
      return [];
    };
    const slow = reactor.pushFrame('camera', frame);
    const fast = reactor.pushFrame('mic', { samples: new Int16Array([1]), sampleRate: 16000 });

    read.resolve();
    await Promise.all([slow, fast]);

    expect(order).toEqual(['video', 'audio']);
  });

  it('rejects a frame the binding refuses as a ReactorError, without blocking later pushes', async () => {
    const { reactor, client } = await connected('stepped');

    client.pushVideoFrameError = Object.assign(new Error('4 bytes is not a 2x2 Rgba frame'), {
      code: 'INVALID_STATE',
    });
    const refused = reactor.pushFrame('camera', { data: new Uint8Array(4), width: 2, height: 2 });

    await expect(refused).rejects.toBeInstanceOf(InvalidStateError);
    client.pushVideoFrameError = undefined;
    await reactor.pushFrame('camera', { data: new Uint8Array(16), width: 2, height: 2 });

    expect(client.pushVideoFrameCalls).toHaveLength(1);
  });

  it('rejects outside a stepped session, before touching the binding', async () => {
    const { reactor, client } = await connected();

    const error = await reactor
      .pushFrame('camera', { data: new Uint8Array(4), width: 1, height: 1 })
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(InvalidStateError);
    expect((error as ReactorError).message).toContain('generation: "stepped"');
    expect(client.pushVideoFrameCalls).toEqual([]);
  });
});

describe('Reactor.publishTrack and generation', () => {
  async function rejection(reactor: ReactorInstance, track?: MediaStreamTrack) {
    return reactor.publishTrack('camera', track).catch((e: unknown) => e);
  }

  it('claims a stepped input track without a MediaStreamTrack', async () => {
    const { reactor, client } = await connected('stepped');

    await reactor.publishTrack('camera');

    expect(client.publishTrackCalls).toEqual([{ name: 'camera', track: undefined }]);
  });

  it('refuses a MediaStreamTrack in a stepped session, which cannot feed steps yet', async () => {
    const { reactor, client } = await connected('stepped');

    const error = await rejection(reactor, {} as MediaStreamTrack);

    expect(error).toBeInstanceOf(InvalidStateError);
    expect((error as ReactorError).message).toContain('cannot feed a stepped session yet');
    expect(client.publishTrackCalls).toEqual([]);
  });

  it('still needs a MediaStreamTrack in a streamed session', async () => {
    const { reactor, client } = await connected();

    expect(await rejection(reactor)).toBeInstanceOf(InvalidStateError);
    expect(client.publishTrackCalls).toEqual([]);
  });
});
