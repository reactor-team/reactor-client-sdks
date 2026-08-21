import type { Clip, ClipKind } from '../types';
import type { Clip as WireClip } from './reactor-wasm.types';

/** The wasm binding's snake_case wire shape → the public, camelCase one —
 *  what `Reactor.requestClip()` / `requestRecording()` hand back to a caller.
 *  One-directional: a `Clip` is only ever received, never sent, so there's no
 *  `toWireClip` counterpart.
 *
 *  `kind` is narrowed from the binding's own untyped `string` — `reactor-core`
 *  only ever sends `"snap"` (from `requestClip()`) or `"recording"` (from
 *  `requestRecording()`), same as `crates/reactor-wasm/src/types.rs`'s own
 *  doc comment for the field. */
export function toPublicClip(clip: WireClip): Clip {
  return {
    sessionId: clip.session_id,
    kind: clip.kind as ClipKind,
    startMarker: clip.start_marker,
    endMarker: clip.end_marker,
    nowMarker: clip.now_marker,
    predictedReadyAtMs: clip.predicted_ready_at_ms,
    playlistUrl: clip.playlist_url,
  };
}
