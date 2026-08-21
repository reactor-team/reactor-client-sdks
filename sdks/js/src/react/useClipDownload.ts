import { useCallback, useContext, useEffect, useRef, useState } from 'react';
import { resolveJwtSource } from '../internal/jwt-resolver';
import { RecordingError, downloadClipAsFile } from '../recording';
import { ReactorContext } from './ReactorProvider';
import type { Clip } from '../types';

/**
 * State machine for an in-progress clip download.
 *
 * - `idle`: no download in flight (initial state, and what the hook returns
 *   to after a successful save).
 * - `downloading`: chunks are being fetched. `fetched`/`total` count the
 *   chunks (init segment + media segments) — useful for a progress bar.
 *   `total` is 0 until the manifest is parsed and the chunk count is known.
 * - `error`: most recent attempt failed; `message` is suitable for
 *   surfacing inline. `RecordingError`s are formatted as `"<CODE>: <reason>"`.
 */
export type ClipDownloadState =
  | { kind: 'idle' }
  | { kind: 'downloading'; fetched: number; total: number }
  | { kind: 'error'; message: string };

export interface UseClipDownloadOptions {
  /** Filename used when the browser save dialog opens. Pass `null` to skip
   *  the `<a download>` trigger entirely — the returned Blob is still
   *  resolved so the caller can `URL.createObjectURL` it or re-upload it.
   *  Default `"reactor-clip.mp4"`. */
  filename?: string | null;
  /**
   * Lazy resolver for the Coordinator JWT used on the manifest GET. Called
   * on every {@link UseClipDownloadResult.download} invocation, so token
   * refreshes are picked up automatically.
   *
   * Optional inside a `ReactorProvider` (inherits the provider's resolver)
   * and in local-dev mode.
   */
  getJwt?: () => string | Promise<string>;
}

export interface UseClipDownloadResult {
  /** Current state of the most recent download attempt. */
  state: ClipDownloadState;
  /**
   * Trigger a download. Resolves with the assembled fragmented-MP4 Blob, or
   * `undefined` if a download was already in flight (a no-op in that case)
   * or the attempt failed. Errors are surfaced via {@link state} rather than
   * a rejection — check `state.kind === "error"` to drive failure UI.
   */
  download: () => Promise<Blob | undefined>;
  /** Reset to `idle`. Does *not* cancel an in-flight download. */
  reset: () => void;
}

/**
 * Headless download primitive for a {@link Clip}. Wraps `downloadClipAsFile`
 * in a React state machine so the consumer can render any button they want,
 * anywhere they want, and still get progress + error feedback. Used
 * internally by `ClipDownloadButton` — reach for this hook directly when you
 * need custom placement or styling.
 *
 * `download`/`reset` are stable callback identities across renders, so
 * they're safe to pass through memoized children without forcing re-renders.
 */
export function useClipDownload(clip: Clip, options: UseClipDownloadOptions = {}): UseClipDownloadResult {
  const [state, setState] = useState<ClipDownloadState>({ kind: 'idle' });

  // `undefined` outside a `ReactorProvider`; used to inherit the provider's
  // JWT resolver when `options.getJwt` is omitted.
  const store = useContext(ReactorContext);

  // Latest-value refs so `download` can be a stable callback (empty deps)
  // without forcing the caller to memoize `clip`/`options`.
  const clipRef = useRef(clip);
  const filenameRef = useRef<string | null>(options.filename ?? 'reactor-clip.mp4');
  const getJwtRef = useRef(options.getJwt);
  const storeRef = useRef(store);

  clipRef.current = clip;
  filenameRef.current = options.filename === undefined ? 'reactor-clip.mp4' : options.filename;
  getJwtRef.current = options.getJwt;
  storeRef.current = store;

  // Re-entrancy guard. Lives in a ref (not state) so back-to-back
  // synchronous clicks before the first `setState` flushes are still
  // handled correctly.
  const inFlightRef = useRef(false);

  // `downloadClipAsFile` polls the manifest indefinitely by default, so an
  // in-flight download that never resolves would otherwise outlive the
  // component. Abort it on unmount; the abort surfaces as an `AbortError`,
  // swallowed below rather than painted as a failure.
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  const download = useCallback(async (): Promise<Blob | undefined> => {
    if (inFlightRef.current) {
      return undefined;
    }
    inFlightRef.current = true;
    const abort = new AbortController();

    abortRef.current = abort;
    setState({ kind: 'downloading', fetched: 0, total: 0 });
    try {
      // Explicit `options.getJwt` wins; fall back to the provider's
      // resolver. Reading at click time picks up provider swaps without
      // re-running this callback.
      const explicit = getJwtRef.current;
      const fallback = storeRef.current?.getState().internal.reactor.getJwtResolver();
      const jwt = explicit ? await explicit() : fallback !== undefined ? await resolveJwtSource(fallback) : undefined;
      const downloadOptions: Parameters<typeof downloadClipAsFile>[2] = {
        signal: abort.signal,
        onProgress: ({ fetched, total }) => setState({ kind: 'downloading', fetched, total }),
      };

      if (jwt !== undefined) {
        downloadOptions.jwt = jwt;
      }

      const blob = await downloadClipAsFile(clipRef.current, filenameRef.current, downloadOptions);

      setState({ kind: 'idle' });
      return blob;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        return undefined;
      }
      const message =
        err instanceof RecordingError ? `${err.code}: ${err.reason}` : err instanceof Error ? err.message : String(err);

      setState({ kind: 'error', message });
      return undefined;
    } finally {
      inFlightRef.current = false;
    }
  }, []);

  const reset = useCallback(() => setState({ kind: 'idle' }), []);

  return { state, download, reset };
}
