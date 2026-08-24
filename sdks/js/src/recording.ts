/**
 * Browser-side primitives for turning a `Clip` (from `requestClip()` /
 * `requestRecording()`) into playable or downloadable media.
 *
 * `Clip.playlistUrl` names an HLS media playlist — `#EXT-X-VERSION:7`
 * fragmented MP4, an `#EXT-X-MAP` init segment followed by `.m4s` media
 * fragments — that the caller fetches and assembles; Reactor itself doesn't
 * host clips. This module owns fetching and parsing that playlist
 * (`fetchPlaylist` / `parsePlaylist`), wrapping it for a `<video>` element or
 * `hls.js` (`createPlayableManifestUrl`), and assembling the referenced
 * chunks into a flat, downloadable MP4 (`downloadClipAsFile`).
 */

import type * as MP4BoxTypes from 'mp4box';
import type { Clip } from './types';

/**
 * Error thrown when a clip's playlist or chunks can't be fetched, parsed, or
 * assembled into a file. Scoped to this module — a failure in
 * `requestClip()` / `requestRecording()` themselves surfaces as a
 * `ReactorError`, since those go through `reactor-core`'s own error channel.
 *
 * `code` is a stable, machine-readable identifier; `reason` is the raw
 * string behind it (an HTTP status, a parse complaint, …).
 */
