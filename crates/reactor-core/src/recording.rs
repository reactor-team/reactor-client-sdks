//! Recording / clip results.
//!
//! Clip and recording requests are correlated by `request_id` on the
//! control channel like every other control request — see
//! [`crate::control::ControlCorrelator`]. This module only holds the
//! resulting [`Clip`] type and its conversion from the wire's `ClipReady`.

use crate::protocol::wire::v1::platform::ClipReady;

/// A finished (or soon-available) clip.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Clip {
    pub session_id: String,
    /// "snap" (requestClip) or "recording" (requestRecording).
    pub kind: String,
    pub start_marker: f64,
    pub end_marker: f64,
    pub now_marker: f64,
    pub predicted_ready_at_ms: f64,
    /// Absolute HLS manifest URL.
    pub playlist_url: String,
}

/// Resolve a `ClipReady` message's playlist URL against the coordinator
/// base URL when the runtime returned a path-only URL.
pub fn clip_from_ready(ready: ClipReady, coordinator_base_url: &str) -> Clip {
    let playlist_url = if ready.playlist_url.starts_with("http://")
        || ready.playlist_url.starts_with("https://")
    {
        ready.playlist_url
    } else {
        format!(
            "{}/{}",
            coordinator_base_url.trim_end_matches('/'),
            ready.playlist_url.trim_start_matches('/')
        )
    };
    Clip {
        session_id: ready.session_id,
        kind: ready.kind,
        start_marker: ready.start_marker,
        end_marker: ready.end_marker,
        now_marker: ready.now_marker,
        predicted_ready_at_ms: ready.predicted_ready_at_ms as f64,
        playlist_url,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_relative_playlist_url() {
        let clip = clip_from_ready(
            ClipReady {
                session_id: "sess_1".into(),
                kind: "snap".into(),
                start_marker: 0.0,
                end_marker: 5.0,
                now_marker: 6.0,
                predicted_ready_at_ms: 1,
                playlist_url: "/clips/a.m3u8".into(),
            },
            "https://api.reactor.inc",
        );
        assert_eq!(clip.playlist_url, "https://api.reactor.inc/clips/a.m3u8");
    }

    #[test]
    fn keeps_absolute_playlist_url() {
        let clip = clip_from_ready(
            ClipReady {
                session_id: "sess_1".into(),
                kind: "recording".into(),
                start_marker: 0.0,
                end_marker: 5.0,
                now_marker: 6.0,
                predicted_ready_at_ms: 1,
                playlist_url: "https://cdn/b.m3u8".into(),
            },
            "https://api.reactor.inc",
        );
        assert_eq!(clip.playlist_url, "https://cdn/b.m3u8");
    }
}
