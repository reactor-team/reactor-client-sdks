import { describe, expect, it } from 'vitest';
import { InvalidStateError } from '../errors';
import { FakeReactorClient } from './fake-reactor-client';
import type { ReactorClient } from './reactor-wasm.types';
import { checkStepFrame, holdStepMedia, readStepFrame } from './step-media';

/** The fake, as the binding the code under test takes. */
const asBinding = (client: FakeReactorClient) => client as unknown as ReactorClient;

const idr = { codec: 'h264', data: new Uint8Array([0, 0, 1, 0x65]), width: 2, height: 2 } as const;

describe('holdStepMedia', () => {
  it('holds every frame, track by track and in order', () => {
    const held = holdStepMedia({
      camera: [idr, { data: new Uint8Array(4), width: 1, height: 1 }],
      mic: [{ samples: new Int16Array([1]), sampleRate: 16000 }],
    });

    expect(held.map((frame) => [frame.track, frame.kind])).toEqual([
      ['camera', 'encoded'],
      ['camera', 'raw'],
      ['mic', 'raw'],
    ]);
  });

  it.each([
    ['an array', [idr]],
    ['a track whose frames are not an array', { camera: idr }],
    ['a frame that is not an object', { camera: [7] }],
    ['an encoded frame with no data', { camera: [{ ...idr, data: new Uint8Array(0) }] }],
  ])('refuses media that is %s', (_, media) => {
    expect(() => holdStepMedia(media as never)).toThrow(InvalidStateError);
  });
});

describe('readStepFrame and checkStepFrame', () => {
  it('asks the binding to check each frame as its push would, by length for pixels and encoded bytes', async () => {
    const client = new FakeReactorClient({}, null);
    const samples = new Int16Array(4);
    const held = holdStepMedia({
      camera: [{ ...idr, captureTimeUs: 7 }, { data: new Uint8Array(3), width: 1, height: 1, format: 'rgb' }],
      mic: [{ samples, sampleRate: 16000, channels: 2 }],
    });

    for (const frame of held) {
      checkStepFrame(asBinding(client), await readStepFrame(frame));
    }

    expect(client.checkCalls).toEqual([
      ['checkEncodedVideoFrame', 'camera', 'h264', 4, 2, 2, 7],
      ['checkVideoFrame', 'camera', 3, 1, 1, 'rgb'],
      ['checkAudioFrame', 'mic', samples, 2],
    ]);
  });

  it('throws what the binding refuses', async () => {
    const client = new FakeReactorClient({}, null);
    const [video] = holdStepMedia({ camera: [{ data: new Uint8Array(3), width: 1, height: 1 }] });
    const refusal = new InvalidStateError("pushVideoFrame('camera'): 3 bytes is not a 1x1 Rgba frame");

    client.checkImpl = () => {
      throw refusal;
    };

    const ready = await readStepFrame(video!);

    expect(() => checkStepFrame(asBinding(client), ready)).toThrow(refusal);
  });
});
