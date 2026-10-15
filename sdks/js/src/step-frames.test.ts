import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { stepFrameToImageData } from './step-frames';
import type { StepFrame } from './types';

/** Node has no `ImageData`; this stands in with the constructor's shape. */
class FakeImageData {
  constructor(
    readonly data: Uint8ClampedArray,
    readonly width: number,
    readonly height: number,
  ) {}
}

beforeEach(() => {
  vi.stubGlobal('ImageData', FakeImageData);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function videoFrame(shape: number[], data: Uint8Array): StepFrame {
  return { encoding: 'raw', dtype: 'uint8', shape, data };
}

describe('stepFrameToImageData', () => {
  it('turns (height, width, 3) RGB into opaque RGBA of the same size', () => {
    const image = stepFrameToImageData(videoFrame([1, 2, 3], new Uint8Array([1, 2, 3, 4, 5, 6])));

    expect(image.width).toBe(2);
    expect(image.height).toBe(1);
    expect(Array.from(image.data)).toEqual([1, 2, 3, 255, 4, 5, 6, 255]);
  });

  it('refuses a frame that is not RGB video', () => {
    const audio: StepFrame = { encoding: 'raw', dtype: 'int16', shape: [1, 2], data: new Int16Array(2) };

    expect(() => stepFrameToImageData(audio)).toThrow(/uint8 RGB/);
    expect(() => stepFrameToImageData(videoFrame([1, 1, 3], new Uint8Array(2)))).toThrow(/uint8 RGB/);
  });
});
