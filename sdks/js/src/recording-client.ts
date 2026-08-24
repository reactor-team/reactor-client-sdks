import type { Reactor } from './reactor';
import {
  createPlayableManifestUrl,
  fetchPlaylist as fetchPlaylistFn,
  type DownloadClipOptions,
  type FetchPlaylistOptions,
} from './recording';
import type { Clip } from './types';

/**
 * Standalone wrapper around a `Reactor`'s clip/recording surface, for
 * callers who'd rather hold a dedicated object than reach through a
 * `Reactor` instance directly. Every method delegates straight through —
 * `reactor-core` already owns the request/reply correlation and per-request
 * timeout behind `requestClip()`/`requestRecording()`, so there's nothing
 * left for this class to track on its own.
 */
export class RecordingClient {
  constructor(private readonly reactor: Reactor) {}

  /** See `Reactor.requestClip()`. */
  requestClip(durationSeconds: number): Promise<Clip> {
    return this.reactor.requestClip(durationSeconds);
  }

  /** See `Reactor.requestRecording()`. */
  requestRecording(): Promise<Clip> {
    return this.reactor.requestRecording();
  }

  /** Polls `clip.playlistUrl` and returns the raw manifest body — see the
   *  standalone `fetchPlaylist()`. */
  fetchPlaylist(clip: Clip, options: FetchPlaylistOptions = {}): Promise<string> {
    return fetchPlaylistFn(clip.playlistUrl, { predictedReadyAtMs: clip.predictedReadyAtMs, ...options });
  }

  /**
   * Fetches the manifest and returns a `blob:` URL suitable for
   * `<video src>`/`hls.js`. Caller owns the returned URL — revoke it via
   * `URL.revokeObjectURL()` once playback tears down.
   */
  async getPlayableManifestUrl(clip: Clip, options: FetchPlaylistOptions = {}): Promise<string> {
    const body = await this.fetchPlaylist(clip, options);

    return createPlayableManifestUrl(body, clip.playlistUrl);
  }

  /** See `Reactor.downloadClipAsFile()`. */
  downloadClipAsFile(
    clip: Clip,
    filename: string | null = 'reactor-clip.mp4',
    options?: DownloadClipOptions,
  ): Promise<Blob> {
    return this.reactor.downloadClipAsFile(clip, filename, options);
  }

  /** No-op — this class holds no subscriptions or in-flight state of its
   *  own to release. The `Reactor` it wraps owns its own lifecycle
   *  independently of this wrapper's. */
  destroy(): void {}
}
