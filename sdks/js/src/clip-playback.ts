/**
 * Attaches a captured clip to a `<video>` element for preview.
 *
 * There are two ways to put a clip on an element, and which one is available
 * is decided by `Hls.isSupported()` — never by
 * `canPlayType("application/vnd.apple.mpegurl")`, which answers `"maybe"` in
 * browsers that then fail the load with `MediaError` 4.
 *
 * `hls.js` streams the clip wherever Media Source Extensions exist, which is
 * every current browser, including the Managed variant iOS Safari exposes.
 * Where they don't — iOS before 17.1 — the clip is assembled into a single
 * flat MP4 and played from memory. That costs the whole clip up front
 * instead of streaming it, which for a few seconds of video is a fair trade
 * for working at all.
 *
 * Handing the manifest to the element instead is not a third option, and
 * each engine rules it out for its own reason. Native HLS makes the element
 * load the media segments itself, and Chromium refuses segments from an
 * origin other than the page's whatever CORS headers they carry, while a
 * clip's chunks are presigned URLs that always come from elsewhere. WebKit
 * takes those cross-origin segments happily but won't read a `blob:`
 * manifest at all — and the manifest is always a blob, because fetching it
 * takes an `Authorization` header. Both playback paths here therefore fetch
 * the media themselves: `hls.js` appends it through Media Source Extensions,
 * and the assembled MP4 has no sub-resources left to load.
 *
 * The element is the single source of truth for readiness and failure:
 * `loadedmetadata` marks playback ready (a parsed manifest says nothing
 * about the element holding decodable media), and the element's `error`
 * event carries the `MediaError` that would otherwise leave the viewer
 * looking at a black frame.
 *
 * Rendering, state, and manifest fetching live in the `ClipPlayer` React
 * component; this module owns nothing but the attach.
 */

/** The two forms a clip can take, either of which can drive an element. */
export interface ClipSource {
  /** `blob:` URL of the HLS manifest, streamed by `hls.js`. */
  manifestUrl: string;
  /** Assemble the clip into one self-contained MP4. Called only when
   *  `hls.js` can't run, and expected to honour the caller's abort signal. */
  assembleMp4: () => Promise<Blob>;
}

/** Wired up by {@link attachClipPlayback}. */
export interface ClipPlaybackOptions {
  /** Start playing as soon as the element has metadata. */
  autoPlay: boolean;
  /** Called once the element has loaded the clip's metadata. */
  onReady: () => void;
  /** Called at most once, with an error suitable for display. */
  onError: (error: Error) => void;
  /** Resolves `hls.js`. Defaults to a dynamic `import()`, which bundlers keep
   *  in its own chunk so consumers who never render a player aren't billed
   *  for it. A rejection is not fatal: playback falls back to the assembled
   *  MP4. */
  loadHls?: () => Promise<HlsConstructor>;
}

export interface ClipPlayback {
  /** Detaches every listener, tears down `hls.js`, frees the MP4. */
  destroy: () => void;
}

/**
 * Play `source` on `video`.
 *
 * Returns as soon as the element is wired up: choosing a path means loading
 * `hls.js`, and taking the fallback means downloading the clip, so both
 * happen in the background and the handle is destroyable throughout.
 * Playback becoming available is reported through
 * {@link ClipPlaybackOptions.onReady}.
 */
