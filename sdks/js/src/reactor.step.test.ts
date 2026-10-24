import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import {
  DecodeError,
  InvalidStateError,
  StepCommandFailedError,
  StepNotReadyError,
  StepRefusedError,
  StepResultTooLargeError,
  StepSessionEndedError,
  type ReactorError,
} from './errors';
import { FileRef } from './file-ref';
import { FakeReactorClient } from './internal/fake-reactor-client';
import type { StepResult } from './internal/reactor-wasm.types';
import type { ReactorEventMap } from './types';

vi.mock('./internal/wasm', () => ({
  loadReactorWasm: () => Promise.resolve({ ReactorClient: FakeReactorClient }),
}));

// Import after the mock so `Reactor` picks up the faked wasm loader.
const { Reactor } = await import('./reactor');

async function connected(generation?: 'streamed' | 'stepped') {
  const reactor = new Reactor({ modelName: 'test-model', ...(generation ? { generation } : {}) });

  await reactor.connect();
  const client = FakeReactorClient.instances.at(-1);

  if (!client) {
    throw new Error('no FakeReactorClient was constructed');
  }
  return { reactor, client };
}

/** A stepped session with its `camera` and `mic` inputs published, as a
 *  push needs. */
async function steppedWithInputs() {
  const connection = await connected('stepped');

  await connection.reactor.publishTrack('camera');
  await connection.reactor.publishTrack('mic');
  return connection;
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

  const frame = {
    format,
    codedWidth: width,
    codedHeight: height,
    displayWidth: width,
    displayHeight: height,
    visibleRect: { x: 0, y: 0, width, height },
    copyTo,
    close: vi.fn(),
    // Its own clone, so a test that swaps copyTo reads through the clone too.
    clone: () => frame,
  };

  return { frame: frame as unknown as VideoFrame, copyTo };
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
      step: 3,
      tracks: [
        {
          track: 'main_video',
          kind: 'video',
          rate: 24,
          frames: [{ encoding: 'raw', dtype: 'uint8', shape: [1, 1, 3], data: new Uint8Array([1, 2, 3]) }],
        },
      ],
      elapsed: 0.25,
      files: [],
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

  it('names the request entry and the handler code a failed step call reported', async () => {
    const { reactor, client } = await connected('stepped');

    client.stepImpl = () =>
      Promise.reject(
        Object.assign(bindingError('step_command_failed', 'tag failed (queue_full): full'), {
          entry: 'commands[2]',
          command_code: 'queue_full',
        }),
      );

    const error = await reactor.step().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(StepCommandFailedError);
    expect(error).toMatchObject({ entry: 'commands[2]', command_code: 'queue_full' });
  });

  it('leaves entry and command_code unset when the failure names none', async () => {
    const { reactor, client } = await connected('stepped');

    client.stepImpl = () => Promise.reject(bindingError('step_refused', 'no'));

    const error = (await reactor.step().catch((e: unknown) => e)) as StepRefusedError;

    expect(error.entry).toBeUndefined();
    expect(error.command_code).toBeUndefined();
  });

  it.each([
    ['step_not_ready', StepNotReadyError],
    ['step_command_failed', StepCommandFailedError],
    ['step_session_ended', StepSessionEndedError],
    ['step_result_too_large', StepResultTooLargeError],
  ] as const)('rejects a %s step with its own class', async (code, ErrorClass) => {
    const { reactor, client } = await connected('stepped');

    client.stepImpl = () => Promise.reject(bindingError(code, 'no'));

    const error = await reactor.step().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ErrorClass);
    expect(error).toMatchObject({ code, operation: 'step' });
  });

  it('hands back each track’s rate: frames per second for video, samples per second for audio', async () => {
    const { reactor, client } = await connected('stepped');

    client.stepResult = {
      step: 1,
      tracks: [
        { track: 'main_video', kind: 'video', rate: 24, frames: [] },
        { track: 'main_audio', kind: 'audio', rate: 48000, frames: [] },
        { track: 'depth', kind: 'video', rate: 0, frames: [] },
      ],
      elapsed: 0.1,
      files: [],
    };

    const result = await reactor.step();

    expect(result.tracks.map(({ track, kind, rate }) => ({ track, kind, rate }))).toEqual([
      { track: 'main_video', kind: 'video', rate: 24 },
      { track: 'main_audio', kind: 'audio', rate: 48000 },
      { track: 'depth', kind: 'video', rate: 0 },
    ]);
  });

  it('hands back the files the model kept with the step', async () => {
    const { reactor, client } = await connected('stepped');

    client.stepResult = {
      step: 2,
      tracks: [],
      elapsed: 1,
      files: [{ name: 'depth.png', contentType: 'image/png', size: 2048 }],
    };

    const result = await reactor.step();

    expect(result.files).toEqual([{ name: 'depth.png', contentType: 'image/png', size: 2048 }]);
    expect(result.error).toBeUndefined();
  });

  it('resolves a step that ran but failed, with its error, rather than rejecting', async () => {
    const { reactor, client } = await connected('stepped');

    client.stepResult = { step: 4, tracks: [], elapsed: 0.5, files: [], error: 'out of memory' };

    const result = await reactor.step();

    expect(result).toMatchObject({ step: 4, error: 'out of memory' });
    expect(reactor.getLastError()).toBeUndefined();
  });

  // Checked by type-check: the result is the step's one response, so the
  // public events have no step-accepted one.
  it('has no step-accepted event: the result is the step’s one response', () => {
    expectTypeOf<ReactorEventMap>().not.toHaveProperty('stepAccepted');
  });

  it('reads a VideoFrame the caller closes as soon as pushFrame() returns', async () => {
    const { reactor, client } = await steppedWithInputs();
    const { frame } = fakeVideoFrame('BGRA', 1, 1, 0);
    const { frame: clone } = fakeVideoFrame('BGRA', 1, 1, 7);
    const closeClone = vi.fn();
    let closed = false;

    Object.assign(clone, { close: closeClone });
    Object.assign(frame, {
      close: () => {
        closed = true;
      },
      copyTo: () =>
        closed
          ? Promise.reject(new DOMException('VideoFrame is closed', 'InvalidStateError'))
          : Promise.resolve([]),
      clone: () => clone,
    });
    const pushing = reactor.pushFrame('camera', frame);

    frame.close();
    await pushing;

    expect(client.pushVideoFrameCalls[0]?.data).toEqual(new Uint8Array(4).fill(7));
    expect(closeClone).toHaveBeenCalledOnce();
  });

  it('sends a FileRef in state or command data in its wire shape', async () => {
    const { reactor, client } = await connected('stepped');
    const image = new FileRef('up_1', 'cat.png', 'image/png', 3);

    await reactor.step({
      state: { reference: image },
      commands: [{ command: 'add_layers', data: { layers: [image] } }],
    });

    const wire = { upload_id: 'up_1', name: 'cat.png', mime_type: 'image/png', size: 3 };

    expect(client.stepCalls).toEqual([
      {
        state: { reference: wire },
        commands: [{ command: 'add_layers', data: { layers: [wire] } }],
      },
    ]);
  });

  it('refuses a push to a track not published on this connection', async () => {
    const { reactor, client } = await connected('stepped');
    const pushing = reactor.pushFrame('camera', { data: new Uint8Array(4), width: 1, height: 1 });

    await expect(pushing).rejects.toThrow("publishTrack('camera') first");
    expect(client.pushVideoFrameCalls).toEqual([]);
  });

  it('stops a PublishedTrack once it is unpublished, or its connection ends', async () => {
    const { reactor, client } = await connected('stepped');
    const pixels = { data: new Uint8Array(4), width: 1, height: 1 };
    const camera = await reactor.publishTrack('camera');

    await reactor.unpublishTrack('camera');
    await expect(camera.pushFrame(pixels)).rejects.toBeInstanceOf(InvalidStateError);

    const again = await reactor.publishTrack('camera');

    await again.pushFrame(pixels);
    await reactor.disconnect(true);
    await reactor.connect();
    await reactor.publishTrack('camera');
    await expect(again.pushFrame(pixels)).rejects.toBeInstanceOf(InvalidStateError);
    expect(client.pushVideoFrameCalls).toHaveLength(1);
  });

  it('drops a push whose frame was still being read when disconnect() ran', async () => {
    const { reactor, client } = await steppedWithInputs();
    const read = createDeferred();
    const { frame } = fakeVideoFrame('BGRA', 1, 1, 9);

    Object.assign(frame, {
      copyTo: async (destination: Uint8Array) => {
        await read.promise;
        destination.fill(9);

        return [];
      },
    });
    const pushing = reactor.pushFrame('camera', frame);

    await reactor.disconnect();
    read.resolve();

    await expect(pushing).rejects.toBeInstanceOf(InvalidStateError);
    expect(client.pushVideoFrameCalls).toEqual([]);
    expect(FakeReactorClient.instances).toEqual([client]);
  });

  it('waits for a push called before it, however long its pixels take to read', async () => {
    const { reactor, client } = await steppedWithInputs();
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

    await reactor.clearStepInputs();

    expect(client.clearStepInputsCalls).toBe(1);
  });

  it('clearStepInputs() also drops a push called before it that is still being read', async () => {
    const { reactor, client } = await steppedWithInputs();
    const read = createDeferred();
    const order: string[] = [];
    const { frame } = fakeVideoFrame('BGRA', 1, 1, 9);
    const pushVideo = client.pushVideoFrame.bind(client);
    const clear = client.clearStepInputs.bind(client);

    Object.assign(frame, {
      copyTo: async () => {
        await read.promise;

        return [];
      },
    });
    client.pushVideoFrame = (...args) => {
      order.push('push');
      pushVideo(...args);
    };
    client.clearStepInputs = () => {
      order.push('clear');
      clear();
    };
    const pushing = reactor.pushFrame('camera', frame);
    const clearing = reactor.clearStepInputs();

    read.resolve();
    await Promise.all([pushing, clearing]);

    expect(order).toEqual(['push', 'clear']);
  });
});

