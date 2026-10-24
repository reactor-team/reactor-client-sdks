/**
 * Turns what `Reactor.pushFrame()` accepts into what the wasm binding's
 * `pushVideoFrame`/`pushAudioFrame` take: bytes, dimensions, and the layout
 * the bytes are in. The binding converts that layout to what `reactor-core`
 * buffers — this only gets the pixels out of whichever browser object holds
 * them, without converting them again on the JS side.
 */
import type { PixelFormat } from './reactor-wasm.types';
import type { PushAudioFrame, PushFrame, PushVideoFrame, RawVideoFrame } from '../types';

/** Pixels in a layout the binding accepts. */
export interface Pixels {
  data: Uint8Array;
  width: number;
  height: number;
  format: PixelFormat;
}

export function isAudioFrame(frame: PushFrame): frame is PushAudioFrame {
  return 'samples' in frame;
}

function isVideoFrame(frame: PushVideoFrame): frame is VideoFrame {
  return typeof (frame as VideoFrame).copyTo === 'function' && 'displayWidth' in frame;
}

/** A pushed frame the queue can read later. A `VideoFrame` is cloned at call
 *  time, so the caller may close theirs as soon as `pushFrame()` returns;
 *  `release` closes the clone once its pixels are out. */
export function holdFrame(frame: PushFrame): { frame: PushFrame; release: () => void } {
  if (!isAudioFrame(frame) && isVideoFrame(frame)) {
    const clone = frame.clone();

    return { frame: clone, release: () => clone.close() };
  }
  return { frame, release: () => {} };
}

/** The pixels of a pushed video frame. Async because a `VideoFrame`'s live
 *  in GPU or decoder memory and copying them out is. A `VideoFrame` comes out
 *  as it is displayed: its visible rect, rotated by its `rotation`, then
 *  flipped by its `flip`, whichever path reads it. */
export async function toPixels(frame: PushVideoFrame): Promise<Pixels> {
  if (isVideoFrame(frame)) {
    return videoFramePixels(frame);
  }
  return rawPixels(frame);
}

function rawPixels(frame: RawVideoFrame | ImageData): Pixels {
  const { data, width, height } = frame;
  // A view, not a copy: `ImageData.data` is a Uint8ClampedArray, which the
  // binding's `Uint8Array` parameter would otherwise refuse at type level.
  const bytes =
    data instanceof Uint8Array ? data : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);

  return { data: bytes, width, height, format: ('format' in frame && frame.format) || 'rgba' };
}

/** Bytes per pixel of each layout the binding accepts. */
const BYTES_PER_PIXEL: Record<PixelFormat, number> = { rgba: 4, rgb: 3, bgra: 4 };

/** Why the binding would refuse `pixels`, or `undefined` if it would take
 *  them: what `step()` checks of every frame of its `media` before pushing
 *  any. */
export function pixelsFault({ data, width, height, format }: Pixels): string | undefined {
  const bytesPerPixel = BYTES_PER_PIXEL[format] as number | undefined;

  if (bytesPerPixel === undefined) {
    return `unknown pixel format ${JSON.stringify(format)}; expected "rgba", "rgb" or "bgra"`;
  }
  if (!isDimension(width) || !isDimension(height)) {
    return `a ${String(width)}x${String(height)} frame has no pixels`;
  }
  if (!(data instanceof Uint8Array) || data.byteLength !== width * height * bytesPerPixel) {
    return `${String(data?.byteLength)} bytes is not a ${width}x${height} ${format} frame`;
  }
  return undefined;
}

/** Why the binding would refuse an audio frame, or `undefined` if it would
 *  take it. */
export function audioFault({ samples, channels = 1 }: PushAudioFrame): string | undefined {
  if (!(samples instanceof Int16Array)) {
    return 'samples must be an Int16Array';
  }
  if (!isDimension(channels) || samples.length % channels !== 0) {
    return `${samples.length} samples do not divide into ${String(channels)} channels`;
  }
  return undefined;
}

function isDimension(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 && value <= 0xffff_ffff;
}

/** Native layouts `copyTo()` can hand over as they are. */
const NATIVE_FORMATS: Partial<Record<string, PixelFormat>> = {
  RGBA: 'rgba',
  RGBX: 'rgba',
  BGRA: 'bgra',
  BGRX: 'bgra',
};

