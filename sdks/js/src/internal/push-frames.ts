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

/** The pixels of a pushed video frame. Async because a `VideoFrame`'s live
 *  in GPU or decoder memory and copying them out is. */
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

/** Native layouts `copyTo()` can hand over as they are. */
const NATIVE_FORMATS: Partial<Record<string, PixelFormat>> = {
  RGBA: 'rgba',
  RGBX: 'rgba',
  BGRA: 'bgra',
  BGRX: 'bgra',
};

async function videoFramePixels(frame: VideoFrame): Promise<Pixels> {
  const rect = frame.visibleRect;
  const width = rect?.width ?? frame.codedWidth;
  const height = rect?.height ?? frame.codedHeight;
  const native = frame.format ? NATIVE_FORMATS[frame.format] : undefined;

  if (native) {
    const data = new Uint8Array(width * height * 4);

    await frame.copyTo(data, { layout: [{ offset: 0, stride: width * 4 }] });
    return { data, width, height, format: native };
  }
  // A YUV frame (a camera's, a decoder's): ask copyTo() to convert. Newer
  // engines can; the rest refuse the `format` option, and a canvas does it.
  try {
    const data = new Uint8Array(width * height * 4);
    const options = { format: 'RGBA', layout: [{ offset: 0, stride: width * 4 }] };

    await frame.copyTo(data, options as VideoFrameCopyToOptions);
    return { data, width, height, format: 'rgba' };
  } catch {
    return canvasPixels(frame, width, height);
  }
}

function canvasPixels(frame: VideoFrame, width: number, height: number): Pixels {
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
