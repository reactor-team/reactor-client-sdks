/**
 * Step frames encoded with the browser's own codecs (WebCodecs), both ways:
 * the frames pushed on an input track are encoded before the binding sends
 * them, and the frames a step result brings on an output track come up from
 * the binding still encoded and are decoded here.
 *
 * Each track is one stream for the whole connection, with the same keyframe
 * rules as the native SDKs: a track's first frame on a connection is a
 * keyframe, and so is the next one after the runtime could not decode the
 * track's frames or frames encoded for it were not sent (the binding's
 * `takeInputKeyframeRequest`). An output track's decoder is fed in the order
 * results arrive, starts at a keyframe, and is dropped when it fails or when
 * the connection ends: until a keyframe starts it again, every step asks the
 * runtime for one (`request_keyframe`).
 */
import { DecodeError } from '../errors';
import { toPixels, type Pixels } from './push-frames';
import type { EncodedVideoCodec, StepFrame, StepResult } from './reactor-wasm.types';

/** The codecs a step frame can be encoded in, in the order a client prefers
 *  when the caller sets none: H.264 first, since hardware decodes it almost
 *  everywhere, then VP9 and AV1, which compress better at a higher encode
 *  cost, then VP8. */
export const STEP_CODECS: readonly EncodedVideoCodec[] = ['h264', 'vp9', 'av1', 'vp8'];

/** The WebCodecs constructors this module uses, from `globalThis` unless a
 *  test hands its own. Any of them may be missing: a browser without
 *  WebCodecs carries every step frame raw. */
export interface WebCodecs {
  VideoEncoder?: typeof VideoEncoder;
  VideoDecoder?: typeof VideoDecoder;
  VideoFrame?: typeof VideoFrame;
  EncodedVideoChunk?: typeof EncodedVideoChunk;
}

/** The codecs this browser can carry step frames in, each list in
 *  `STEP_CODECS` order: what the binding's `setLocalStepCodecs` takes. */
export interface LocalStepCodecs {
  encode: EncodedVideoCodec[];
  decode: EncodedVideoCodec[];
}

/** How long a decoder may take to hand back a frame before it is taken as
 *  lost. Frames are decoded one access unit at a time, so this is generous. */
const DECODE_TIMEOUT_MS = 10_000;

/** The WebCodecs codec string of `codec`, at a level that covers 4K: the
 *  baseline H.264 profile (no B-frames), VP9 profile 0 and AV1 main, 8-bit. */
export function codecString(codec: EncodedVideoCodec): string {
  switch (codec) {
    case 'h264':
      return 'avc1.42e033';
    case 'vp8':
      return 'vp8';
    case 'vp9':
      return 'vp09.00.51.08';
    case 'av1':
      return 'av01.0.13M.08';
  }
}

function encoderConfig(codec: EncodedVideoCodec, width: number, height: number): VideoEncoderConfig {
  return {
    codec: codecString(codec),
    width,
    height,
    // Rate-controlled, as a real-time encoder is: enough for a still image
    // to come back close, small enough to keep a step's message small.
    bitrate: Math.min(20_000_000, Math.max(300_000, width * height * 4)),
    framerate: 30,
    latencyMode: 'realtime',
    ...(codec === 'h264' ? { avc: { format: 'annexb' } } : {}),
  };
}

function decoderConfig(codec: EncodedVideoCodec): VideoDecoderConfig {
  // No `description`: H.264 arrives Annex B.
  return { codec: codecString(codec), optimizeForLatency: true };
}

/** Which of `STEP_CODECS` this browser can encode and decode, by
 *  `isConfigSupported`. Empty lists where WebCodecs is missing, or a probe
 *  throws: those step frames then stay raw. */
export async function probeStepCodecs(api: WebCodecs = globalThis): Promise<LocalStepCodecs> {
  const supported = async (probe: (codec: EncodedVideoCodec) => Promise<boolean>) => {
    const results = await Promise.all(STEP_CODECS.map((codec) => probe(codec).catch(() => false)));

    return STEP_CODECS.filter((_, i) => results[i]);
  };
  const { VideoEncoder: Encoder, VideoDecoder: Decoder, VideoFrame: Frame, EncodedVideoChunk: Chunk } = api;
  const [encode, decode] = await Promise.all([
    Encoder && Frame
      ? supported(async (codec) => (await Encoder.isConfigSupported(encoderConfig(codec, 1280, 720))).supported === true)
      : [],
    Decoder && Chunk
      ? supported(async (codec) => (await Decoder.isConfigSupported(decoderConfig(codec))).supported === true)
      : [],
  ]);

  return { encode, decode };
}