export class RecordingError extends Error {
  constructor(
    public readonly code:
      | 'CLIP_GONE'
      | 'CLIP_NOT_READY'
      | 'PLAYLIST_FETCH_FAILED'
      | 'CHUNK_FETCH_FAILED'
      | 'INVALID_PLAYLIST'
      | 'DOWNLOAD_UNSUPPORTED',
    public readonly reason: string,
  ) {
    super(`${code}: ${reason}`);
    this.name = 'RecordingError';
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// HLS playlist fetching + parsing
// ─────────────────────────────────────────────────────────────────────────────

/** Segments referenced by an HLS manifest, in playback order. */
interface ParsedPlaylist {
  initUrl: string;
  segmentUrls: string[];
}

/**
 * Suggested grace period for callers that opt into a bounded wait by passing
 * it as `FetchPlaylistOptions.slackMs`. Not a default — `fetchPlaylist()`
 * polls indefinitely unless a bound is supplied.
 */
export const DEFAULT_PLAYLIST_POLL_SLACK_MS = 15_000;

export interface FetchPlaylistOptions {
  /**
   * Unix epoch (ms) when the runtime predicts the boundary chunk will be
   * servable — pass `clip.predictedReadyAtMs`. On its own this doesn't stop
   * polling; it only anchors the optional `slackMs` deadline.
   */
  predictedReadyAtMs?: number;
  /**
   * Opt-in grace period that turns polling into a bounded wait: a stuck
   * `202` produces `CLIP_NOT_READY` once `max(predictedReadyAtMs, pollStart)
   * + slackMs` passes. Omit (the default) to poll indefinitely until the
   * manifest is ready or the caller aborts via `signal`.
   */
  slackMs?: number;
  /** Hard cap on the per-poll wait. `Retry-After` is honored but clamped. Default 2000 ms. */
  maxRetryDelayMs?: number;
  /** Floor on the per-poll wait so a cheap network doesn't hot-loop. Default 200 ms. */
  minRetryDelayMs?: number;
  /**
   * Opt-in cap on the number of `202` responses tolerated before
   * `CLIP_NOT_READY`. Omit (the default) to poll indefinitely.
   */
  maxRetries?: number;
  /**
   * Aborts in-flight fetches and the inter-poll sleep — the primary way to
   * end an unbounded wait (a timeout, a user cancel, a component unmount).
   */
  signal?: AbortSignal;
  /**
   * Coordinator JWT, attached as `Authorization: Bearer <jwt>` on the
   * manifest GET. Omit in local mode (HttpRuntime).
   */
  jwt?: string;
}

/**
 * Fetch `playlistUrl`, polling on `202 Accepted` — returned while the
 * boundary chunk is still uploading.
 *
 * - `200` → the manifest body.
 * - `410` / `404` → `CLIP_GONE`.
 * - other → `PLAYLIST_FETCH_FAILED` (no retry).
 * - `202` past an opt-in `slackMs` deadline or `maxRetries` cap → `CLIP_NOT_READY`.
 * - aborted `signal` → rejects with the fetch/sleep `AbortError`.
 */
export async function fetchPlaylist(
  playlistUrl: string,
  options: FetchPlaylistOptions = {},
): Promise<string> {
  const minDelay = Math.max(0, options.minRetryDelayMs ?? 200);
  const maxDelay = Math.max(minDelay, options.maxRetryDelayMs ?? 2_000);

  const hasDeadline = typeof options.slackMs === 'number' && Number.isFinite(options.slackMs);
  // Anchored at max(predictedReadyAtMs, now) so a late start still gets the
  // full grace window rather than a deadline already in the past.
  const startedPollingAt = Date.now();
  const deadlineMs = hasDeadline
    ? Math.max(options.predictedReadyAtMs ?? startedPollingAt, startedPollingAt) +
      (options.slackMs as number)
    : undefined;
  const maxRetries = typeof options.maxRetries === 'number' ? options.maxRetries : undefined;

  const init: RequestInit = {};

  if (options.signal) {
    init.signal = options.signal;
  }
  if (options.jwt) {
    init.headers = { Authorization: `Bearer ${options.jwt}` };
  }

  let attempt = 0;

  while (true) {
    let response: Response;

    try {
      response = await fetch(playlistUrl, init);
    } catch (error) {
      if (isAbortError(error)) {
        throw error;
      }
      throw new RecordingError(
        'PLAYLIST_FETCH_FAILED',
        `Network error fetching playlist: ${(error as Error).message}`,
      );
    }

    // 202 is in the 2xx range, so it has to be checked before response.ok.
    if (response.status === 202) {
      if (deadlineMs !== undefined && Date.now() >= deadlineMs) {
        throw new RecordingError(
          'CLIP_NOT_READY',
          `Boundary chunk still pending after ${options.slackMs}ms grace (predicted ready ${new Date(
            options.predictedReadyAtMs ?? startedPollingAt,
          ).toISOString()}). Runtime may have crashed mid-clip.`,
        );
      }
      if (maxRetries !== undefined && attempt >= maxRetries) {
        throw new RecordingError(
          'CLIP_NOT_READY',
          `Manifest still pending after ${attempt + 1} attempts (last status 202)`,
        );
      }

      const headerDelay = parseRetryAfter(response.headers.get('Retry-After'), minDelay);
      const delay = Math.min(maxDelay, Math.max(minDelay, headerDelay));
      const clampedDelay =
        deadlineMs !== undefined ? Math.min(delay, Math.max(0, deadlineMs - Date.now())) : delay;

      await sleep(clampedDelay, options.signal);
      attempt++;
      continue;
    }

    if (response.status === 200) {
      return await response.text();
    }
    if (response.status === 410 || response.status === 404) {
      throw new RecordingError('CLIP_GONE', 'Clip is no longer available or session unknown');
    }
    throw new RecordingError('PLAYLIST_FETCH_FAILED', `Manifest endpoint returned HTTP ${response.status}`);
  }
}

/**
 * Parse an HLS `.m3u8` body into the init segment URL plus the ordered media
 * segment URLs, resolving relative URLs against `playlistUrl` itself.
 */
export function parsePlaylist(manifestBody: string, playlistUrl: string): ParsedPlaylist {
  let initUrl: string | undefined;
  const segments: string[] = [];

  for (const rawLine of manifestBody.split(/\r?\n/)) {
    const trimmed = rawLine.trim();

    if (!trimmed) {
      continue;
    }

    if (trimmed.startsWith('#EXT-X-MAP')) {
      const match = trimmed.match(/URI="([^"]+)"/);

      if (match?.[1]) {
        initUrl = resolveAgainst(match[1], playlistUrl);
      }
      continue;
    }
    if (trimmed.startsWith('#')) {
      continue;
    }
    segments.push(resolveAgainst(trimmed, playlistUrl));
  }

  if (!initUrl) {
    throw new RecordingError('INVALID_PLAYLIST', 'Playlist is missing an #EXT-X-MAP init segment URI');
  }
  if (segments.length === 0) {
    throw new RecordingError('INVALID_PLAYLIST', 'Playlist contains no media segments');
  }

  return { initUrl, segmentUrls: segments };
}

/**
 * Wrap an HLS manifest body in a `blob:` URL suitable for `<video src>` or
 * `hls.js`. Bypasses "the player can't set an Authorization header": the
 * manifest is served from memory, and its chunk URLs are already-signed S3
 * GETs. Path-only chunk URLs in the body are absolutized against
 * `playlistUrl` first — without that, the browser would resolve them against
 * the `blob:` URL's own origin instead of the runtime's.
 *
 * Caller owns the returned URL — revoke it via `URL.revokeObjectURL` when
 * playback tears down. Browser-only; throws `INVALID_PLAYLIST` outside a DOM
 * environment.
 */
export function createPlayableManifestUrl(manifestBody: string, playlistUrl: string): string {
  if (
    typeof Blob === 'undefined' ||
    typeof URL === 'undefined' ||
    typeof URL.createObjectURL !== 'function'
  ) {
    throw new RecordingError(
      'INVALID_PLAYLIST',
      'createPlayableManifestUrl requires a browser environment with URL.createObjectURL',
    );
  }
  const rewritten = absolutizeManifestUrls(manifestBody, playlistUrl);
  const blob = new Blob([rewritten], { type: 'application/vnd.apple.mpegurl' });

  return URL.createObjectURL(blob);
}

function absolutizeManifestUrls(manifestBody: string, playlistUrl: string): string {
  const eol = manifestBody.includes('\r\n') ? '\r\n' : '\n';
  const out: string[] = [];

  for (const line of manifestBody.split(/\r?\n/)) {
    if (line.startsWith('#EXT-X-MAP')) {
      out.push(
        line.replace(/URI="([^"]+)"/, (_: string, uri: string) => `URI="${resolveAgainst(uri, playlistUrl)}"`),
      );
      continue;
    }
    const trimmed = line.trim();

    if (!trimmed || trimmed.startsWith('#')) {
      out.push(line);
      continue;
    }
    out.push(resolveAgainst(trimmed, playlistUrl));
  }
  return out.join(eol);
}

