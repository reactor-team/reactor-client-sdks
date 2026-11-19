import { describe, expect, it } from 'vitest';
import { rgbToI420, yuvToRgb, type I420 } from './bt601';
import type { Pixels } from './push-frames';

function solid(width: number, height: number, rgb: [number, number, number]): Pixels {
  const data = new Uint8Array(width * height * 3);

  for (let i = 0; i < data.length; i += 3) {
    data.set(rgb, i);
  }
  return { data, width, height, format: 'rgb' };
}

/** `i420` read back as its planes are packed. */
function backToRgb(i420: I420): Uint8Array {
  const chroma = (i420.codedWidth >> 1) * (i420.codedHeight >> 1);
  const ySize = i420.codedWidth * i420.codedHeight;

  return yuvToRgb(i420.data, {
    format: 'I420',
    width: i420.width,
    height: i420.height,
    planes: [
      { offset: 0, stride: i420.codedWidth },
      { offset: ySize, stride: i420.codedWidth >> 1 },
      { offset: ySize + chroma, stride: i420.codedWidth >> 1 },
    ],
  });
}

describe('BT.601 limited range, as libyuv converts', () => {
  it('maps black, white and grey to the luma libyuv gives them, with neutral chroma', () => {
    for (const [rgb, luma] of [
      [[0, 0, 0], 16],
      [[255, 255, 255], 235],
      [[128, 128, 128], 126],
    ] as const) {
      const { data } = rgbToI420(solid(2, 2, [...rgb]));

      expect(Array.from(data.subarray(0, 4))).toEqual([luma, luma, luma, luma]);
      expect(Array.from(data.subarray(4))).toEqual([128, 128]);
    }
  });

  it('round-trips a colour within a level or two', () => {
    for (const rgb of [
      [10, 20, 30],
      [200, 100, 50],
      [245, 235, 225],
      [0, 255, 0],
    ] as [number, number, number][]) {
      const back = backToRgb(rgbToI420(solid(4, 4, rgb)));

      for (let channel = 0; channel < 3; channel++) {
        expect(Math.abs((back[channel] as number) - rgb[channel]!)).toBeLessThanOrEqual(2);
      }
    }
  });

  it('reads RGBA and BGRA as the same colour', () => {
    const rgba = { data: new Uint8Array([200, 100, 50, 255]), width: 1, height: 1, format: 'rgba' } as Pixels;
    const bgra = { data: new Uint8Array([50, 100, 200, 255]), width: 1, height: 1, format: 'bgra' } as Pixels;

    expect(rgbToI420(rgba).data).toEqual(rgbToI420(bgra).data);
  });

  it('pads an odd size to an even coded size, repeating the last column and row', () => {
    const i420 = rgbToI420(solid(3, 5, [200, 100, 50]));

    expect([i420.codedWidth, i420.codedHeight]).toEqual([4, 6]);
    expect(new Set(i420.data.subarray(0, 24))).toEqual(new Set([i420.data[0]]));
    expect(backToRgb(i420)).toHaveLength(3 * 5 * 3);
  });

  it('reads NV12 the same as I420', () => {
    const i420 = rgbToI420(solid(4, 2, [200, 100, 50]));
    // Y, then U and V interleaved.
    const nv12 = new Uint8Array([...i420.data.subarray(0, 8), i420.data[8]!, i420.data[10]!, i420.data[9]!, i420.data[11]!]);
    const fromNv12 = yuvToRgb(nv12, {
      format: 'NV12',
      width: 4,
      height: 2,
      planes: [
        { offset: 0, stride: 4 },
        { offset: 8, stride: 4 },
      ],
    });

    expect(fromNv12).toEqual(backToRgb(i420));
  });

  it('honours each plane’s stride', () => {
    // A 2x2 picture whose Y rows are 8 bytes apart.
    const data = new Uint8Array([126, 126, 0, 0, 0, 0, 0, 0, 126, 126, 0, 0, 0, 0, 0, 0, 128, 128]);
    const rgb = yuvToRgb(data, {
      format: 'I420',
      width: 2,
      height: 2,
      planes: [
        { offset: 0, stride: 8 },
        { offset: 16, stride: 1 },
        { offset: 17, stride: 1 },
      ],
    });

    expect(new Set(rgb)).toEqual(new Set([128]));
  });
});