/** A `VideoFrame`'s orientation. Both fields are recent WebCodecs additions,
 *  so an engine without them reports neither. */
interface Orientation {
  rotation: number;
  flip: boolean;
}

function orientationOf(frame: VideoFrame): Orientation {
  const { rotation, flip } = frame as VideoFrame & Partial<Orientation>;

  return { rotation: (((rotation ?? 0) % 360) + 360) % 360, flip: flip ?? false };
}

async function videoFramePixels(frame: VideoFrame): Promise<Pixels> {
  const rect = frame.visibleRect;
  const width = rect?.width ?? frame.codedWidth;
  const height = rect?.height ?? frame.codedHeight;
  const native = frame.format ? NATIVE_FORMATS[frame.format] : undefined;
  const orientation = orientationOf(frame);

  if (native) {
    const data = new Uint8Array(width * height * 4);

    await frame.copyTo(data, { layout: [{ offset: 0, stride: width * 4 }] });
    return orient({ data, width, height, format: native }, orientation);
  }
  // A YUV frame (a camera's, a decoder's): ask copyTo() to convert. Newer
  // engines can; the rest refuse the `format` option, and a canvas does it.
  try {
    const data = new Uint8Array(width * height * 4);
    const options = { format: 'RGBA', layout: [{ offset: 0, stride: width * 4 }] };

    await frame.copyTo(data, options as VideoFrameCopyToOptions);
    return orient({ data, width, height, format: 'rgba' }, orientation);
  } catch {
    return canvasPixels(frame, width, height, orientation);
  }
}

/** Turn pixels `copyTo()` read in stored orientation into the displayed one:
 *  rotate clockwise by `rotation`, then flip horizontally if `flip`, as
 *  WebCodecs renders a frame. A rotation that is not a multiple of 90 is not
 *  a valid `VideoFrame` rotation and leaves the pixels as they are. */
export function orient(pixels: Pixels, { rotation, flip }: Orientation): Pixels {
  if (rotation % 90 !== 0 || (rotation === 0 && !flip)) {
    return pixels;
  }
  const { width: w, height: h } = pixels;
  const turned = rotation === 90 || rotation === 270;
  const outWidth = turned ? h : w;
  const outHeight = turned ? w : h;
  // One 32-bit word per pixel: the layout is 4 bytes a pixel, whatever order.
  // A view needs its offset 4-byte aligned; copy the bytes when it is not.
  const bytes = pixels.data.byteOffset % 4 === 0 ? pixels.data : pixels.data.slice();
  const source = new Uint32Array(bytes.buffer, bytes.byteOffset, w * h);
  const data = new Uint8Array(outWidth * outHeight * 4);
  const target = new Uint32Array(data.buffer);

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let tx: number;
      let ty: number;

      if (rotation === 90) {
        [tx, ty] = [h - 1 - y, x];
      } else if (rotation === 180) {
        [tx, ty] = [w - 1 - x, h - 1 - y];
      } else if (rotation === 270) {
        [tx, ty] = [y, w - 1 - x];
      } else {
        [tx, ty] = [x, y];
      }
      if (flip) {
        tx = outWidth - 1 - tx;
      }
      target[ty * outWidth + tx] = source[y * w + x] as number;
    }
  }
  return { data, width: outWidth, height: outHeight, format: pixels.format };
}

/** Read a frame through a 2D canvas, which renders it as displayed. The
 *  canvas takes the visible size, turned for a rotation of 90 or 270, so the
 *  pixels match what `orient()` makes of a `copyTo()` read. */
function canvasPixels(
  frame: VideoFrame,
  visibleWidth: number,
  visibleHeight: number,
  { rotation }: Orientation,
): Pixels {
  const turned = rotation === 90 || rotation === 270;
  const width = turned ? visibleHeight : visibleWidth;
  const height = turned ? visibleWidth : visibleHeight;

  if (typeof OffscreenCanvas === 'undefined') {
    throw new Error(
      `pushFrame: cannot read a ${frame.format ?? 'formatless'} VideoFrame here — ` +
        'push its pixels as an ImageData or a Uint8Array instead.',
    );
  }
  const context = new OffscreenCanvas(width, height).getContext('2d');

  if (!context) {
    throw new Error('pushFrame: no 2D canvas context to read the VideoFrame through.');
  }
  context.drawImage(frame, 0, 0, width, height);
  return rawPixels(context.getImageData(0, 0, width, height));
}