// ─────────────────────────────────────────────────────────────────────────────
// downloadClipAsFile
// ─────────────────────────────────────────────────────────────────────────────

export interface DownloadClipOptions {
  /** Coordinator JWT for the manifest GET. The chunks it references are S3
   *  presigned URLs, fetched unauthenticated. */
  jwt?: string;
  /** Cancels both the playlist poll and any in-flight chunk fetches. */
  signal?: AbortSignal;
  /** Called after each chunk completes — useful for progress UI. */
  onProgress?: (info: { fetched: number; total: number; bytes: number }) => void;
}

/**
 * Fetch the chunks `clip.playlistUrl` references, remux them into a flat
 * MP4, and (when `filename` is non-null) trigger a browser `<a download>`.
 * Pass `filename: null` to skip the download trigger and just get the Blob.
 *
 * The remux rewrites the container only — H.264 NAL units and AAC packets
 * pass through unchanged — so `start_time=0`, faststart, and
 * `major_brand=isom` come for free with no re-encode. See `maybeRemux()` for
 * the fallback on the rare parse failure.
 *
 * Memory-bound: every chunk is held in `parts` for the full duration of the
 * fetch loop, and `maybeRemux()`'s underlying `mp4box` parser needs the whole
 * input up front, so there's no streaming path to disk. Fine for a bounded
 * clip; a full-session `requestRecording()` download can hold the entire
 * recording in memory at once.
 */
export async function downloadClipAsFile(
  clip: Clip,
  filename: string | null = 'reactor-clip.mp4',
  options: DownloadClipOptions = {},
): Promise<Blob> {
  const playlistOptions: FetchPlaylistOptions = { predictedReadyAtMs: clip.predictedReadyAtMs };

  if (options.signal) {
    playlistOptions.signal = options.signal;
  }
  if (options.jwt) {
    playlistOptions.jwt = options.jwt;
  }

  const manifestBody = await fetchPlaylist(clip.playlistUrl, playlistOptions);
  const { initUrl, segmentUrls } = parsePlaylist(manifestBody, clip.playlistUrl);
  const orderedUrls = [initUrl, ...segmentUrls];
  const chunkInit: RequestInit = {};

  if (options.signal) {
    chunkInit.signal = options.signal;
  }

  const parts: Uint8Array[] = [];
  let bytes = 0;

  for (const [i, url] of orderedUrls.entries()) {
    let response: Response;

    try {
      response = await fetch(url, chunkInit);
    } catch (error) {
      if (isAbortError(error)) {
        throw error;
      }
      throw new RecordingError('CHUNK_FETCH_FAILED', `Network error fetching chunk ${i}: ${(error as Error).message}`);
    }
    if (!response.ok) {
      throw new RecordingError('CHUNK_FETCH_FAILED', `Chunk ${i} returned HTTP ${response.status}`);
    }
    const data = new Uint8Array(await response.arrayBuffer());

    parts.push(data);
    bytes += data.byteLength;
    options.onProgress?.({ fetched: i + 1, total: orderedUrls.length, bytes });
  }

  const finalBytes = await maybeRemux(parts);
  const blob = new Blob([finalBytes as BlobPart], { type: 'video/mp4' });

  if (filename === null) {
    return blob;
  }
  if (typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') {
    throw new RecordingError(
      'DOWNLOAD_UNSUPPORTED',
      'downloadClipAsFile requires a DOM environment; pass filename=null to skip the download trigger',
    );
  }
  triggerBrowserDownload(blob, filename);
  return blob;
}

