/**
 * The read-and-check half of `step()`'s `media`, and of its `state` and
 * `commands`: everything a step refuses before any of its frames reach the
 * binding. `Reactor` keeps only the queueing and the final pushes.
 */
import { DecodeError, InvalidStateError } from '../errors';
import { isEncodedFrame, toEncodedBytes, type EncodedBytes } from './encoded-frames';
import { audioFault, holdFrame, isAudioFrame, pixelsFault, toPixels, type Pixels } from './push-frames';
import type { ReactorClient } from './reactor-wasm.types';
import type { EncodedFrame, PushAudioFrame, PushFrame, StepOptions } from '../types';

function isJsonObject(value: unknown): boolean {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** One frame of `step()`'s `media`, held from call time until it is read. */
export type HeldStepFrame = { track: string; release: () => void } & (
  | { kind: 'encoded'; operation: 'pushEncodedFrame'; encoded: EncodedBytes }
  | { kind: 'raw'; operation: 'pushFrame'; frame: PushFrame }
);

/** One frame of `step()`'s `media`, read and checked, as the binding takes it. */
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

/** `frame` read out and checked the way the binding would check it, so a
 *  frame it would refuse is refused before any of the step's are pushed. */
export async function readStepFrame(frame: HeldStepFrame): Promise<ReadyStepFrame> {
  const { track, operation } = frame;
  const refuse = (why: string) =>
    new InvalidStateError(`${operation}('${track}'): ${why}`, { operation });

  if (frame.kind === 'encoded') {
    return { track, kind: 'encoded', operation: frame.operation, encoded: frame.encoded };
  }
  if (isAudioFrame(frame.frame)) {
    const fault = audioFault(frame.frame);

    if (fault) {
      throw refuse(fault);
    }
    return { track, kind: 'audio', operation: frame.operation, audio: frame.frame };
  }
  const pixels = await toPixels(frame.frame);
  const fault = pixelsFault(pixels);

  if (fault) {
    throw refuse(fault);
  }
  return { track, kind: 'video', operation: frame.operation, pixels };
}

/** Refuses a `step()` media frame for a track the model declares as other
 *  than a sendonly track of its kind, as the binding would on its push.
 *  Before the runtime declares its tracks, any name passes, as there. */
export function checkStepTrack(declared: ReturnType<ReactorClient['tracks']>, frame: ReadyStepFrame): void {
  if (declared.length === 0) {
    return;
  }
  const { track, operation } = frame;
  const kind = frame.kind === 'audio' ? 'audio' : 'video';
  const found = declared.find((candidate) => candidate.name === track);

  if (found === undefined) {
    throw new InvalidStateError(
      `${operation}('${track}'): the model declares no track named '${track}'`,
      { operation },
    );
  }
  if (found.kind !== kind || found.direction !== 'sendonly') {
    const wanted =
      frame.kind === 'encoded'
        ? '; only a sendonly video track takes encoded frames.'
        : `, not a sendonly ${kind} one`;

    throw new InvalidStateError(
      `${operation}('${track}'): '${track}' is a ${found.direction} ${found.kind} track${wanted}`,
      { operation },
    );
  }
}

/** Refuses a malformed `state` or `commands` the way the binding would, but
 *  before `step()` pushes its `media`: a step that never goes out leaves the
 *  frames buffered as the caller left them. */
export function checkStepInput({ state, commands }: StepOptions): void {
  if (state != null && !isJsonObject(state)) {
    throw new DecodeError('step state must be a JSON object', { operation: 'step' });
  }
  if (commands === undefined) {
    return;
  }
  if (!Array.isArray(commands)) {
    throw new InvalidStateError('step commands: expected an array of { command, data? }', {
      operation: 'step',
    });
  }
  (commands as unknown[]).forEach((entry, index) => {
    if (!isJsonObject(entry) || typeof (entry as { command?: unknown }).command !== 'string') {
      throw new InvalidStateError(`step commands[${index}]: expected { command: string, data? }`, {
        operation: 'step',
      });
    }
    const { command, data } = entry as { command: string; data?: unknown };

    if (data != null && !isJsonObject(data)) {
      throw new DecodeError(`data of step command '${command}' must be a JSON object`, {
        operation: 'step',
      });
    }
  });
}
