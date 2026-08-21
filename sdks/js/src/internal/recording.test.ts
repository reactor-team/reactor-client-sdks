import { describe, expect, it } from 'vitest';
import { toPublicClip } from './recording';
import type { Clip as WireClip } from './reactor-wasm.types';

describe('toPublicClip', () => {
  it("translates the wasm binding's snake_case wire shape to camelCase", () => {
    const wireClip: WireClip = {
      session_id: 'sess_1',
      kind: 'snap',
      start_marker: 120,
      end_marker: 150,
      now_marker: 150,
      predicted_ready_at_ms: 1_700_000_000_000,
      playlist_url: 'https://api.reactor.inc/clips?session_id=sess_1',
    };

    expect(toPublicClip(wireClip)).toEqual({
      sessionId: 'sess_1',
      kind: 'snap',
      startMarker: 120,
      endMarker: 150,
      nowMarker: 150,
      predictedReadyAtMs: 1_700_000_000_000,
      playlistUrl: 'https://api.reactor.inc/clips?session_id=sess_1',
    });
  });
});
