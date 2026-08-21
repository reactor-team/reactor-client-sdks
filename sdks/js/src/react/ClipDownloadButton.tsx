import { useEffect, useRef } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { useClipDownload } from './useClipDownload';
import type { ClipDownloadState, UseClipDownloadOptions } from './useClipDownload';
import type { Clip } from '../types';

/**
 * Standalone download button for a captured {@link Clip}. Drops anywhere in
 * your UI — modal headers, list rows, hover menus, floating action buttons
 * — and is responsible for nothing more than triggering a download and
 * reflecting its state. Wraps `useClipDownload` internally; for completely
 * custom UIs (progress bars, menu items, post-download blob handling) call
 * that hook directly.
 *
 * Styling is intentionally minimal — override via `className`/`style`, or
 * replace the inner content with the `children` render-prop. No CSS file is
 * shipped; every default style is inline so it loses to anything the
 * consumer provides.
 */
export interface ClipDownloadButtonProps {
  /** The clip to download. */
  clip: Clip;
  /** Lazy JWT resolver. Optional inside a `ReactorProvider` (inherits the
   *  provider's resolver) and in local-dev mode. See `ClipPlayerProps.getJwt`. */
  getJwt?: () => string | Promise<string>;
  /** Filename for the saved MP4. Default `"reactor-clip.mp4"`. */
  filename?: string;
  /**
   * Inner content of the button. Three forms:
   *
   * - Omitted — renders a default label that follows the state
   *   (`"Download"` / `"Downloading 3/8…"` / etc.).
   * - `ReactNode` — static label, no state-driven text.
   * - `(state) => ReactNode` — state-aware render function, for custom
   *   progress strings, spinners, etc.
   */
  children?: ReactNode | ((state: ClipDownloadState) => ReactNode);
  /** Forwarded to the underlying `<button>`. */
  className?: string;
  /** Forwarded to the underlying `<button>` — merges after the defaults so
   *  each property overrides. */
  style?: CSSProperties;
  /** Forwarded to the underlying `<button>`. ORed with the internal
   *  "downloading" state. */
  disabled?: boolean;
  /** Fires when the download completes with the assembled MP4 Blob. */
  onSuccess?: (blob: Blob) => void;
  /** Fires when the download fails. Message mirrors the in-button state —
   *  `"<CODE>: <reason>"` for `RecordingError`s, the plain error message
   *  otherwise. */
  onError?: (error: Error) => void;
}

export function ClipDownloadButton({
  clip,
  getJwt,
  filename = 'reactor-clip.mp4',
  children,
  className,
  style,
  disabled,
  onSuccess,
  onError,
}: ClipDownloadButtonProps) {
  const downloadOptions: UseClipDownloadOptions = { filename };

  if (getJwt !== undefined) {
    downloadOptions.getJwt = getJwt;
  }

  const { state, download } = useClipDownload(clip, downloadOptions);
  const downloading = state.kind === 'downloading';
  const isDisabled = downloading || !!disabled;

  // Held in refs so inline callback identity doesn't churn the error-emit
  // effect or the click handler on every parent render.
  const onSuccessRef = useRef(onSuccess);
  const onErrorRef = useRef(onError);

  onSuccessRef.current = onSuccess;
  onErrorRef.current = onError;

  // `useClipDownload.download()` resolves to `undefined` on failure (the
  // hook surfaces errors through state, not by rejecting). Each retry that
  // lands back in `"error"` re-fires `onError`.
  useEffect(() => {
    if (state.kind === 'error') {
      onErrorRef.current?.(new Error(state.message));
    }
  }, [state]);

  const content = typeof children === 'function' ? children(state) : children !== undefined ? children : defaultLabel(state);

  return (
    <button
      type="button"
      onClick={() => {
        void download().then((blob) => {
          if (blob) {
            onSuccessRef.current?.(blob);
          }
        });
      }}
      disabled={isDisabled}
      title={state.kind === 'error' ? state.message : undefined}
      className={className}
      style={{
        padding: '5px 12px',
        borderRadius: 4,
        border: '1px solid rgba(255,255,255,0.15)',
        background: 'rgba(255,255,255,0.05)',
        color: '#fff',
        font: '11px ui-monospace, SFMono-Regular, Menlo, monospace',
        cursor: isDisabled ? 'default' : 'pointer',
        opacity: isDisabled ? 0.6 : 1,
        transition: 'background-color 120ms ease',
        ...style,
      }}
    >
      {content}
    </button>
  );
}

function defaultLabel(state: ClipDownloadState): ReactNode {
  if (state.kind === 'downloading') {
    if (state.total > 0) {
      return `Downloading ${state.fetched}/${state.total}…`;
    }
    return 'Downloading…';
  }
  return 'Download';
}