// ─────────────────────────────────────────────────────────────────────────────
// Fragmented MP4 → flat MP4 remux
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Concatenate `parts` and pipe the result through `remuxFragmentedToFlat()`.
 * Remux failures (`mp4box` blocked by an exotic bundler/CSP, corrupt input,
 * an unsupported codec) are funneled through one path: a `console.warn` and
 * the unmodified concatenation, so the download still succeeds end-to-end
 * with the (worse, but still playable) fragmented MP4 the runtime emitted.
 */
async function maybeRemux(parts: Uint8Array[]): Promise<Uint8Array> {
  const input = concatUint8Arrays(parts);

  try {
    const MP4Box = await loadMp4Box();

    return await remuxFragmentedToFlat(input, MP4Box);
  } catch (error) {
    console.warn('[Reactor] Clip remux failed, returning fragmented MP4 instead.', error);
    return input;
  }
}

/** Indirection so tests can stub the dynamic `mp4box` import without pulling
 *  the real bytes through every test that calls `downloadClipAsFile`.
 *  @internal */
export const __remuxInternals = {
  loadMp4Box: (): Promise<typeof MP4BoxTypes> => import('mp4box'),
};

function loadMp4Box(): Promise<typeof MP4BoxTypes> {
  return __remuxInternals.loadMp4Box();
}

/**
 * The actual fMP4 → flat MP4 conversion. No re-encode: every NAL unit and AAC
 * packet passes through unchanged. Only the container framing (boxes) and
 * decode timestamps are rewritten.
 *
 * The output file is initialized with `["isom", "mp42", "avc1", "iso2"]` as
 * compatible brands so the major brand is `isom` (not `iso5`, what
 * byte-concatenated fragments default to) and the sample tables are written
 * ahead of `mdat` — i.e. the file is implicitly faststart. Decode times are
 * shifted by one shared presentation-time origin across every track — the
 * earliest first-sample time, converted through each track's own timescale
 * — so the output starts at (close to) `0.000000` while the original
 * inter-track offset (e.g. audio starting slightly before or after video)
 * is preserved rather than each track independently zeroing to its own
 * first sample.
 */
