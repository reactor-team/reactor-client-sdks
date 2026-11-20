/**
 * A fake WebCodecs for tests: just enough of `VideoEncoder`, `VideoDecoder`,
 * `VideoFrame` and `EncodedVideoChunk` to drive `step-codecs.ts` without a
 * browser. Hand `fakeWebCodecs()` to the module directly, or stub the
 * globals with it.
 *
 * The encoder makes one chunk per frame, flushed out: `[1, n]` for a
 * keyframe and `[0, n]` for a delta frame, `n` counting the frames it
 * encoded. The decoder makes a 1x1 RGBA picture of each chunk whose red is
 * the chunk's first byte, refuses a delta chunk right after `configure()`
 * as a real one does, and fails on a chunk whose first byte is `0xFF`.
 */
import type { WebCodecs } from './step-codecs';

interface Callbacks<T> {
  output: (value: T) => void;
  error: (error: Error) => void;
}

export class FakeEncodedVideoChunk {
  readonly type: 'key' | 'delta';
  readonly timestamp: number;
  readonly data: Uint8Array;

  constructor(init: { type: 'key' | 'delta'; timestamp: number; data: BufferSource }) {
    this.type = init.type;
    this.timestamp = init.timestamp;
    const { data } = init;

    this.data = (
      ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data)
    ).slice();
  }

  get byteLength(): number {
    return this.data.byteLength;
  }

  copyTo(destination: Uint8Array): void {
    destination.set(this.data);
  }
}

export class FakeVideoFrame {
  static open = 0;
  readonly format: string;
  readonly codedWidth: number;
  readonly codedHeight: number;
  readonly displayWidth: number;
  readonly visibleRect: { width: number; height: number };
  readonly timestamp: number;
  closed = false;

  constructor(
    readonly data: Uint8Array,
    init: { format: string; codedWidth: number; codedHeight: number; timestamp: number },
  ) {
    this.format = init.format;
    this.codedWidth = init.codedWidth;
    this.codedHeight = init.codedHeight;
    this.displayWidth = init.codedWidth;
    this.visibleRect = { width: init.codedWidth, height: init.codedHeight };
    this.timestamp = init.timestamp;
    FakeVideoFrame.open += 1;
  }

  copyTo(destination: Uint8Array): Promise<void> {
    destination.set(this.data.subarray(0, destination.byteLength));
    return Promise.resolve();
  }

  close(): void {
    if (!this.closed) {
      this.closed = true;
      FakeVideoFrame.open -= 1;
    }
  }
}

/** The codec strings the fakes support: every step codec unless a test
 *  narrows it. */
export const fakeSupport = { encode: new Set<string>(), decode: new Set<string>(), all: true };

function supports(direction: 'encode' | 'decode', codec: string): boolean {
  return fakeSupport.all || fakeSupport[direction].has(codec);
}

export class FakeVideoEncoder {
  static instances: FakeVideoEncoder[] = [];
  /** Set so the next `flush()` fails, as an encoder error does. */
  static failNext = false;
  state: 'unconfigured' | 'configured' | 'closed' = 'unconfigured';
  config: VideoEncoderConfig | undefined;
  /** Whether each frame given to `encode()` was asked to be a keyframe. */
  readonly keyFrames: boolean[] = [];
  private queued: FakeEncodedVideoChunk[] = [];

  static isConfigSupported(config: VideoEncoderConfig): Promise<{ supported: boolean }> {
    return Promise.resolve({ supported: supports('encode', config.codec) });
  }

  constructor(private readonly callbacks: Callbacks<FakeEncodedVideoChunk>) {
    FakeVideoEncoder.instances.push(this);
  }

  configure(config: VideoEncoderConfig): void {
    this.config = config;
    this.state = 'configured';
  }

  encode(_frame: FakeVideoFrame, options: { keyFrame?: boolean } = {}): void {
    const keyFrame = options.keyFrame === true;

    this.keyFrames.push(keyFrame);
    this.queued.push(
      new FakeEncodedVideoChunk({
        type: keyFrame ? 'key' : 'delta',
        timestamp: 0,
        data: new Uint8Array([keyFrame ? 1 : 0, this.keyFrames.length]),
      }),
    );
  }

  flush(): Promise<void> {
    if (FakeVideoEncoder.failNext) {
      FakeVideoEncoder.failNext = false;
      this.queued = [];
      const error = new Error('encoder failed');

      this.callbacks.error(error);
      return Promise.reject(error);
    }
    for (const chunk of this.queued.splice(0)) {
      this.callbacks.output(chunk);
    }
    return Promise.resolve();
  }

  close(): void {
    this.state = 'closed';
  }
}

export class FakeVideoDecoder {
  static instances: FakeVideoDecoder[] = [];
  state: 'unconfigured' | 'configured' | 'closed' = 'unconfigured';
  private keyRequired = true;

  static isConfigSupported(config: VideoDecoderConfig): Promise<{ supported: boolean }> {
    return Promise.resolve({ supported: supports('decode', config.codec) });
  }

  constructor(private readonly callbacks: Callbacks<FakeVideoFrame>) {
    FakeVideoDecoder.instances.push(this);
  }

  configure(): void {
    this.state = 'configured';
    this.keyRequired = true;
  }

  decode(chunk: FakeEncodedVideoChunk): void {
    if (this.state !== 'configured') {
      throw new Error('decoder is not configured');
    }
    if (this.keyRequired && chunk.type !== 'key') {
      throw new Error('a key frame is required');
    }
    this.keyRequired = false;
    const red = chunk.data[0] as number;

    queueMicrotask(() => {
      if (this.state === 'closed') {
        return;
      }
      if (red === 0xff) {
        this.state = 'closed';
        this.callbacks.error(new Error('corrupt frame'));
        return;
      }
      if (red === 0xfe) {
        // A frame that is not shown: decoded, no picture.
        return;
      }
      this.callbacks.output(
        new FakeVideoFrame(new Uint8Array([red, 2, 3, 255]), { format: 'RGBA', codedWidth: 1, codedHeight: 1, timestamp: chunk.timestamp }),
      );
    });
  }

  close(): void {
    this.state = 'closed';
  }
}

/** The fakes, as `step-codecs.ts` takes them, with every count reset. */
export function fakeWebCodecs(): WebCodecs {
  FakeVideoEncoder.instances = [];
  FakeVideoEncoder.failNext = false;
  FakeVideoDecoder.instances = [];
  FakeVideoFrame.open = 0;
  fakeSupport.all = true;
  fakeSupport.encode.clear();
  fakeSupport.decode.clear();
  return {
    VideoEncoder: FakeVideoEncoder as unknown as typeof VideoEncoder,
    VideoDecoder: FakeVideoDecoder as unknown as typeof VideoDecoder,
    VideoFrame: FakeVideoFrame as unknown as typeof VideoFrame,
    EncodedVideoChunk: FakeEncodedVideoChunk as unknown as typeof EncodedVideoChunk,
  };
}
