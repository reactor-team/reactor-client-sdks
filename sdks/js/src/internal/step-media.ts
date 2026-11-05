/**
 * The read half of `step()`'s `media`: each frame held when `step()` is
 * called, read into what the binding takes, and checked by the binding's own
 * checks before any of them is pushed. `Reactor` keeps only the queueing and
 * the final pushes.
 */
import { InvalidStateError } from '../errors';
import { isEncodedFrame, toEncodedBytes, type EncodedBytes } from './encoded-frames';
import { holdFrame, isAudioFrame, toPixels, type Pixels } from './push-frames';
import type { ReactorClient } from './reactor-wasm.types';
import type { EncodedFrame, PushAudioFrame, PushFrame, StepOptions } from '../types';

/** One frame of `step()`'s `media`, held from call time until it is read. */
export type HeldStepFrame = { track: string; release: () => void } & (
  | { kind: 'encoded'; operation: 'pushEncodedFrame'; encoded: EncodedBytes }
  | { kind: 'raw'; operation: 'pushFrame'; frame: PushFrame }
);

/** One frame of `step()`'s `media`, read out, as the binding takes it. */
export type ReadyStepFrame = { track: string } & (
  | { kind: 'encoded'; operation: 'pushEncodedFrame'; encoded: EncodedBytes }
  | { kind: 'video'; operation: 'pushFrame'; pixels: Pixels }
  | { kind: 'audio'; operation: 'pushFrame'; audio: PushAudioFrame }
);

/** Every frame of `media`, held as at call time, track by track and in order:
 *  a chunk copied, a VideoFrame cloned, and a malformed frame refused, now.
 *  On a refusal, whatever was already held is released before it throws. */
export function holdStepMedia(media: NonNullable<StepOptions['media']>): HeldStepFrame[] {
  if (typeof media !== 'object' || media === null || Array.isArray(media)) {
    throw new InvalidStateError('step(): media must map track names to arrays of frames', {
      operation: 'step',
    });
  }
  const held: HeldStepFrame[] = [];

  try {
    for (const [trackName, frames] of Object.entries(media)) {
      if (!Array.isArray(frames)) {
        throw new InvalidStateError(`step(): media['${trackName}'] must be an array of frames`, {
          operation: 'step',
        });
      }
      for (const frame of frames as ReadonlyArray<PushFrame | EncodedFrame>) {
        held.push(holdStepFrame(trackName, frame));
      }
    }
  } catch (cause) {
    for (const frame of held) {
      frame.release();
    }
    throw cause;
  }
  return held;
}

function holdStepFrame(track: string, frame: PushFrame | EncodedFrame): HeldStepFrame {
  if (isEncodedFrame(frame)) {
    const operation = 'pushEncodedFrame';

    return { track, kind: 'encoded', operation, encoded: toEncodedBytes(frame, operation, track), release: () => {} };
  }
  if (typeof frame !== 'object' || frame === null) {
    throw new InvalidStateError(`pushFrame('${track}'): expected a frame`, { operation: 'pushFrame' });
  }
  const held = holdFrame(frame);

  return { track, kind: 'raw', operation: 'pushFrame', frame: held.frame, release: held.release };
}

/** `frame` read out into what the binding's push takes: a VideoFrame's
 *  pixels copied out, as `pushFrame()` would. */
export async function readStepFrame(frame: HeldStepFrame): Promise<ReadyStepFrame> {
  const { track } = frame;

  if (frame.kind === 'encoded') {
    return { track, kind: 'encoded', operation: frame.operation, encoded: frame.encoded };
  }
  if (isAudioFrame(frame.frame)) {
    return { track, kind: 'audio', operation: frame.operation, audio: frame.frame };
  }
  return { track, kind: 'video', operation: frame.operation, pixels: await toPixels(frame.frame) };
}

/** Throws what the binding's push would refuse of `frame`, pushing nothing:
 *  the push's own checks, so a step refuses a bad frame anywhere in its media
 *  before the first goes into the buffer. A raw or encoded frame is checked
 *  by its length, so its bytes are not copied into wasm memory twice. */
export function checkStepFrame(client: ReactorClient, frame: ReadyStepFrame): void {
  if (frame.kind === 'encoded') {
    const { codec, data, width, height, captureTimeUs } = frame.encoded;

    client.checkEncodedVideoFrame(frame.track, codec, data.byteLength, width, height, captureTimeUs);
  } else if (frame.kind === 'video') {
    const { data, width, height, format } = frame.pixels;

    client.checkVideoFrame(frame.track, data.byteLength, width, height, format);
  } else {
    client.checkAudioFrame(frame.track, frame.audio.samples, frame.audio.channels ?? 1);
  }
}
