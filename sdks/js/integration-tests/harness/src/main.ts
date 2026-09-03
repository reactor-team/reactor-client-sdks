import {
  DEFAULT_PLAYLIST_POLL_SLACK_MS,
  Reactor,
  downloadClipAsFile,
  fetchPlaylist,
  isFileRef,
  type Clip,
  type ReactorEventName,
  type ReactorOptions,
} from '@reactor-team/js-sdk';
import { REACTOR_API_URL, REACTOR_LOCAL, REACTOR_MODEL_NAME } from './config';
import { makeAudioTrack, makeTestImageFile, makeVideoTrack, samplePixel } from './fixtures';

// Not a demo — see index.html. This is the one thing every integration test
// talks through: a small, evaluable API that wraps the real SDK, so
// Playwright exercises `@reactor-team/js-sdk`'s actual public surface rather
// than a hand-rolled stand-in for it.

export async function fetchToken(): Promise<string> {
  if (REACTOR_LOCAL) {return '';}
  const r = await fetch('/api/token');

  if (!r.ok) {throw new Error(`token fetch failed: ${r.status}`);}
  const { jwt } = (await r.json()) as { jwt: string };

  return jwt;
}

interface HarnessEvent {
  t: number;
  type: ReactorEventName;
  detail: unknown;
}

const EVENT_NAMES: ReactorEventName[] = [
  'statusChanged',
  'sessionIdChanged',
  'error',
  'message',
  'runtimeMessage',
  'schemaReceived',
  'capabilitiesReceived',
  'trackReceived',
  'statsUpdate',
];

const instances = new Map<string, Reactor>();
const events: Record<string, HarnessEvent[]> = {};
const receivedTracks: Record<string, Record<string, MediaStreamTrack>> = {};
const audioAnalysers: Record<string, Record<string, AnalyserNode>> = {};

function record(name: string, type: ReactorEventName, detail: unknown): void {
  events[name]!.push({ t: performance.now(), type, detail });
}

function create(name: string, extraOptions: Partial<ReactorOptions> = {}): void {
  if (instances.has(name)) {throw new Error(`harness instance "${name}" already exists`);}

  const reactor = new Reactor({
    modelName: REACTOR_MODEL_NAME,
    apiUrl: REACTOR_API_URL,
    local: REACTOR_LOCAL,
    ...extraOptions,
  });

  events[name] = [];
  receivedTracks[name] = {};
  audioAnalysers[name] = {};

  for (const type of EVENT_NAMES) {
    reactor.on(type, ((...args: unknown[]) => {
      if (type === 'trackReceived') {
        const [trackName, track] = args as [string, MediaStreamTrack];

        receivedTracks[name]![trackName] = track;
        if (track.kind === 'audio') {
          // getStats()'s inbound-rtp audioLevel/totalSamplesReceived stay at
          // 0 until something actually plays the track out — confirmed
          // empirically, the same headless-Chromium rendering gap
          // makeAudioTrack's own silent tap works around on the sending
          // side (see fixtures.ts). volume 0, not muted: muting can skip
          // decode/playout entirely in some browsers, which would reproduce
          // the same gap this exists to avoid.
          const sink = document.createElement('audio');

          sink.srcObject = new MediaStream([track]);
          sink.volume = 0;
          sink.autoplay = true;
          document.body.appendChild(sink);
          void sink.play().catch(() => {});

          // getStats()'s inbound-rtp audioLevel/totalAudioEnergy stayed at 0
          // even with the <audio> sink above proving real playout
          // (jitterBufferEmittedCount/totalSamplesReceived > 0) — confirmed
          // empirically those two specific fields just don't populate here,
          // for reasons unrelated to whether real audio arrived. Measuring
          // the decoded PCM directly with an AnalyserNode sidesteps that
          // entirely. The analyser must sit *in* the path to destination,
          // not as a dead-end side branch — Web Audio only pulls (processes)
          // nodes that are part of an active destination-reaching chain;
          // an analyser with nowhere downstream reads all-zero forever,
          // the same gap the sending side's silent tap works around.
          const audioCtx = new AudioContext();
          const source = audioCtx.createMediaStreamSource(new MediaStream([track]));
          const analyser = audioCtx.createAnalyser();
          const silentTap = audioCtx.createGain();

          silentTap.gain.value = 0;
          source.connect(analyser);
          analyser.connect(silentTap);
          silentTap.connect(audioCtx.destination);
          audioAnalysers[name]![trackName] = analyser;
        }
        record(name, type, { name: trackName });
        return;
      }
      if (type === 'error') {
        const [err] = args as [Error & { code?: string; recoverable?: boolean }];

        record(name, type, { message: err.message, code: err.code, recoverable: err.recoverable });
        return;
      }
      record(name, type, args.length <= 1 ? args[0] : args);
    }) as never);
  }

  instances.set(name, reactor);
}

