import { afterEach, describe, expect, it, vi } from 'vitest';
import { orient, toPixels, type Pixels } from './push-frames';

/** Pixel `n` as 4 bytes, each one telling the pixel apart. */
function pixel(n: number): number[] {
  return [n, n + 10, n + 20, 255];
}

/** A 3×2 image, pixels 1 2 3 on the top row and 4 5 6 below. */
function image(): Pixels {
  return {
    data: new Uint8Array([1, 2, 3, 4, 5, 6].flatMap(pixel)),
    width: 3,
    height: 2,
    format: 'rgba',
  };
}

/** The pixel numbers of `pixels`, row by row. */
function rows(pixels: Pixels): number[][] {
  const out: number[][] = [];

  for (let y = 0; y < pixels.height; y++) {
    const row: number[] = [];

    for (let x = 0; x < pixels.width; x++) {
      row.push(pixels.data[(y * pixels.width + x) * 4] as number);
    }
    out.push(row);
  }
  return out;
}

describe('orient', () => {
  it.each([
    {
      rotation: 0,
      flip: false,
      expected: [
        [1, 2, 3],
        [4, 5, 6],
      ],
    },
    {
      rotation: 90,
      flip: false,
      expected: [
        [4, 1],
        [5, 2],
        [6, 3],
      ],
    },
    {
      rotation: 180,
      flip: false,
      expected: [
        [6, 5, 4],
        [3, 2, 1],
      ],
    },
    {
      rotation: 270,
      flip: false,
      expected: [
        [3, 6],
        [2, 5],
        [1, 4],
      ],
    },
    {
      rotation: 0,
      flip: true,
      expected: [
        [3, 2, 1],
        [6, 5, 4],
      ],
    },
    // Rotation first, then the flip, as WebCodecs renders a frame.
    {
      rotation: 90,
      flip: true,
      expected: [
        [1, 4],
        [2, 5],
        [3, 6],
      ],
    },
  ])(
    'rotates $rotation° clockwise, then flips: $flip',
    ({ rotation, flip, expected }) => {
      const out = orient(image(), { rotation, flip });

      expect(rows(out)).toEqual(expected);
      expect([out.width, out.height]).toEqual([
        expected[0]?.length,
        expected.length,
      ]);
    },
  );

  it('keeps every byte of a pixel together', () => {
    const out = orient(image(), { rotation: 90, flip: false });

    expect(Array.from(out.data.subarray(0, 4))).toEqual(pixel(4));
  });

  it('reads pixels whose view does not start on a 4-byte boundary', () => {
    const padded = new Uint8Array(1 + 6 * 4);

    padded.set([1, 2, 3, 4, 5, 6].flatMap(pixel), 1);
    const out = orient(
      { data: padded.subarray(1), width: 3, height: 2, format: 'rgba' },
      { rotation: 180, flip: false },
    );

    expect(rows(out)).toEqual([
      [6, 5, 4],
      [3, 2, 1],
    ]);
  });
});

/** A `VideoFrame` with the 3×2 image as its planes, and an orientation. */
function videoFrame(
  format: string,
  rotation: number,
  flip: boolean,
  readable = true,
): VideoFrame {
  return {
    format,
    codedWidth: 3,
    codedHeight: 2,
    displayWidth: rotation % 180 ? 2 : 3,
    displayHeight: rotation % 180 ? 3 : 2,
    visibleRect: { x: 0, y: 0, width: 3, height: 2 },
    rotation,
    flip,
    copyTo: (destination: Uint8Array) => {
      if (!readable) {
        return Promise.reject(
          new TypeError('format conversion is not supported'),
        );
      }
      destination.set(image().data);
      return Promise.resolve([]);
    },
    close: () => {},
  } as unknown as VideoFrame;
}

describe('toPixels of a VideoFrame', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reads a rotated and flipped frame as it is displayed', async () => {
    const out = await toPixels(videoFrame('RGBA', 90, true));

    expect(rows(out)).toEqual([
      [1, 4],
      [2, 5],
      [3, 6],
    ]);
    expect(out.format).toBe('rgba');
  });

  it('reads an unrotated frame as copyTo() hands it over', async () => {
    const out = await toPixels(videoFrame('BGRA', 0, false));

    expect(rows(out)).toEqual([
      [1, 2, 3],
      [4, 5, 6],
    ]);
    expect(out.format).toBe('bgra');
  });

  it('draws a frame copyTo() cannot convert on a canvas of its displayed size', async () => {
    const sizes: number[][] = [];

    // The canvas renders the frame as displayed; only its size is the SDK's.
    class FakeCanvas {
      constructor(
        readonly width: number,
        readonly height: number,
      ) {
        sizes.push([width, height]);
      }

      getContext() {
        const { width, height } = this;

        return {
          drawImage: () => {},
          getImageData: () => ({
            data: new Uint8ClampedArray(width * height * 4),
            width,
            height,
          }),
        };
      }
    }
    vi.stubGlobal('OffscreenCanvas', FakeCanvas);

    const canvas = await toPixels(videoFrame('I420', 90, false, false));
    const copied = await toPixels(videoFrame('RGBA', 90, false));

    expect(sizes).toEqual([[2, 3]]);
    expect([canvas.width, canvas.height]).toEqual([
      copied.width,
      copied.height,
    ]);
  });
});
