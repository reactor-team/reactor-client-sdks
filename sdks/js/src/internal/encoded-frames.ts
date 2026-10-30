/**
 * Turns an `EncodedFrame` into what the wasm binding's `pushEncodedVideoFrame`
 * takes, refusing what could never be a frame before it is queued. Whether
 * the bytes really decode is the runtime's to say, when the step runs.
 */
import { InvalidStateError } from '../errors';
import type { EncodedVideoCodec } from './reactor-wasm.types';
import type { EncodedFrame, PushFrame } from '../types';

/** The codecs an encoded step frame can carry. */
const CODECS: ReadonlySet<string> = new Set<EncodedVideoCodec>(['h264']);

/** An encoded frame's bytes and extras, ready for the binding, read once when
 *  the call is made: a caller changing the frame afterwards changes nothing. */
export interface EncodedBytes {
  codec: EncodedVideoCodec;
  data: Uint8Array;
  width: number;
  height: number;
  metadata: Uint8Array | null;
  captureTimeUs: number | null;
}

/** Whether a `step()` media entry is an `EncodedFrame` rather than a frame
 *  `pushFrame()` takes: only an encoded frame names a `codec`. */
export function isEncodedFrame(frame: PushFrame | EncodedFrame): frame is EncodedFrame {
  return typeof frame === 'object' && frame !== null && 'codec' in frame;
}

function isChunk(data: EncodedFrame['data']): data is EncodedVideoChunk {
  return (
    !(data instanceof Uint8Array) &&
    !(data instanceof ArrayBuffer) &&
    typeof data.copyTo === 'function'
  );
}

/** `frame`'s bytes as a `Uint8Array`: a view over an array or buffer, a copy
 *  of an `EncodedVideoChunk`'s. Throws an `InvalidStateError` naming
 *  `operation('trackName')` for a frame that could not be pushed. */
export function toEncodedBytes(frame: EncodedFrame, operation: string, trackName: string): EncodedBytes {
  const refuse = (why: string) =>
    new InvalidStateError(`${operation}('${trackName}'): ${why}`, { operation });

  if (typeof frame !== 'object' || frame === null) {
    throw refuse('expected an EncodedFrame, { codec, data, width, height }');
  }
  const { codec, data, width, height, metadata, captureTimeUs } = frame;

  if (!CODECS.has(codec)) {
    throw refuse(`unknown codec ${JSON.stringify(codec)}; expected "h264"`);
  }
  let bytes: Uint8Array;

  if (data instanceof Uint8Array) {
    bytes = data;
  } else if (data instanceof ArrayBuffer) {
    bytes = new Uint8Array(data);
  } else if (data && isChunk(data)) {
    bytes = new Uint8Array(data.byteLength);
    data.copyTo(bytes);
  } else {
    throw refuse('data must be a Uint8Array, an ArrayBuffer or an EncodedVideoChunk');
  }
  if (bytes.byteLength === 0) {
    throw refuse('an encoded frame needs data');
  }
  if (!isDimension(width) || !isDimension(height)) {
    throw refuse(`width and height are the decoded frame's, got ${String(width)}x${String(height)}`);
  }
  if (metadata != null && !(metadata instanceof Uint8Array)) {
    throw refuse('metadata must be a Uint8Array');
  }
  if (captureTimeUs != null && !Number.isFinite(captureTimeUs)) {
    throw refuse('captureTimeUs must be a finite number');
  }
  return { codec, data: bytes, width, height, metadata: metadata ?? null, captureTimeUs: captureTimeUs ?? null };
}

function isDimension(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 && value <= 0xffff_ffff;
}
