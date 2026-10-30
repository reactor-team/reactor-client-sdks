import { describe, expect, it } from 'vitest';
import { DecodeError, InvalidStateError } from '../errors';
import { checkStepInput, checkStepTrack, holdStepMedia, readStepFrame } from './step-media';

const idr = { codec: 'h264', data: new Uint8Array([0, 0, 1, 0x65]), width: 2, height: 2 } as const;

describe('checkStepInput', () => {
  it('takes no state and no commands, or well-formed ones', () => {
    expect(() => checkStepInput({})).not.toThrow();
    expect(() => checkStepInput({ state: { prompt: 'a cat' }, commands: [{ command: 'go', data: {} }] })).not.toThrow();
  });

  it.each([
    ['a state that is not an object', { state: ['prompt'] }, DecodeError],
    ['commands that are not an array', { commands: { command: 'x' } }, InvalidStateError],
    ['a command without a name', { commands: [{ data: {} }] }, InvalidStateError],
    ['command data that is not an object', { commands: [{ command: 'x', data: [1] }] }, DecodeError],
  ])('refuses %s', (_, options, ErrorClass) => {
    expect(() => checkStepInput(options as never)).toThrow(ErrorClass);
  });
});

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

describe('readStepFrame', () => {
  it('refuses raw pixels of the wrong size, and audio that does not divide into its channels', async () => {
    const [video, audio] = holdStepMedia({
      camera: [{ data: new Uint8Array(3), width: 1, height: 1 }],
      mic: [{ samples: new Int16Array(3), sampleRate: 16000, channels: 2 }],
    });

    await expect(readStepFrame(video!)).rejects.toThrow('bytes is not');
    await expect(readStepFrame(audio!)).rejects.toThrow('channels');
  });
});

describe('checkStepTrack', () => {
  const declared = [
    { name: 'camera', kind: 'video', direction: 'sendonly' },
    { name: 'mic', kind: 'audio', direction: 'sendonly' },
    { name: 'main_video', kind: 'video', direction: 'recvonly' },
  ] as never;

  it('passes any name before the runtime declares its tracks', () => {
    expect(() =>
      checkStepTrack([], { track: 'anything', kind: 'encoded', operation: 'pushEncodedFrame', encoded: {} as never }),
    ).not.toThrow();
  });

  it.each([
    ['not declared', 'speaker', 'declares no track'],
    ['not sendonly', 'main_video', 'recvonly video'],
    ['not video', 'mic', 'only a sendonly video track'],
  ])('refuses an encoded frame for a track that is %s', (_, track, message) => {
    expect(() =>
      checkStepTrack(declared, { track, kind: 'encoded', operation: 'pushEncodedFrame', encoded: {} as never }),
    ).toThrow(message);
  });
});