export function attachClipPlayback(
  video: HTMLVideoElement,
  source: ClipSource,
  {
    autoPlay,
    onReady,
    onError,
    loadHls = () => import('hls.js').then((mod) => (mod as { default: HlsConstructor }).default),
  }: ClipPlaybackOptions,
): ClipPlayback {
  let destroyed = false;
  let failed = false;
  let ready = false;
  let hls: HlsInstance | null = null;
  let mp4Url: string | null = null;

  const fail = (error: Error) => {
    if (destroyed || failed) {
      return;
    }
    failed = true;
    onError(error);
  };

  const handleLoadedMetadata = () => {
    // Metadata can arrive more than once, and can arrive after a failure was
    // reported. Reporting it again would clear an error the viewer is
    // reading, or restart a clip they paused.
    if (destroyed || failed || ready) {
      return;
    }
    ready = true;
    onReady();
    if (autoPlay) {
      video.play().catch(() => {
        // Autoplay may be blocked by the browser; native controls still work.
      });
    }
  };

  const handleElementError = () => {
    fail(new Error(describeMediaError(video.error)));
  };

  video.addEventListener('loadedmetadata', handleLoadedMetadata);
  video.addEventListener('error', handleElementError);

  const handle: ClipPlayback = {
    destroy: () => {
      destroyed = true;
      video.removeEventListener('loadedmetadata', handleLoadedMetadata);
      video.removeEventListener('error', handleElementError);
      hls?.destroy();
      hls = null;
      if (mp4Url) {
        URL.revokeObjectURL(mp4Url);
        mp4Url = null;
      }
    },
  };

  const selectPath = async () => {
    // A browser with no MediaSource of any kind can't run `hls.js`, so don't
    // spend the download to find out — go straight to the MP4. Loading it
    // and asking is still the rule everywhere else: this test is the weakest
    // part of `Hls.isSupported()`, never a reimplementation of it.
    const HlsCtor = hasMediaSource() ? await loadHls().catch(() => null) : null;

    if (destroyed) {
      return;
    }

    if (HlsCtor?.isSupported()) {
      const instance = new HlsCtor();

      instance.loadSource(source.manifestUrl);
      instance.attachMedia(video);
      instance.on(HlsCtor.Events.ERROR, (_evt: unknown, data: HlsErrorData) => {
        if (destroyed) {
          return;
        }
        if (data.fatal) {
          fail(new Error(`Playback error: ${data.details ?? 'unknown'}`));
          return;
        }
        // Non-fatal errors are the usual explanation for a "fetches but
        // nothing renders" symptom (bufferAppendingError, fragParsingError,
        // levelLoadError), which the user-facing overlay would otherwise
        // hide.
        console.warn('[Reactor.ClipPlayer] hls.js non-fatal error', data);
      });
      hls = instance;
      return;
    }

    const blob = await source.assembleMp4();

    if (destroyed) {
      return;
    }
    mp4Url = URL.createObjectURL(blob);
    video.src = mp4Url;
  };

  selectPath().catch((err: unknown) => {
    fail(err instanceof Error ? err : new Error(String(err)));
  });

  return handle;
}

/**
 * Whether the browser exposes a MediaSource `hls.js` could drive — the
 * Managed variant included, which is the one iOS Safari 17.1 added. Older
 * iOS has none of the three and is the reason clips still need a path that
 * doesn't stream.
 */
function hasMediaSource(): boolean {
  const scope = globalThis as Record<string, unknown>;

  return Boolean(scope.ManagedMediaSource ?? scope.MediaSource ?? scope.WebKitMediaSource);
}

const MEDIA_ERROR_MESSAGES: Record<number, string> = {
  1: 'Playback was aborted.',
  2: 'A network error interrupted playback.',
  3: 'This clip could not be decoded.',
  4: 'This browser cannot play this clip. Use Download instead.',
};

/**
 * Turn the element's `MediaError` into displayable text, keeping the
 * browser's own diagnostic (`DEMUXER_ERROR_...` and friends) when it
 * provides one.
 */
function describeMediaError(error: MediaError | null): string {
  const message = (error && MEDIA_ERROR_MESSAGES[error.code]) ?? 'This clip failed to play in this browser. Use Download instead.';

  return error?.message ? `${message} (${error.message})` : message;
}

// ─────────────────────────────────────────────────────────────────────────────
// Structural typings covering exactly the `hls.js` surface this module
// drives. Naming the shape rather than importing the class keeps the loader
// swappable, which is what lets the tests exercise every path without the
// real library.
// ─────────────────────────────────────────────────────────────────────────────

export interface HlsInstance {
  loadSource: (url: string) => void;
  attachMedia: (el: HTMLMediaElement) => void;
  on: (event: string, cb: (evt: unknown, data: HlsErrorData) => void) => void;
  destroy: () => void;
}

export interface HlsConstructor {
  new (): HlsInstance;
  isSupported: () => boolean;
  readonly Events: {
    readonly ERROR: string;
  };
}

export interface HlsErrorData {
  fatal?: boolean;
  details?: string;
}
