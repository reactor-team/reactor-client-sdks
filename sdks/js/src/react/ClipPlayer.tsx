'use client';

import { useContext, useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { type ClipPlayback, attachClipPlayback } from '../clip-playback';
import { resolveJwtSource } from '../internal/jwt-resolver';
import { RecordingError, assembleClipBlob, createPlayableManifestUrl, fetchPlaylist } from '../recording';
import { ReactorContext } from './ReactorProvider';
import type { Clip } from '../types';

/**
 * Video preview for a captured {@link Clip}. Streams the clip with `hls.js`
 * wherever Media Source Extensions exist — every current browser, iOS
 * Safari 17.1 and later included. `hls.js` is dynamically imported, so
 * bundlers give it a chunk of its own that an app importing this component
 * fetches on first play, and one that never renders a player drops
 * entirely. Where Media Source Extensions don't exist — iOS before 17.1 —
 * the clip is assembled into a single MP4 and played from memory instead.
 * Failures surface in an inline error overlay; the chunks remain
 * downloadable via {@link useClipDownload}/`ClipDownloadButton` either way.
 *
 * Preview only — this component doesn't render a download UI. Compose it
 * with `ClipDownloadButton`, or build a custom download surface around
 * `useClipDownload`.
 *
 * Unlike `ReactorView`/`WebcamStream`, this doesn't require a
 * `ReactorProvider` in the tree — it operates on the `Clip` value alone, so
 * it stays usable after `reactor.disconnect()` and works with clips loaded
 * from fixtures or any other source. When a `ReactorProvider` is mounted
 * above, an omitted `getJwt` inherits the provider's resolver.
 */
export interface ClipPlayerProps {
  /** The captured clip to play. Re-fetches the manifest and re-attaches the
   *  player when this changes by reference. */
  clip: Clip;
  /**
   * Lazy resolver for the Coordinator JWT used on the manifest GET, called
   * at request time so token refreshes are picked up automatically.
   *
   * Required outside a `ReactorProvider` in production; optional inside one
   * (inherits the provider's resolver). Omit in local-dev mode (HttpRuntime)
   * — the manifest endpoint there is auth-free.
   */
  getJwt?: () => string | Promise<string>;
  /**
   * Opt into a bounded wait: give up polling the manifest with
   * `CLIP_NOT_READY` once `max(clip.predictedReadyAtMs, pollStart) + slackMs`
   * passes. Unset by default — the player polls indefinitely until the clip
   * is ready, stopping only when it unmounts or `clip` changes. Forwarded
   * directly to `fetchPlaylist()`'s `slackMs` option.
   */
  slackMs?: number;
  /** Play automatically once the manifest is attached. Default `true`. */
  autoPlay?: boolean;
  /** Start muted. Default `true` — browser autoplay policies block
   *  audio-bearing video from playing without a user gesture; the user can
   *  unmute via the native controls. */
  muted?: boolean;
  className?: string;
  style?: CSSProperties;
  /** Fires when the player enters its inline error state. A `RecordingError`
   *  for manifest-fetch failures, a plain `Error` for hls.js/element playback
   *  failures. */
  onError?: (error: Error) => void;
}

type Phase =
  | { kind: 'waiting' }
  | { kind: 'loading' }
  | { kind: 'ready' }
  | { kind: 'error'; message: string; error: Error };

export function ClipPlayer({
  clip,
  getJwt,
  slackMs,
  autoPlay = true,
  muted = true,
  className,
  style,
  onError,
}: ClipPlayerProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'waiting' });

  // `undefined` outside a `ReactorProvider`; used to inherit the provider's
  // JWT resolver when `getJwt` is omitted.
  const store = useContext(ReactorContext);

  // The playback effect intentionally depends only on `clip`. Callers
  // typically pass an inline `getJwt={() => token}` that changes identity on
  // every render — using it directly in the effect deps would tear down the
  // player and re-fetch the manifest on every parent re-render. Same
  // reasoning for `autoPlay`: it's only read at the moment of attach. Refs
  // keep the latest values reachable from inside the effect without forcing
  // it to re-run.
  const getJwtRef = useRef(getJwt);
  const autoPlayRef = useRef(autoPlay);
  const slackMsRef = useRef(slackMs);
  const onErrorRef = useRef(onError);
  // Same ref pattern: the resolver is looked up inside `setup` at request
  // time, so a provider swap is picked up on next attach without tearing the
  // player down.
  const storeRef = useRef(store);

  getJwtRef.current = getJwt;
  autoPlayRef.current = autoPlay;
  slackMsRef.current = slackMs;
  onErrorRef.current = onError;
  storeRef.current = store;

  // Re-fires per error transition: each new `clip` resets through
  // `waiting`/`loading` before potentially re-entering `error`.
  useEffect(() => {
    if (phase.kind === 'error') {
      onErrorRef.current?.(phase.error);
    }
  }, [phase]);

  // Playback pipeline: fetch manifest (with optional JWT) → wrap in blob URL
  // → hand to `attachClipPlayback`, which either streams it with hls.js or
  // assembles the chunks into an MP4. Re-runs only when `clip` changes by
  // reference. The cleanup closure tears every piece down deterministically.
  useEffect(() => {
    const video = videoRef.current;

    if (!video) {
      return;
    }

    const abort = new AbortController();
    let cancelled = false;
    let playback: ClipPlayback | null = null;
    let manifestBlobUrl: string | null = null;

    const fail = (error: Error) => {
      if (cancelled) {
        return;
      }
      const message = error instanceof RecordingError ? `${error.code}: ${error.reason}` : error.message;

      setPhase({ kind: 'error', message, error });
    };

    const setup = async () => {
      try {
        setPhase({ kind: 'waiting' });
        // Explicit `getJwt` wins; fall back to the provider's resolver.
        const explicit = getJwtRef.current;
        const fallback = storeRef.current?.getState().internal.reactor.getJwtResolver();
        const jwt = explicit ? await explicit() : fallback !== undefined ? await resolveJwtSource(fallback) : undefined;

        if (cancelled) {
          return;
        }
        const playlistOptions: Parameters<typeof fetchPlaylist>[1] = {
          predictedReadyAtMs: clip.predictedReadyAtMs,
          signal: abort.signal,
        };

        if (slackMsRef.current !== undefined) {
          playlistOptions.slackMs = slackMsRef.current;
        }
        if (jwt !== undefined) {
          playlistOptions.jwt = jwt;
        }

        const body = await fetchPlaylist(clip.playlistUrl, playlistOptions);

        if (cancelled) {
          return;
        }
        setPhase({ kind: 'loading' });
        manifestBlobUrl = createPlayableManifestUrl(body, clip.playlistUrl);
        playback = attachClipPlayback(
          video,
          {
            manifestUrl: manifestBlobUrl,
            assembleMp4: () => assembleClipBlob(body, clip.playlistUrl, { signal: abort.signal }),
          },
          {
            autoPlay: autoPlayRef.current,
            onReady: () => {
              if (!cancelled) {
                setPhase({ kind: 'ready' });
              }
            },
            onError: fail,
          },
        );
      } catch (err) {
        if (cancelled) {
          return;
        }
        // `AbortError` from teardown is expected — don't paint it as a failure.
        if (err instanceof DOMException && err.name === 'AbortError') {
          return;
        }
        fail(err instanceof Error ? err : new Error(String(err)));
      }
    };

    void setup();

    return () => {
      cancelled = true;
      abort.abort();
      playback?.destroy();
      video.pause();
      video.removeAttribute('src');
      video.load();
      if (manifestBlobUrl) {
        URL.revokeObjectURL(manifestBlobUrl);
      }
    };
  }, [clip]);

  const overlayText = phase.kind === 'waiting' ? 'Waiting for clip…' : phase.kind === 'loading' ? 'Loading player…' : null;

  return (
    <div
      className={className}
      style={{
        position: 'relative',
        background: '#000',
        width: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        ...style,
      }}
    >
      <video
        ref={videoRef}
        controls
        playsInline
        muted={muted}
        style={{
          display: 'block',
          width: '100%',
          height: 'auto',
          maxHeight: '100%',
        }}
      />

      {overlayText && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'rgba(255,255,255,0.6)',
            font: '11px ui-monospace, SFMono-Regular, Menlo, monospace',
            letterSpacing: '0.1em',
            textTransform: 'uppercase',
            pointerEvents: 'none',
          }}
        >
          {overlayText}
        </div>
      )}

      {phase.kind === 'error' && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 24,
            background: 'rgba(0,0,0,0.8)',
            color: '#ef4444',
            font: '11px ui-monospace, SFMono-Regular, Menlo, monospace',
            textAlign: 'center',
          }}
        >
          {phase.message}
        </div>
      )}
    </div>
  );
}