/** One encoded frame, ready for the binding's `pushEncodedVideoFrame`. */
export interface EncodedInput {
  data: Uint8Array;
  keyframe: boolean;
}

/** One input track's encoder: one stream, for one codec and one size. */
class TrackEncoder {
  private readonly encoder: VideoEncoder;
  private readonly outputs: EncodedInput[] = [];
  private failure: Error | undefined;
  private started = false;
  private timestampUs = 0;

  constructor(
    private readonly api: Required<Pick<WebCodecs, 'VideoEncoder' | 'VideoFrame'>>,
    readonly codec: EncodedVideoCodec,
    readonly width: number,
    readonly height: number,
  ) {
    this.encoder = new api.VideoEncoder({
      output: (chunk) => {
        const data = new Uint8Array(chunk.byteLength);

        chunk.copyTo(data);
        this.outputs.push({ data, keyframe: chunk.type === 'key' });
      },
      error: (error) => {
        this.failure = error instanceof Error ? error : new Error(String(error));
      },
    });
    this.encoder.configure(encoderConfig(codec, width, height));
  }

  /** Encode `pixels` as the stream's next frame, a keyframe when `keyframe`
   *  or when it is the stream's first, and hand back what the encoder made
   *  of it: every frame is flushed out before the next goes in. */
  async encode(pixels: Pixels, keyframe: boolean): Promise<EncodedInput[]> {
    const frame = new this.api.VideoFrame(rgbaOrBgra(pixels), {
      format: pixels.format === 'bgra' ? 'BGRA' : 'RGBA',
      codedWidth: pixels.width,
      codedHeight: pixels.height,
      timestamp: this.timestampUs,
    });

    this.timestampUs += 33_333;
    try {
      this.encoder.encode(frame, { keyFrame: keyframe || !this.started });
    } finally {
      frame.close();
    }
    this.started = true;
    await this.encoder.flush();
    if (this.failure !== undefined) {
      throw this.failure;
    }
    return this.outputs.splice(0);
  }

  close(): void {
    if (this.encoder.state !== 'closed') {
      this.encoder.close();
    }
  }
}

/** Raw pixels in a layout a `VideoFrame` takes: RGB gets an opaque alpha. */
function rgbaOrBgra(pixels: Pixels): Uint8Array {
  if (pixels.format !== 'rgb') {
    return pixels.data;
  }
  const rgba = new Uint8Array(pixels.width * pixels.height * 4);

  for (let i = 0, j = 0; i < pixels.data.length; i += 3, j += 4) {
    rgba[j] = pixels.data[i] as number;
    rgba[j + 1] = pixels.data[i + 1] as number;
    rgba[j + 2] = pixels.data[i + 2] as number;
    rgba[j + 3] = 255;
  }
  return rgba;
}

/** The input tracks' encoders of one connection. `reset()` drops them all,
 *  so the next connection's streams start at keyframes on fresh encoders. */
export class InputEncoders {
  private readonly encoders = new Map<string, TrackEncoder>();

  constructor(private readonly api: WebCodecs = globalThis) {}

  /** Whether this browser can encode at all. */
  get available(): boolean {
    return this.api.VideoEncoder !== undefined && this.api.VideoFrame !== undefined;
  }

  /** Encode `pixels` as `track`'s next frame in `codec`. A track whose codec
   *  or size changed starts a new stream, at a keyframe. On a failure the
   *  track's encoder is dropped, so its next frame is a keyframe, and the
   *  error is thrown for the caller to send the frame raw instead. */
  async encode(track: string, codec: EncodedVideoCodec, pixels: Pixels, keyframe: boolean): Promise<EncodedInput[]> {
    const { VideoEncoder: Encoder, VideoFrame: Frame } = this.api;

    if (!Encoder || !Frame) {
      throw new Error('this browser has no WebCodecs VideoEncoder');
    }
    let encoder = this.encoders.get(track);

    if (encoder && (encoder.codec !== codec || encoder.width !== pixels.width || encoder.height !== pixels.height)) {
      encoder.close();
      encoder = undefined;
    }
    try {
      if (!encoder) {
        encoder = new TrackEncoder({ VideoEncoder: Encoder, VideoFrame: Frame }, codec, pixels.width, pixels.height);
        this.encoders.set(track, encoder);
      }
      return await encoder.encode(pixels, keyframe);
    } catch (cause) {
      this.drop(track);
      throw cause;
    }
  }

