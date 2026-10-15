import type { StepFrame } from './types';

/**
 * A step's video frame as an `ImageData`, ready for a canvas's
 * `putImageData()` (or `createImageBitmap()`).
 *
 * A step's video frames are `(height, width, 3)` `uint8` RGB; a canvas wants
 * RGBA, so this adds an opaque alpha. Throws on any other frame — an audio
 * frame, or a dtype or shape that is not RGB video.
 */
export function stepFrameToImageData(frame: StepFrame): ImageData {
  const [height, width, channels] = frame.shape;

  if (
    frame.dtype !== 'uint8' ||
    frame.shape.length !== 3 ||
    channels !== 3 ||
    height === undefined ||
    width === undefined ||
    frame.data.length !== width * height * 3
  ) {
    throw new Error(
      `stepFrameToImageData: expected a (height, width, 3) uint8 RGB frame, got ${frame.dtype} [${frame.shape.join(', ')}]`,
    );
  }
  const rgb = frame.data as Uint8Array;
  const rgba = new Uint8ClampedArray(width * height * 4);

  for (let src = 0, dst = 0; src < rgb.length; src += 3, dst += 4) {
    rgba[dst] = rgb[src]!;
    rgba[dst + 1] = rgb[src + 1]!;
    rgba[dst + 2] = rgb[src + 2]!;
    rgba[dst + 3] = 255;
  }
  return new ImageData(rgba, width, height);
}