async function remuxFragmentedToFlat(
  input: Uint8Array,
  MP4Box: typeof MP4BoxTypes,
): Promise<Uint8Array> {
  return new Promise<Uint8Array>((resolve, reject) => {
    const inFile = MP4Box.createFile();
    const outFile = MP4Box.createFile();

    outFile.init({ brands: ['isom', 'mp42', 'avc1', 'iso2'] });

    interface PendingTrack {
      trackOptions: MP4BoxTypes.IsoFileOptions;
      timescale: number;
      firstDts: number;
      samples: MP4BoxTypes.Sample[];
    }

    const pendingTracks = new Map<number, PendingTrack>();
    let settled = false;

    const fail = (err: Error) => {
      if (settled) {
        return;
      }
      settled = true;
      reject(err);
    };

    inFile.onError = (_module: string, message: string) => {
      fail(new Error(message));
    };

    inFile.onReady = (info) => {
      if (!info.tracks.length) {
        fail(new Error('no tracks in input'));
        return;
      }
      for (const track of info.tracks) {
        // setExtractionOptions delivers samples in batches via onSamples;
        // nbSamples controls batch size only, not the total.
        inFile.setExtractionOptions(track.id, null, { nbSamples: 1000 });
      }
      inFile.start();
    };

    // Samples are buffered per track (not written to outFile yet) because
    // the shared origin below needs every track's first sample DTS, and
    // tracks arrive interleaved as fragments are parsed.
    inFile.onSamples = (id, _user, samples) => {
      if (settled || samples.length === 0) {
        return;
      }

      let pending = pendingTracks.get(id);

      if (!pending) {
        // The sample carries the parsed input SampleEntry (avc1 / mp4a / …)
        // whose child boxes are the actual codec config (avcC, esds, …).
        // addTrack creates a fresh empty SampleEntry of the requested type,
        // so the input's child boxes are passed via description_boxes: they
        // become direct children of the new SampleEntry, where decoders
        // expect them. Passing the whole input SampleEntry as `description`
        // instead would nest it (stsd > avc1 > avc1 > avcC) and decoders
        // would fail to find avcC and render black.
        const firstSample = samples[0];

        if (!firstSample) {
          return;
        }

        const sampleEntry = firstSample.description as MP4BoxTypes.SampleEntry;
        const inputTrack = trackInfoById(inFile, id);
        const trackOptions: MP4BoxTypes.IsoFileOptions = {
          type: sampleEntry.type as MP4BoxTypes.SampleEntryFourCC,
          timescale: firstSample.timescale,
          // `boxes` is typed as `Box[]` but `description_boxes` wants the
          // narrower `BoxKind[]` union — at runtime they're the same
          // concrete instances, just typed loosely.
          description_boxes: sampleEntry.boxes as unknown as MP4BoxTypes.BoxKind[],
        };

        if (inputTrack?.track_width !== undefined) {
          trackOptions.width = inputTrack.track_width;
        }
        if (inputTrack?.track_height !== undefined) {
          trackOptions.height = inputTrack.track_height;
        }
        if (inputTrack?.language !== undefined) {
          trackOptions.language = inputTrack.language;
        }
        if (inputTrack?.video) {
          trackOptions.hdlr = 'vide';
        } else if (inputTrack?.audio) {
          trackOptions.hdlr = 'soun';
        }

        pending = { trackOptions, timescale: firstSample.timescale, firstDts: firstSample.dts, samples: [] };
        pendingTracks.set(id, pending);
      }

      pending.samples.push(...samples);
    };

    let buf: MP4BoxTypes.MP4BoxBuffer;

    try {
      buf = MP4Box.MP4BoxBuffer.fromArrayBuffer(
        input.buffer.slice(input.byteOffset, input.byteOffset + input.byteLength),
        0,
      );
    } catch (error) {
      fail(error as Error);
      return;
    }

    try {
      inFile.appendBuffer(buf);
      inFile.flush();
    } catch (error) {
      fail(error as Error);
      return;
    }

    if (settled) {
      return;
    }
    if (pendingTracks.size === 0) {
      fail(new Error('no samples extracted from input'));
      return;
    }

    let originSeconds = Infinity;

    for (const pending of pendingTracks.values()) {
      originSeconds = Math.min(originSeconds, pending.firstDts / pending.timescale);
    }

    try {
      for (const pending of pendingTracks.values()) {
        const outId = outFile.addTrack(pending.trackOptions);
        const dtsShift = Math.round(originSeconds * pending.timescale);

        for (const sample of pending.samples) {
          if (!sample.data) {
            continue;
          }
          outFile.addSample(outId, sample.data, {
            duration: sample.duration,
            dts: sample.dts - dtsShift,
            cts: sample.cts - dtsShift,
            is_sync: sample.is_sync,
          });
        }
      }
    } catch (error) {
      fail(error as Error);
      return;
    }

    let output: Uint8Array;

    try {
      const stream = outFile.getBuffer();

      output = new Uint8Array(stream.buffer.slice(0, stream.byteLength));
    } catch (error) {
      fail(error as Error);
      return;
    }

    settled = true;
    resolve(output);
  });
}

function trackInfoById(file: MP4BoxTypes.ISOFile, id: number): MP4BoxTypes.Track | undefined {
  const info = file.getInfo();

  return info.tracks.find((t) => t.id === id);
}

function concatUint8Arrays(parts: Uint8Array[]): Uint8Array {
  const only = parts.length === 1 ? parts[0] : undefined;

  if (only) {
    return only;
  }

  let total = 0;

  for (const p of parts) {total += p.byteLength;}

  const out = new Uint8Array(total);
  let offset = 0;

  for (const p of parts) {
    out.set(p, offset);
    offset += p.byteLength;
  }
  return out;
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────────────────────────────────────

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function resolveAgainst(target: string, base: string): string {
  try {
    return new URL(target, base).toString();
  } catch {
    return target;
  }
}

function parseRetryAfter(header: string | null, fallbackMs: number): number {
  if (!header) {
    return fallbackMs;
  }

  const seconds = Number(header);

  if (Number.isFinite(seconds) && seconds >= 0) {
    return seconds * 1000;
  }
  const dateMs = Date.parse(header);

  if (!Number.isNaN(dateMs)) {
    return Math.max(0, dateMs - Date.now());
  }
  return fallbackMs;
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'));
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new DOMException('Aborted', 'AbortError'));
    };

    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

function triggerBrowserDownload(blob: Blob, filename: string): void {
  const objectUrl = URL.createObjectURL(blob);

  try {
    const a = document.createElement('a');

    a.href = objectUrl;
    a.download = filename;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}