describe('Reactor.pushFrame', () => {
  it('pushes raw pixels as RGBA by default, with the frame’s extras', async () => {
    const { reactor, client } = await steppedWithInputs();
    const data = new Uint8Array(2 * 1 * 4);
    const metadata = new Uint8Array([7]);

    await reactor.pushFrame('camera', { data, width: 2, height: 1 }, { metadata, captureTimeUs: 42 });

    expect(client.pushVideoFrameCalls).toEqual([
      { name: 'camera', data, width: 2, height: 1, format: 'rgba', metadata, captureTimeUs: 42 },
    ]);
  });

  it('passes an explicit pixel format through, and no extras as null', async () => {
    const { reactor, client } = await steppedWithInputs();

    await reactor.pushFrame('camera', { data: new Uint8Array(3), width: 1, height: 1, format: 'rgb' });

    expect(client.pushVideoFrameCalls[0]).toMatchObject({
      format: 'rgb',
      metadata: null,
      captureTimeUs: null,
    });
  });

  it('pushes ImageData-shaped clamped bytes as a view over the same memory, not a copy', async () => {
    const { reactor, client } = await steppedWithInputs();
    const pixels = new Uint8ClampedArray([1, 2, 3, 4]);

    await reactor.pushFrame('camera', { data: pixels, width: 1, height: 1 });

    const pushed = client.pushVideoFrameCalls[0]?.data;

    expect(pushed).toBeInstanceOf(Uint8Array);
    expect(pushed?.buffer).toBe(pixels.buffer);
    expect(Array.from(pushed ?? [])).toEqual([1, 2, 3, 4]);
  });

  it('copies an RGB-family VideoFrame out as it is', async () => {
    const { reactor, client } = await steppedWithInputs();
    const { frame, copyTo } = fakeVideoFrame('BGRX', 2, 2, 5);

    await reactor.pushFrame('camera', frame);

    expect(copyTo).toHaveBeenCalledWith(expect.any(Uint8Array), {
      layout: [{ offset: 0, stride: 8 }],
    });
    expect(client.pushVideoFrameCalls[0]).toMatchObject({ width: 2, height: 2, format: 'bgra' });
    expect(client.pushVideoFrameCalls[0]?.data).toEqual(new Uint8Array(16).fill(5));
  });

  it('asks a YUV VideoFrame’s copyTo() to convert to RGBA', async () => {
    const { reactor, client } = await steppedWithInputs();
    const { frame, copyTo } = fakeVideoFrame('I420', 2, 1, 3);

    await reactor.pushFrame('camera', frame);

    expect(copyTo).toHaveBeenCalledWith(expect.any(Uint8Array), {
      format: 'RGBA',
      layout: [{ offset: 0, stride: 8 }],
    });
    expect(client.pushVideoFrameCalls[0]).toMatchObject({ width: 2, height: 1, format: 'rgba' });
  });

  it('pushes audio as interleaved PCM, mono unless told otherwise', async () => {
    const { reactor, client } = await steppedWithInputs();
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
    const { reactor, client } = await steppedWithInputs();
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
    const { reactor, client } = await steppedWithInputs();

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
  it('claims a stepped input track without a MediaStreamTrack', async () => {
    const { reactor, client } = await connected('stepped');

    const published = await reactor.publishTrack('camera');

    expect(published.name).toBe('camera');
    expect(client.publishTrackCalls).toEqual([{ name: 'camera', track: undefined }]);
  });

  it('ignores a MediaStreamTrack in a stepped session with a warning, sending no RTP', async () => {
    const { reactor, client } = await connected('stepped');
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    try {
      await reactor.publishTrack('camera', {} as MediaStreamTrack);

      expect(warn).toHaveBeenCalledOnce();
      expect(String(warn.mock.calls[0]?.[0])).toContain('track.pushFrame(...)');
      // The binding is never handed the track, so no sender is attached.
      expect(client.publishTrackCalls).toEqual([{ name: 'camera', track: undefined }]);
    } finally {
      warn.mockRestore();
    }
  });

  it('feeds the stepped track through the handle’s pushFrame()', async () => {
    const { reactor, client } = await connected('stepped');
    const camera = await reactor.publishTrack('camera');
    const data = new Uint8Array(4);

    await camera.pushFrame({ data, width: 1, height: 1 }, { captureTimeUs: 9 });

    expect(client.pushVideoFrameCalls).toEqual([
      { name: 'camera', data, width: 1, height: 1, format: 'rgba', metadata: null, captureTimeUs: 9 },
    ]);
  });

  it('still needs a MediaStreamTrack in a streamed session', async () => {
    const { reactor, client } = await connected();

    const error = await reactor.publishTrack('camera').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(InvalidStateError);
    expect((error as ReactorError).message).toContain('needs a MediaStreamTrack');
    expect(client.publishTrackCalls).toEqual([]);
  });

  it('rejects a push through a streamed track’s handle', async () => {
    const { reactor } = await connected();
    const camera = await reactor.publishTrack('camera', {} as MediaStreamTrack);

    await expect(
      camera.pushFrame({ data: new Uint8Array(4), width: 1, height: 1 }),
    ).rejects.toBeInstanceOf(InvalidStateError);
  });
});

describe('Reactor.pushEncodedFrame', () => {
  const idr = new Uint8Array([0, 0, 0, 1, 0x65, 0x88]);

  it('hands the bytes on untouched, with the decoded size and the frame’s extras', async () => {
    const { reactor, client } = await steppedWithInputs();
    const metadata = new Uint8Array([7]);

    await reactor.pushEncodedFrame('camera', {
      codec: 'h264',
      data: idr,
      width: 1280,
      height: 720,
      metadata,
      captureTimeUs: 9,
    });

    expect(client.pushEncodedVideoFrameCalls).toEqual([
      { name: 'camera', codec: 'h264', data: idr, width: 1280, height: 720, metadata, captureTimeUs: 9 },
    ]);
    expect(client.pushEncodedVideoFrameCalls[0]?.data).toBe(idr);
    expect(client.pushVideoFrameCalls).toEqual([]);
  });

  it('takes an ArrayBuffer, and no extras as null', async () => {
    const { reactor, client } = await steppedWithInputs();

    await reactor.pushEncodedFrame('camera', { codec: 'h264', data: idr.slice().buffer, width: 2, height: 2 });

    expect(client.pushEncodedVideoFrameCalls[0]).toMatchObject({
      data: idr,
      metadata: null,
      captureTimeUs: null,
    });
  });

  it('copies an EncodedVideoChunk’s bytes out when called', async () => {
    const { reactor, client } = await steppedWithInputs();
    const copyTo = vi.fn((destination: Uint8Array) => destination.set(idr));
    const chunk = { type: 'key', timestamp: 0, byteLength: idr.byteLength, copyTo } as unknown as EncodedVideoChunk;

    await reactor.pushEncodedFrame('camera', { codec: 'h264', data: chunk, width: 4, height: 2 });

    expect(copyTo).toHaveBeenCalledOnce();
    expect(client.pushEncodedVideoFrameCalls[0]?.data).toEqual(idr);
  });

  it('feeds the stepped track through the handle’s pushEncodedFrame()', async () => {
    const { reactor, client } = await connected('stepped');
    const camera = await reactor.publishTrack('camera');

    await camera.pushEncodedFrame({ codec: 'h264', data: idr, width: 2, height: 2 });
    await reactor.unpublishTrack('camera');

    await expect(
      camera.pushEncodedFrame({ codec: 'h264', data: idr, width: 2, height: 2 }),
    ).rejects.toBeInstanceOf(InvalidStateError);
    expect(client.pushEncodedVideoFrameCalls).toHaveLength(1);
  });

  it.each([
    ['empty data', { codec: 'h264', data: new Uint8Array(0), width: 2, height: 2 }, 'needs data'],
    ['a zero width', { codec: 'h264', data: new Uint8Array([1]), width: 0, height: 2 }, 'decoded frame'],
    ['a zero height', { codec: 'h264', data: new Uint8Array([1]), width: 2, height: 0 }, 'decoded frame'],
    ['another codec', { codec: 'vp9', data: new Uint8Array([1]), width: 2, height: 2 }, 'unknown codec'],
    ['no data', { codec: 'h264', width: 2, height: 2 }, 'data must be'],
  ])('refuses %s before touching the binding', async (_, frame, message) => {
    const { reactor, client } = await steppedWithInputs();

    const error = await reactor
      .pushEncodedFrame('camera', frame as unknown as Parameters<typeof reactor.pushEncodedFrame>[1])
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(InvalidStateError);
    expect((error as ReactorError).message).toContain(message);
    expect((error as ReactorError).operation).toBe('pushEncodedFrame');
    expect(client.pushEncodedVideoFrameCalls).toEqual([]);
  });

  it.each([
    ['an audio', 'mic', 'sendonly audio'],
    ['a recvonly', 'main_video', 'recvonly video'],
  ])('refuses %s track', async (_, name, message) => {
    const { reactor, client } = await connected('stepped');

    client.tracksResult = [
      { name: 'camera', kind: 'video', direction: 'sendonly' },
      { name: 'mic', kind: 'audio', direction: 'sendonly' },
      { name: 'main_video', kind: 'video', direction: 'recvonly' },
    ];
    await reactor.publishTrack(name);

    const error = await reactor
      .pushEncodedFrame(name, { codec: 'h264', data: idr, width: 2, height: 2 })
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(InvalidStateError);
    expect((error as ReactorError).message).toContain(message);
    expect((error as ReactorError).message).toContain('only a sendonly video track');
    expect(client.pushEncodedVideoFrameCalls).toEqual([]);
  });

  it('rejects outside a stepped session', async () => {
    const { reactor, client } = await connected();

    const error = await reactor
      .pushEncodedFrame('camera', { codec: 'h264', data: idr, width: 2, height: 2 })
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(InvalidStateError);
    expect((error as ReactorError).message).toContain('generation: "stepped"');
    expect(client.pushEncodedVideoFrameCalls).toEqual([]);
  });

  it('refuses a push to a track not published on this connection', async () => {
    const { reactor, client } = await connected('stepped');

    await expect(
      reactor.pushEncodedFrame('camera', { codec: 'h264', data: idr, width: 2, height: 2 }),
    ).rejects.toThrow("publishTrack('camera') first");
    expect(client.pushEncodedVideoFrameCalls).toEqual([]);
  });

  it('keeps call order with raw pushes, however long a raw frame takes to read', async () => {
    const { reactor, client } = await steppedWithInputs();
    const read = createDeferred();
    const { frame } = fakeVideoFrame('RGBA', 1, 1, 0);
    const order: string[] = [];
    const pushVideo = client.pushVideoFrame.bind(client);
    const pushEncoded = client.pushEncodedVideoFrame.bind(client);

    client.pushVideoFrame = (...args) => {
      order.push('raw');
      pushVideo(...args);
    };
    client.pushEncodedVideoFrame = (...args) => {
      order.push('h264');
      pushEncoded(...args);
    };
    (frame as unknown as { copyTo: unknown }).copyTo = async () => {
      await read.promise;
      return [];
    };
    const slow = reactor.pushFrame('camera', frame);
    const fast = reactor.pushEncodedFrame('camera', { codec: 'h264', data: idr, width: 1, height: 1 });

    read.resolve();
    await Promise.all([slow, fast]);

    expect(order).toEqual(['raw', 'h264']);
  });
});

describe('Reactor.step media', () => {
  const idr = { codec: 'h264', data: new Uint8Array([0, 0, 1, 0x65]), width: 2, height: 2 } as const;
  const p = { codec: 'h264', data: new Uint8Array([0, 0, 1, 0x41]), width: 2, height: 2 } as const;

  it('pushes raw and encoded frames in order, after the ones already pushed, then steps', async () => {
    const { reactor, client } = await steppedWithInputs();
    const order: string[] = [];
    const pushVideo = client.pushVideoFrame.bind(client);
    const pushEncoded = client.pushEncodedVideoFrame.bind(client);
    const step = client.step.bind(client);

    client.pushVideoFrame = (...args) => {
      order.push(`raw ${args[0]}`);
      pushVideo(...args);
    };
    client.pushEncodedVideoFrame = (...args) => {
      order.push(`h264 ${args[0]} ${args[2][3]}`);
      pushEncoded(...args);
    };
    client.pushAudioFrame = (name) => order.push(`audio ${name}`);
    client.step = (...args) => {
      order.push('step');
      return step(...args);
    };

    void reactor.pushFrame('camera', { data: new Uint8Array(4), width: 1, height: 1 });
    await reactor.step({
      state: { prompt: 'a cat' },
      media: {
        camera: [idr, p, { data: new Uint8Array(4), width: 1, height: 1 }],
        mic: [{ samples: new Int16Array([1]), sampleRate: 16000 }],
      },
    });

    expect(order).toEqual([
      'raw camera',
      `h264 camera ${0x65}`,
      `h264 camera ${0x41}`,
      'raw camera',
      'audio mic',
      'step',
    ]);
    expect(client.stepCalls).toEqual([{ state: { prompt: 'a cat' }, commands: [] }]);
  });

  it.each([
    ['a state that is not an object', { state: ['prompt'] }, DecodeError],
    ['commands that are not an array', { commands: { command: 'x' } }, InvalidStateError],
    ['a command without a name', { commands: [{ data: {} }] }, InvalidStateError],
    ['command data that is not an object', { commands: [{ command: 'x', data: [1] }] }, DecodeError],
  ])('pushes none of its media for %s, nor sends the step', async (_, options, ErrorClass) => {
    const { reactor, client } = await steppedWithInputs();

    const error = await reactor
      .step({ ...(options as object), media: { camera: [idr] } })
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ErrorClass);
    expect(client.pushEncodedVideoFrameCalls).toEqual([]);
    expect(client.stepCalls).toEqual([]);
  });

  it('rejects before the step goes out when a frame is refused, keeping the ones before it', async () => {
    const { reactor, client } = await steppedWithInputs();

    const error = await reactor
      .step({ media: { camera: [idr, { ...p, width: 0 }, p] } })
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(InvalidStateError);
    expect(client.pushEncodedVideoFrameCalls.map((call) => call.data)).toEqual([idr.data]);
    expect(client.stepCalls).toEqual([]);
  });

  it('needs each media track published first', async () => {
    const { reactor, client } = await connected('stepped');

    await expect(reactor.step({ media: { camera: [idr] } })).rejects.toThrow(
      "publishTrack('camera') first",
    );
    expect(client.stepCalls).toEqual([]);
  });

  it.each([
    ['an array', [idr]],
    ['a track whose frames are not an array', { camera: idr }],
  ])('refuses media that is %s', async (_, media) => {
    const { reactor, client } = await steppedWithInputs();

    const error = await reactor
      .step({ media: media as unknown as Record<string, never[]> })
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(InvalidStateError);
    expect(client.stepCalls).toEqual([]);
  });
});