  /** Drop `track`'s encoder: its next frame starts a new stream. */
  drop(track: string): void {
    this.encoders.get(track)?.close();
    this.encoders.delete(track);
  }

  reset(): void {
    for (const track of [...this.encoders.keys()]) {
      this.drop(track);
    }
  }
}

/** One output track's decoder, from a keyframe on. */
class TrackDecoder {
  private readonly decoder: VideoDecoder;
  /** Resolvers of the frames handed in and not yet handed back, oldest
   *  first: a decoder hands frames back in the order it took them. */
  private readonly pending: Array<{ resolve: (frame: VideoFrame) => void; reject: (cause: unknown) => void }> = [];
  failed = false;

  constructor(
    api: Required<Pick<WebCodecs, 'VideoDecoder'>>,
    readonly codec: EncodedVideoCodec,
  ) {
    this.decoder = new api.VideoDecoder({
      output: (frame) => {
        const next = this.pending.shift();

        if (next) {
          next.resolve(frame);
        } else {
          frame.close();
        }
      },
      error: (error) => this.fail(error),
    });
    this.decoder.configure(decoderConfig(codec));
  }

  /** Hand `chunk` to the decoder now, and resolve with the frame it makes of
   *  it. Called synchronously for every frame of a result, so the decoder
   *  takes them in the order results arrive. */
  decode(chunk: EncodedVideoChunk): Promise<VideoFrame> {
    const decoded = new Promise<VideoFrame>((resolve, reject) => {
      this.pending.push({ resolve, reject });
    });

    try {
      this.decoder.decode(chunk);
    } catch (cause) {
      this.fail(cause);
    }
    return withTimeout(decoded, DECODE_TIMEOUT_MS, () => {
      this.fail(new Error(`the ${this.codec} decoder handed back no frame in ${DECODE_TIMEOUT_MS} ms`));
    });
  }

  /** The decoder lost its state: every frame still in it fails. */
  fail(cause: unknown): void {
    this.failed = true;
    for (const next of this.pending.splice(0)) {
      next.reject(cause);
    }
    this.close();
  }

  close(): void {
    if (this.decoder.state !== 'closed') {
      this.decoder.close();
    }
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number, onTimeout: () => void): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      onTimeout();
      reject(new Error(`timed out after ${ms} ms`));
    }, ms);
  });

  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** The output tracks' decoders of one connection, and the tracks waiting for
 *  a keyframe. `reset()` drops both: the next connection's streams start
 *  over at keyframes anyway. */
export class OutputDecoders {
  private readonly decoders = new Map<string, TrackDecoder>();
  private readonly awaitingKeyframe = new Set<string>();

  constructor(private readonly api: WebCodecs = globalThis) {}

  /** Whether a track lost its decoder and waits for a keyframe: the next
   *  step asks the runtime for one. */
  needsKeyframe(): boolean {
    return this.awaitingKeyframe.size > 0;
  }

  /** `result` with every encoded frame decoded to RGB `(height, width, 3)`
   *  `uint8`, as a raw frame comes. Call it as the result arrives: each
   *  encoded frame goes into its track's decoder before this returns, so
   *  results that arrive one after another are decoded in that order.
   *
   *  Every frame of every track is fed to its decoder even after one fails,
   *  so the other streams keep up; the first failure rejects, with a
   *  `DecodeError`, rather than resolving a partial step. */
  decodeResult(result: StepResult): Promise<StepResult> {
    if (result.tracks.every((track) => track.frames.every((frame) => !isEncoded(frame)))) {
      return Promise.resolve(result);
    }
    let firstError: unknown;
    const tracks = result.tracks.map((track) => {
      const frames = track.frames.map((frame) =>
        this.decodeFrame(track.track, frame).catch((cause: unknown) => {
          firstError ??= cause;
          return undefined;
        }),
      );

      return Promise.all(frames).then((decoded) => ({
        ...track,
        frames: decoded.filter((frame): frame is StepFrame => frame !== undefined),
      }));
    });

    return Promise.all(tracks).then((decoded) => {
      if (firstError !== undefined) {
        throw toDecodeError(firstError);
      }
      return { ...result, tracks: decoded };
    });
  }

