/**
 * RGB to and from YUV 4:2:0 the way the runtime and the native SDKs convert
 * a step frame: BT.601, limited range, with libyuv's `ARGBToI420` and
 * `I420ToARGB` arithmetic. A step frame's pixels travel as YUV, so both ends
 * have to agree on the matrix and the range. Left to the browser, a
 * `VideoFrame` built from RGBA is converted with whatever the engine picks
 * (BT.709, or full range on some H.264 encoders) and a decoded frame is read
 * back the same way, which shifts colours by up to ten levels; converting
 * here keeps a round trip within a level or two, as the native SDKs' is.
 */
import type { Pixels } from './push-frames';

/** The colour space of the I420 frames this module makes, for the encoder
 *  to signal. */
export const BT601_LIMITED: VideoColorSpaceInit = {
  matrix: 'smpte170m',
  primaries: 'smpte170m',
  transfer: 'smpte170m',
  fullRange: false,
};

/** One I420 picture: the Y plane, then U, then V, each tightly packed.
 *  `codedWidth` and `codedHeight` are the picture's size rounded up to even,
 *  the last column and row repeated, since 4:2:0 frames have even coded
 *  sizes; `width` and `height` are the visible size. */
export interface I420 {
  data: Uint8Array;
  width: number;
  height: number;
  codedWidth: number;
  codedHeight: number;
}

function clamp(value: number): number {
  return value < 0 ? 0 : value > 255 ? 255 : value;
}

/** `pixels` (RGB, RGBA or BGRA) as I420, BT.601 limited range. Each chroma
 *  sample is the rounded average of its 2x2 block. */
export function rgbToI420(pixels: Pixels): I420 {
  const { width, height, data } = pixels;
  const step = pixels.format === 'rgb' ? 3 : 4;
  const [ri, bi] = pixels.format === 'bgra' ? [2, 0] : [0, 2];
  const codedWidth = width + (width & 1);
  const codedHeight = height + (height & 1);
  const chromaWidth = codedWidth >> 1;
  const chromaHeight = codedHeight >> 1;
  const ySize = codedWidth * codedHeight;
  const out = new Uint8Array(ySize + 2 * chromaWidth * chromaHeight);
  const uOffset = ySize;
  const vOffset = ySize + chromaWidth * chromaHeight;
  // The source pixel at (x, y), with the coded size's padding repeating the
  // last column and row.
  const at = (x: number, y: number) => (Math.min(y, height - 1) * width + Math.min(x, width - 1)) * step;

  for (let y = 0; y < codedHeight; y++) {
    for (let x = 0; x < codedWidth; x++) {
      const i = at(x, y);
      const r = data[i + ri] as number;
      const g = data[i + 1] as number;
      const b = data[i + bi] as number;

      out[y * codedWidth + x] = ((66 * r + 129 * g + 25 * b + 0x1080) >> 8) & 0xff;
    }
  }
  for (let cy = 0; cy < chromaHeight; cy++) {
    for (let cx = 0; cx < chromaWidth; cx++) {
      let r = 0;
      let g = 0;
      let b = 0;

      for (const [dx, dy] of [
        [0, 0],
        [1, 0],
        [0, 1],
        [1, 1],
      ] as const) {
        const i = at(2 * cx + dx, 2 * cy + dy);

        r += data[i + ri] as number;
        g += data[i + 1] as number;
        b += data[i + bi] as number;
      }
      r = (r + 2) >> 2;
      g = (g + 2) >> 2;
      b = (b + 2) >> 2;
      out[uOffset + cy * chromaWidth + cx] = (112 * b - 74 * g - 38 * r + 0x8080) >> 8;
      out[vOffset + cy * chromaWidth + cx] = (112 * r - 94 * g - 18 * b + 0x8080) >> 8;
    }
  }
  return { data: out, width, height, codedWidth, codedHeight };
}

/** Where a 4:2:0 picture's planes are, as `VideoFrame.copyTo()` reports
 *  them: Y, then U and V (`I420`) or interleaved UV (`NV12`). */
export interface YuvLayout {
  format: 'I420' | 'NV12';
  width: number;
  height: number;
  planes: readonly PlaneLayout[];
}

/** A 4:2:0 picture, BT.601 limited range, as RGB `(height, width, 3)`. Each
 *  chroma sample covers its 2x2 block, as libyuv's `I420ToARGB` reads it. */
export function yuvToRgb(data: Uint8Array, { format, width, height, planes }: YuvLayout): Uint8Array {
  const [yPlane, uPlane, vPlane] = planes;

  if (yPlane === undefined || uPlane === undefined || (format === 'I420' && vPlane === undefined)) {
    throw new Error(`a ${format} picture needs ${format === 'I420' ? 3 : 2} planes, got ${planes.length}`);
  }
  const rgb = new Uint8Array(width * height * 3);

  for (let y = 0; y < height; y++) {
    const cy = y >> 1;

    for (let x = 0; x < width; x++) {
      const cx = x >> 1;
      const luma = data[yPlane.offset + y * yPlane.stride + x] as number;
      let u: number;
      let v: number;

      if (format === 'NV12') {
        u = data[uPlane.offset + cy * uPlane.stride + 2 * cx] as number;
        v = data[uPlane.offset + cy * uPlane.stride + 2 * cx + 1] as number;
      } else {
        u = data[uPlane.offset + cy * uPlane.stride + cx] as number;
        v = data[(vPlane as PlaneLayout).offset + cy * (vPlane as PlaneLayout).stride + cx] as number;
      }
      const c = 1.164 * (luma - 16);
      const d = u - 128;
      const e = v - 128;
      const j = (y * width + x) * 3;

      rgb[j] = clamp(Math.round(c + 1.596 * e));
      rgb[j + 1] = clamp(Math.round(c - 0.391 * d - 0.813 * e));
      rgb[j + 2] = clamp(Math.round(c + 2.018 * d));
    }
  }
  return rgb;
}