function get(name: string): Reactor {
  const reactor = instances.get(name);

  if (!reactor) {throw new Error(`no harness instance named "${name}" — call create() first`);}
  return reactor;
}

async function destroy(name: string): Promise<void> {
  const reactor = instances.get(name);

  if (!reactor) {return;}
  if (reactor.getStatus() !== 'disconnected') {
    await reactor.disconnect();
  }
  reactor[Symbol.dispose]();
  instances.delete(name);
  delete events[name];
  delete receivedTracks[name];
}

/** Destroys every instance a test created, regardless of name — the
 *  afterEach cleanup every spec runs so a failed or forgetful test never
 *  leaves a production session connected past the test that opened it. */
async function destroyAll(): Promise<void> {
  // Sequential, in reverse creation order — not Promise.all. A test like
  // the multi-connection one creates a "creator" the "joiner" then depends
  // on, and disconnecting the creator first ends the shared session before
  // the joiner (only watching it) gets a chance to leave cleanly, orphaning
  // it server-side. `instances` is a Map, so insertion order is creation
  // order; last-created-first-destroyed undoes dependencies in the order
  // they were built, the same way any stack-shaped teardown does.
  for (const name of [...instances.keys()].reverse()) {
    await destroy(name);
  }
}

async function samplePixelFor(name: string, trackName: string): Promise<{ r: number; g: number; b: number }> {
  const track = receivedTracks[name]?.[trackName];

  if (!track) {throw new Error(`instance "${name}" never received a track named "${trackName}"`);}
  return samplePixel(track);
}

/** The RMS level (0-1ish) of the decoded audio actually arriving on
 *  `trackName` — the audio counterpart of `samplePixelFor`.
 *
 * Reads an `AnalyserNode` wired into the track's own destination-reaching
 * chain (see the `trackReceived` handler above), not `getStats()`'s
 * inbound-rtp `audioLevel`/`totalAudioEnergy`: confirmed empirically those
 * two fields read 0 in this environment even once real playout is proven
 * (`jitterBufferEmittedCount`/`totalSamplesReceived` > 0) — for reasons
 * unrelated to whether real audio arrived. Measuring the decoded PCM
 * directly sidesteps whatever that gap is. */
async function sampleAudioLevelFor(name: string, trackName: string): Promise<number> {
  const analyser = audioAnalysers[name]?.[trackName];

  if (!analyser) {throw new Error(`instance "${name}" never received an audio track named "${trackName}"`);}
  const data = new Float32Array(analyser.fftSize);

  analyser.getFloatTimeDomainData(data);
  let sumSquares = 0;

  for (const sample of data) {sumSquares += sample * sample;}
  return Math.sqrt(sumSquares / data.length);
}

// Takes the *same* jwt that created the session, not a freshly minted one —
// reading a session back requires the token that created it (see the
// examples' own fetch-token.ts), and a fresh mint of the same scope is a
// different token that 403s on the manifest.
async function downloadClip(clip: Clip, filename: string, jwt: string): Promise<{ byteLength: number }> {
  await fetchPlaylist(clip.playlistUrl, {
    predictedReadyAtMs: clip.predictedReadyAtMs,
    slackMs: DEFAULT_PLAYLIST_POLL_SLACK_MS,
    jwt,
  });
  const blob = await downloadClipAsFile(clip, filename, { jwt });

  return { byteLength: blob.size };
}

declare global {
  interface Window {
    __harness: {
      config: { local: boolean; apiUrl: string; modelName: string };
      create: typeof create;
      get: typeof get;
      destroy: typeof destroy;
      destroyAll: typeof destroyAll;
      fetchToken: typeof fetchToken;
      makeVideoTrack: typeof makeVideoTrack;
      makeAudioTrack: typeof makeAudioTrack;
      makeTestImageFile: typeof makeTestImageFile;
      samplePixelFor: typeof samplePixelFor;
      sampleAudioLevelFor: typeof sampleAudioLevelFor;
      downloadClip: typeof downloadClip;
      isFileRef: typeof isFileRef;
      events: typeof events;
    };
  }
}

window.__harness = {
  config: { local: REACTOR_LOCAL, apiUrl: REACTOR_API_URL, modelName: REACTOR_MODEL_NAME },
  create,
  get,
  destroy,
  destroyAll,
  fetchToken,
  makeVideoTrack,
  makeAudioTrack,
  makeTestImageFile,
  samplePixelFor,
  sampleAudioLevelFor,
  downloadClip,
  isFileRef,
  events,
};