  /** Decode one frame of `track`; a raw one passes as it is. Synchronous up
   *  to the decoder taking the frame. */
  private decodeFrame(track: string, frame: StepFrame): Promise<StepFrame> {
    if (!isEncoded(frame)) {
      return Promise.resolve(frame);
    }
    const codec = frame.encoding;
    const { VideoDecoder: Decoder, EncodedVideoChunk: Chunk } = this.api;

    if (!Decoder || !Chunk) {
      return Promise.reject(new DecodeError(`step(): '${track}' sent ${codec} frames and this browser has no WebCodecs VideoDecoder`, { operation: 'step' }));
    }
    let decoder = this.decoders.get(track);

    if (decoder && (decoder.failed || decoder.codec !== codec)) {
      decoder.close();
      this.decoders.delete(track);
      decoder = undefined;
    }
    if (!decoder) {
      // A new stream: only a keyframe can start it.
      if (!frame.keyframe) {
        this.awaitingKeyframe.add(track);
        return Promise.reject(
          new DecodeError(
            `step(): track '${track}' has no decoder state for a ${codec} frame that is not a keyframe; waiting for a keyframe`,
            { operation: 'step' },
          ),
        );
      }
      try {
        decoder = new TrackDecoder({ VideoDecoder: Decoder }, codec);
      } catch (cause) {
        return Promise.reject(toDecodeError(cause));
      }
      this.decoders.set(track, decoder);
    }
    const live = decoder;
    const chunk = new Chunk({
      type: frame.keyframe ? 'key' : 'delta',
      timestamp: frame.captureTimeUs ?? 0,
      data: frame.data,
    });

    return live.decode(chunk).then(
      async (picture) => {
        if (frame.keyframe) {
          this.awaitingKeyframe.delete(track);
        }
        return { ...frame, ...(await rgbOf(picture)) };
      },
      (cause: unknown) => {
        this.awaitingKeyframe.add(track);
        if (this.decoders.get(track) === live) {
          this.decoders.delete(track);
        }
        throw cause;
      },
    );
  }

  reset(): void {
    for (const decoder of this.decoders.values()) {
      decoder.close();
    }
    this.decoders.clear();
    this.awaitingKeyframe.clear();
  }
}

/** Whether `frame` came up still encoded, in a codec. */
function isEncoded(frame: StepFrame): frame is StepFrame & { encoding: EncodedVideoCodec } {
  return frame.encoding !== 'raw' && frame.encoding !== 'unspecified';
}

/** `cause` as the `DecodeError` a step rejects with. */
function toDecodeError(cause: unknown): DecodeError {
  if (cause instanceof DecodeError) {
    return cause;
  }
  const message = cause instanceof Error ? cause.message : JSON.stringify(cause);

  return new DecodeError(`step(): ${message}`, { operation: 'step', cause });
}

/** A decoded picture as a step frame's array: RGB `uint8` `(height, width,
 *  3)`. Closes `picture`. */
async function rgbOf(picture: VideoFrame): Promise<Pick<StepFrame, 'dtype' | 'shape' | 'data'>> {
  let pixels: Pixels;

  try {
    pixels = await toPixels(picture);
  } finally {
    picture.close();
  }
  const { width, height } = pixels;
  const rgb = new Uint8Array(width * height * 3);
  const [r, b] = pixels.format === 'bgra' ? [2, 0] : [0, 2];

  for (let i = 0, j = 0; j < rgb.length; i += 4, j += 3) {
    rgb[j] = pixels.data[i + r] as number;
    rgb[j + 1] = pixels.data[i + 1] as number;
    rgb[j + 2] = pixels.data[i + b] as number;
  }
  return { dtype: 'uint8', shape: [height, width, 3], data: rgb };
}
