//! Recording / clip results, and turning one into a playable file.
//!
//! Clip and recording requests are correlated by `request_id` on the
//! control channel like every other control request — see
//! [`crate::control::ControlCorrelator`]. This module holds the resulting
//! [`Clip`] type, its conversion from the wire's `ClipReady`, and
//! [`clip_segment_requests`], which turns a [`Clip`] into the ordered list of
//! requests whose bodies, concatenated, are the file.
//!
//! Reactor does not host clips: `playlist_url` names a short-lived HLS media
//! playlist and it is on the caller to fetch and assemble the fragments. That
//! assembly has three rules, each of which was a shipped bug first, so it lives
//! here where every binding gets the same answer rather than in each of them:
//!
//! 1. **The init segment is a comment line.** `#EXT-X-MAP:URI="…"` carries the
//!    `ftyp`/`moov` every fragment after it is parsed against. A parser that
//!    skips `#` lines drops the one part that makes the rest readable and writes
//!    a file no player opens.
//! 2. **A segment can be presigned on another host.** The playlist needs the
//!    bearer token; a presigned URL *rejects* one rather than ignoring it. Auth
//!    goes same-origin only.
//! 3. **Readiness is in media time.** A 202 means the chunk holding the end of
//!    the window has not closed, and it closes because the model keeps
//!    generating — so the bound on waiting is the session still being alive, not
//!    a number of seconds.

use std::time::Duration;

use crate::error::CoreError;
use crate::http::{check_status, HttpRequest, Method};
use crate::protocol::wire::v1::platform::ClipReady;
use crate::{SharedHttp, SharedPlatform};

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

// ── Assembling a clip ────────────────────────────────────────────────────────

/// Bounds on the wait between polls, matching the Python and JS SDKs: a floor so
/// a `Retry-After: 0` is a retry rather than a spin, and a ceiling so a large one
/// does not park a download for minutes when the chunk may close sooner.
const MIN_RETRY_DELAY: Duration = Duration::from_millis(200);
const MAX_RETRY_DELAY: Duration = Duration::from_millis(2_000);

/// `URI="…"` inside an `#EXT-X-MAP` line.
const INIT_URI_KEY: &str = "URI=\"";

/// Fragment extensions that cannot stand on their own. A playlist naming these
/// with no init segment would assemble into a file no player opens, so that is an
/// error rather than a silently broken clip.
const FRAGMENT_SUFFIXES: [&str; 4] = [".m4s", ".mp4", ".m4a", ".m4v"];

/// How long to keep asking a playlist that answers 202.
///
/// `session_is_live` is the bound that matters. A clip becomes ready because the
/// model keeps generating; once the session is gone the chunk holding the end of
/// the window will never close, so a 202 is a 202 forever and waiting longer is
/// waiting for nothing. Callers with no way to tell pass `|| true` and rely on
/// `timeout`.
pub struct Readiness<L> {
    /// Grace *past* when the clip was expected, or `None` to wait as long as the
    /// session is alive.
    pub timeout: Option<Duration>,
    /// The runtime's own guess, in Unix milliseconds, as carried by [`Clip`].
    ///
    /// It is a wall clock plus media seconds, so it is only right for a model
    /// generating at real time — one at a tenth of that reaches the boundary ten
    /// times later. Used as the anchor the grace is measured from, never as a
    /// deadline in itself.
    pub predicted_ready_at_ms: f64,
    /// Whether the session producing this clip is still generating.
    pub session_is_live: L,
}

impl<L: Fn() -> bool> Readiness<L> {
    /// Wait as long as the session lives, with no wall-clock bound.
    pub fn while_live(session_is_live: L) -> Self {
        Self {
            timeout: None,
            predicted_ready_at_ms: 0.0,
            session_is_live,
        }
    }
}

/// The requests whose bodies, concatenated in order, are the clip — init segment
/// first, then each fragment.
///
/// Waits out the 202s before returning, so a caller that gets a list has a
/// playlist that exists. Every request carries the auth it should and no auth it
/// should not, so a host only has to perform them and append the bodies.
pub async fn clip_segment_requests<L: Fn() -> bool>(
    http: &SharedHttp,
    platform: &SharedPlatform,
    playlist_url: &str,
    jwt: Option<&str>,
    readiness: &Readiness<L>,
) -> Result<Vec<HttpRequest>, CoreError> {
    let playlist = fetch_playlist(http, platform, playlist_url, jwt, readiness).await?;

    Ok(parse_playlist(&playlist, playlist_url)?
        .into_iter()
        .map(|segment| {
            let url = join_url(playlist_url, &segment);
            let headers = match jwt {
                // Same-origin only: a presigned segment on another host answers
                // 400 to an Authorization header rather than ignoring it.
                Some(jwt) if same_origin(&url, playlist_url) => {
                    vec![("Authorization".to_string(), format!("Bearer {jwt}"))]
                }
                _ => Vec::new(),
            };
            HttpRequest {
                method: Method::Get,
                url,
                headers,
                body: None,
            }
        })
        .collect())
}

/// GET the playlist, waiting out any 202s.
async fn fetch_playlist<L: Fn() -> bool>(
    http: &SharedHttp,
    platform: &SharedPlatform,
    playlist_url: &str,
    jwt: Option<&str>,
    readiness: &Readiness<L>,
) -> Result<String, CoreError> {
    // Accumulated sleep rather than two clock readings: the core only has a wall
    // clock, and a deadline computed from one moves if the clock does.
    let mut remaining = readiness.timeout.map(|timeout| {
        let ahead_ms = readiness.predicted_ready_at_ms - platform.now_ms();
        // Anchored at the runtime's prediction while that is still ahead: the
        // budget is grace past when the clip was expected, not from whenever the
        // download happened to start.
        timeout + Duration::from_secs_f64((ahead_ms.max(0.0)) / 1_000.0)
    });
    let mut polls = 0_u32;

    loop {
        let headers = match jwt {
            Some(jwt) => vec![("Authorization".to_string(), format!("Bearer {jwt}"))],
            None => Vec::new(),
        };
        let response = http
            .request(HttpRequest {
                method: Method::Get,
                url: playlist_url.to_string(),
                headers,
                body: None,
            })
            .await?;

        if response.status != 202 {
            check_status(&response, "fetch clip playlist")?;
            return Ok(response.body_text());
        }
        polls += 1;

        if !(readiness.session_is_live)() {
            return Err(CoreError::Timeout(format!(
                "the clip playlist at {playlist_url} was not ready when the session \
                 ended, after {polls} polls. The chunk holding the end of the window \
                 had not closed, and media time only advances while the session \
                 generates — so this clip cannot become ready. Ask for the clip \
                 earlier, or keep the session connected until the download finishes."
            )));
        }

        let delay = retry_delay(response.header("retry-after"));
        let delay = match remaining {
            None => delay,
            Some(left) if left.is_zero() => {
                return Err(CoreError::Timeout(format!(
                    "the clip playlist at {playlist_url} was still not ready after \
                     {polls} polls. Readiness is in media time, not wall clock: the \
                     manifest appears once the recording passes the end of the chunk \
                     holding the window. A model generating slower than real time takes \
                     proportionally longer — keep the session running, or allow more \
                     time (none at all to wait as long as it lives)."
                )));
            }
            Some(left) => {
                let delay = delay.min(left);
                remaining = Some(left - delay);
                delay
            }
        };
        platform.sleep(delay).await;
    }
}

/// What to wait after a 202, from `Retry-After`, clamped.
///
/// The header carries the chunk length, which is the honest estimate of when the
/// boundary chunk closes. An HTTP-date is legal and unreadable here — turning one
/// into a duration needs a trusted clock — so it falls back like a missing header.
fn retry_delay(retry_after: Option<&str>) -> Duration {
    retry_after
        .and_then(|value| value.trim().parse::<f64>().ok())
        .filter(|seconds| seconds.is_finite() && *seconds >= 0.0)
        .map(Duration::from_secs_f64)
        .unwrap_or(MAX_RETRY_DELAY)
        .clamp(MIN_RETRY_DELAY, MAX_RETRY_DELAY)
}

/// The segments to fetch, in write order: init segment first, then fragments.
fn parse_playlist(playlist: &str, playlist_url: &str) -> Result<Vec<String>, CoreError> {
    let mut init: Option<String> = None;
    let mut segments: Vec<String> = Vec::new();

    for line in playlist.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Some(rest) = line.strip_prefix("#EXT-X-MAP") {
            // The one comment line that is not a comment to us.
            if let Some(start) = rest.find(INIT_URI_KEY) {
                let after = &rest[start + INIT_URI_KEY.len()..];
                if let Some(end) = after.find('"') {
                    init = Some(after[..end].to_string());
                }
            }
            continue;
        }
        if line.starts_with('#') {
            continue;
        }
        segments.push(line.to_string());
    }

    if segments.is_empty() {
        return Err(CoreError::Decode(format!(
            "the clip playlist at {playlist_url} names no segments"
        )));
    }

    if init.is_none()
        && segments.iter().any(|segment| {
            let path = path_of(segment);
            FRAGMENT_SUFFIXES
                .iter()
                .any(|suffix| path.ends_with(suffix))
        })
    {
        return Err(CoreError::Decode(format!(
            "the clip playlist at {playlist_url} names fragmented-MP4 segments but no \
             #EXT-X-MAP init segment, so they cannot be assembled into a playable file"
        )));
    }

    Ok(match init {
        Some(init) => std::iter::once(init).chain(segments).collect(),
        None => segments,
    })
}

/// A segment's path, without the query a presigned URL carries.
fn path_of(segment: &str) -> &str {
    let path = match segment.split_once("://") {
        // Absolute: skip the authority, keep everything from its first slash on.
        Some((_, rest)) => rest.find('/').map_or("", |slash| &rest[slash..]),
        None => segment,
    };
    path.split(['?', '#']).next().unwrap_or(path)
}

/// Resolve a segment against the playlist that named it.
///
/// Three cases, and the middle one is the reason this is not string
/// concatenation: manifests mix relative fragment names with absolute-*path*
/// URIs like `/clips/chunks/sid/chunk_00001.m4s`, and appending one of those to
/// the playlist's directory produces a doubled path that 404s.
fn join_url(playlist_url: &str, segment: &str) -> String {
    if segment.contains("://") {
        return segment.to_string();
    }
    let (scheme, rest) = match playlist_url.split_once("://") {
        Some((scheme, rest)) => (scheme, rest),
        // Not a URL we can reason about; the caller's own base is the best answer.
        None => return segment.to_string(),
    };
    let authority = rest.split('/').next().unwrap_or(rest);
    if let Some(absolute) = segment.strip_prefix('/') {
        return format!("{scheme}://{authority}/{absolute}");
    }
    let base = playlist_url
        .split(['?', '#'])
        .next()
        .unwrap_or(playlist_url);
    let directory = match base.rfind('/') {
        // Past the "://", so the slash found is a path separator.
        Some(slash) if slash > scheme.len() + 2 => &base[..slash],
        _ => base,
    };
    format!("{directory}/{segment}")
}

/// Whether two URLs share a scheme and authority.
fn same_origin(a: &str, b: &str) -> bool {
    fn origin(url: &str) -> Option<(&str, &str)> {
        let (scheme, rest) = url.split_once("://")?;
        Some((scheme, rest.split('/').next().unwrap_or(rest)))
    }
    match (origin(a), origin(b)) {
        (Some(a), Some(b)) => a == b,
        // A segment that is not absolute was resolved against the playlist, so it
        // cannot be anywhere else.
        _ => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::error::codes;
    use crate::http::{HttpClient, HttpResponse};
    use crate::runtime::Platform;
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};

    /// The manifest both the coordinator (`services/pkg/handlers/clips_handler.go`)
    /// and a local runtime (`recording/recorder.py`) build, copied in shape from
    /// those builders by way of `sdks/python/tests/test_recording.py`: fragmented
    /// MP4, version 7, an `#EXT-X-MAP` init segment, then one `#EXTINF` + `.m4s`
    /// pair per fragment.
    ///
    /// Copied rather than invented on purpose. The Python clip tests passed for
    /// weeks against a playlist shape nothing serves, and the missing init segment
    /// is exactly the bug that hid behind it — a fixture you wrote yourself agrees
    /// with you.
    ///
    /// Note the deliberate mix: a relative fragment name next to an absolute-path
    /// one. That is what the real manifests contain, and the pair a resolver has to
    /// get right.
    const FMP4_PLAYLIST: &str = concat!(
        "#EXTM3U\n",
        "#EXT-X-VERSION:7\n",
        "#EXT-X-TARGETDURATION:4\n",
        "#EXT-X-PLAYLIST-TYPE:VOD\n",
        "#EXT-X-MAP:URI=\"/clips/chunks/sid/init.mp4\"\n",
        "#EXTINF:4.000,\n",
        "chunk_00000.m4s\n",
        "#EXTINF:4.000,\n",
        "/clips/chunks/sid/chunk_00001.m4s\n",
        "#EXT-X-ENDLIST\n",
    );

    const PLAYLIST_URL: &str = "https://api.reactor.inc/hls/clip.m3u8";

    /// Replays scripted responses per URL, and counts what it was asked for.
    struct ScriptedHttp {
        /// Responses per URL, popped from the front, the last one repeating.
        routes: Mutex<HashMap<String, Vec<HttpResponse>>>,
        seen: Mutex<Vec<HttpRequest>>,
    }

    impl ScriptedHttp {
        fn new(routes: &[(&str, Vec<HttpResponse>)]) -> Arc<Self> {
            Arc::new(Self {
                routes: Mutex::new(
                    routes
                        .iter()
                        .map(|(url, responses)| (url.to_string(), responses.clone()))
                        .collect(),
                ),
                seen: Mutex::new(Vec::new()),
            })
        }

        fn playlist_answering(responses: Vec<HttpResponse>) -> Arc<Self> {
            Self::new(&[(PLAYLIST_URL, responses)])
        }

        fn requests(&self) -> Vec<HttpRequest> {
            self.seen.lock().unwrap().clone()
        }
    }

    fn ok(body: &str) -> HttpResponse {
        HttpResponse {
            status: 200,
            headers: Vec::new(),
            body: body.as_bytes().to_vec(),
        }
    }

    fn pending(retry_after: Option<&str>) -> HttpResponse {
        HttpResponse {
            status: 202,
            headers: retry_after
                .map(|value| vec![("Retry-After".to_string(), value.to_string())])
                .unwrap_or_default(),
            body: Vec::new(),
        }
    }

    #[async_trait::async_trait]
    impl HttpClient for ScriptedHttp {
        async fn request(&self, request: HttpRequest) -> Result<HttpResponse, CoreError> {
            self.seen.lock().unwrap().push(request.clone());
            let mut routes = self.routes.lock().unwrap();
            let responses = routes
                .get_mut(&request.url)
                .unwrap_or_else(|| panic!("nothing scripted for {}", request.url));
            if responses.len() > 1 {
                Ok(responses.remove(0))
            } else {
                Ok(responses[0].clone())
            }
        }
    }

    /// Sleeps instantly and records what it was asked to wait, so a test can assert
    /// the backoff without spending it.
    #[derive(Default)]
    struct FakeClock {
        slept: Mutex<Vec<Duration>>,
    }

    impl FakeClock {
        fn slept(&self) -> Vec<Duration> {
            self.slept.lock().unwrap().clone()
        }
    }

    impl Platform for FakeClock {
        fn sleep(&self, duration: Duration) -> crate::BoxFut<'static, ()> {
            self.slept.lock().unwrap().push(duration);
            Box::pin(std::future::ready(()))
        }

        fn now_ms(&self) -> f64 {
            1_700_000_000_000.0
        }
    }

    fn block_on<F: std::future::Future>(future: F) -> F::Output {
        tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap()
            .block_on(future)
    }

    fn segments_of(
        http: Arc<ScriptedHttp>,
        platform: Arc<FakeClock>,
        jwt: Option<&str>,
        timeout: Option<Duration>,
        session_is_live: bool,
    ) -> Result<Vec<HttpRequest>, CoreError> {
        block_on(clip_segment_requests(
            &(http as SharedHttp),
            &(platform as SharedPlatform),
            PLAYLIST_URL,
            jwt,
            &Readiness {
                timeout,
                predicted_ready_at_ms: 0.0,
                session_is_live: || session_is_live,
            },
        ))
    }

    /// The init segment is a comment line. A parser that skips `#` lines drops the
    /// `ftyp`/`moov` the fragments are parsed against and writes a file no player
    /// opens — so it has to come out first, ahead of every fragment.
    #[test]
    fn the_init_segment_comes_first_and_the_fragments_resolve_around_it() {
        let http = ScriptedHttp::playlist_answering(vec![ok(FMP4_PLAYLIST)]);
        let requests = segments_of(http, Arc::new(FakeClock::default()), None, None, true).unwrap();

        assert_eq!(
            requests.iter().map(|r| r.url.as_str()).collect::<Vec<_>>(),
            vec![
                // From #EXT-X-MAP, and first.
                "https://api.reactor.inc/clips/chunks/sid/init.mp4",
                // Relative: resolved against the playlist's own directory.
                "https://api.reactor.inc/hls/chunk_00000.m4s",
                // Absolute path: resolved against the origin, *not* appended to
                // the directory, which would 404 on a doubled path.
                "https://api.reactor.inc/clips/chunks/sid/chunk_00001.m4s",
            ]
        );
    }

    /// A presigned URL answers 400 to an Authorization header rather than ignoring
    /// it, so the token goes to the coordinator and nowhere else.
    #[test]
    fn the_token_goes_to_the_coordinator_and_never_to_another_host() {
        let playlist = concat!(
            "#EXTM3U\n#EXT-X-VERSION:7\n",
            "#EXT-X-MAP:URI=\"init.mp4\"\n",
            "#EXTINF:4.000,\nchunk_00000.m4s\n",
            "#EXTINF:4.000,\nhttps://cdn.example.com/presigned/chunk_00001.m4s?sig=abc\n",
            "#EXT-X-ENDLIST\n",
        );
        let http = ScriptedHttp::playlist_answering(vec![ok(playlist)]);
        let requests = segments_of(
            http.clone(),
            Arc::new(FakeClock::default()),
            Some("token"),
            None,
            true,
        )
        .unwrap();

        let auth = |request: &HttpRequest| {
            request
                .headers
                .iter()
                .any(|(name, _)| name.eq_ignore_ascii_case("authorization"))
        };
        assert!(auth(&requests[0]), "the init segment is same-origin");
        assert!(auth(&requests[1]), "a relative fragment is same-origin");
        assert!(
            !auth(&requests[2]),
            "a presigned segment on another host must be asked without auth"
        );

        // And the playlist itself, which is always the coordinator, does carry it.
        assert!(auth(&http.requests()[0]));
    }

    /// 202 is not an error: the chunk holding the end of the window has not closed
    /// yet. `Retry-After` carries the chunk length, which is the honest estimate.
    #[test]
    fn a_pending_playlist_is_retried_on_the_servers_own_hint() {
        let http = ScriptedHttp::playlist_answering(vec![
            pending(Some("1")),
            pending(Some("1")),
            ok(FMP4_PLAYLIST),
        ]);
        let clock = Arc::new(FakeClock::default());

        let requests = segments_of(http.clone(), clock.clone(), None, None, true).unwrap();

        assert_eq!(requests.len(), 3);
        assert_eq!(http.requests().len(), 3, "two polls, then the playlist");
        assert_eq!(
            clock.slept(),
            vec![Duration::from_secs(1), Duration::from_secs(1)]
        );
    }

    /// A `Retry-After: 0` must be a retry rather than a spin, and a huge one must
    /// not park the download for minutes when the chunk may close sooner.
    #[test]
    fn the_retry_hint_is_clamped_at_both_ends() {
        assert_eq!(retry_delay(Some("0")), MIN_RETRY_DELAY);
        assert_eq!(retry_delay(Some("600")), MAX_RETRY_DELAY);
        assert_eq!(retry_delay(Some("1")), Duration::from_secs(1));
        // An HTTP-date is legal and unreadable without a trusted clock, so it falls
        // back like a missing header rather than becoming a guess.
        assert_eq!(
            retry_delay(Some("Wed, 21 Oct 2015 07:28:00 GMT")),
            MAX_RETRY_DELAY
        );
        assert_eq!(retry_delay(None), MAX_RETRY_DELAY);
        assert_eq!(retry_delay(Some("-5")), MAX_RETRY_DELAY);
    }

    /// The bound that actually matters. A clip becomes ready because the model
    /// keeps generating; once the session is gone, a 202 is a 202 forever and every
    /// further poll is waiting for something that cannot happen.
    #[test]
    fn a_dead_session_ends_the_wait_instead_of_polling_forever() {
        let http = ScriptedHttp::playlist_answering(vec![pending(Some("1"))]);
        let clock = Arc::new(FakeClock::default());

        let error = segments_of(http.clone(), clock.clone(), None, None, false)
            .expect_err("a clip that cannot become ready is an error");

        assert_eq!(error.code(), codes::REQUEST_TIMEOUT);
        assert!(error.to_string().contains("when the session ended"));
        assert_eq!(http.requests().len(), 1, "it stops after the first 202");
        assert!(
            clock.slept().is_empty(),
            "and does not sleep on the way out"
        );
    }

    /// The wall-clock bound is the fallback for a caller that cannot tell whether
    /// the session lives. It has to actually stop.
    #[test]
    fn a_timeout_stops_a_playlist_that_stays_pending() {
        let http = ScriptedHttp::playlist_answering(vec![pending(Some("1"))]);
        let clock = Arc::new(FakeClock::default());

        let error = segments_of(
            http.clone(),
            clock.clone(),
            None,
            Some(Duration::from_millis(1_500)),
            true,
        )
        .expect_err("an exhausted budget is an error");

        assert_eq!(error.code(), codes::REQUEST_TIMEOUT);
        assert!(error.to_string().contains("media time"));
        // 1s, then the 500ms that is left, then out — never longer than the budget.
        assert_eq!(
            clock.slept(),
            vec![Duration::from_secs(1), Duration::from_millis(500)]
        );
    }

    /// Fragments with no `#EXT-X-MAP` would assemble into a headerless file that
    /// looks downloaded and opens in nothing. Better to say so than to write it.
    #[test]
    fn fragments_without_an_init_segment_are_refused() {
        let error = parse_playlist(
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXTINF:4.0,\nchunk_00000.m4s\n#EXT-X-ENDLIST\n",
            PLAYLIST_URL,
        )
        .expect_err("headerless fragments are an error");

        assert_eq!(error.code(), codes::DECODE_FAILED);
        assert!(error.to_string().contains("#EXT-X-MAP"));
    }

    /// A playlist naming self-contained segments is fine without one — the init
    /// segment is an fMP4 requirement, not an HLS one.
    #[test]
    fn self_contained_segments_need_no_init_segment() {
        let segments = parse_playlist(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4.0,\nchunk_00000.ts\n#EXT-X-ENDLIST\n",
            PLAYLIST_URL,
        )
        .unwrap();
        assert_eq!(segments, vec!["chunk_00000.ts"]);
    }

    #[test]
    fn a_playlist_naming_nothing_is_a_decode_failure() {
        let error = parse_playlist("#EXTM3U\n#EXT-X-ENDLIST\n", PLAYLIST_URL)
            .expect_err("a segmentless playlist is an error");
        assert_eq!(error.code(), codes::DECODE_FAILED);
        assert!(error.to_string().contains("names no segments"));
    }

    /// An expired playlist URL is the ordinary failure here — clips are held for a
    /// limited time — and it must arrive as a status, not as a parse error.
    #[test]
    fn a_gone_playlist_reports_its_status() {
        let http = ScriptedHttp::playlist_answering(vec![HttpResponse {
            status: 404,
            headers: Vec::new(),
            body: b"expired".to_vec(),
        }]);
        let error = segments_of(http, Arc::new(FakeClock::default()), None, None, true)
            .expect_err("404 is an error");
        assert_eq!(error.code(), codes::NOT_FOUND);
    }

    /// The query a presigned URL carries must not be read as part of the extension,
    /// or a `.m4s?sig=…` stops looking like a fragment and the init-segment check
    /// silently passes on a playlist it should reject.
    #[test]
    fn a_presigned_query_is_not_part_of_the_path() {
        assert_eq!(
            path_of("https://cdn.example.com/a/chunk.m4s?sig=abc&x=1"),
            "/a/chunk.m4s"
        );
        assert_eq!(path_of("/clips/chunk.m4s"), "/clips/chunk.m4s");
        assert_eq!(path_of("chunk.m4s"), "chunk.m4s");
        assert_eq!(path_of("https://cdn.example.com"), "");
    }

    #[test]
    fn a_segment_resolves_the_way_a_browser_would() {
        let base = "https://api.reactor.inc/hls/clip.m3u8?token=1";
        assert_eq!(
            join_url(base, "chunk.m4s"),
            "https://api.reactor.inc/hls/chunk.m4s"
        );
        assert_eq!(
            join_url(base, "/clips/chunk.m4s"),
            "https://api.reactor.inc/clips/chunk.m4s"
        );
        assert_eq!(
            join_url(base, "https://cdn.example.com/chunk.m4s?sig=abc"),
            "https://cdn.example.com/chunk.m4s?sig=abc"
        );
        // A playlist at the root of a host: there is no directory to strip.
        assert_eq!(
            join_url("https://api.reactor.inc/clip.m3u8", "chunk.m4s"),
            "https://api.reactor.inc/chunk.m4s"
        );
    }

    #[test]
    fn origins_differ_by_scheme_host_or_port() {
        assert!(same_origin(
            "https://api.reactor.inc/a",
            "https://api.reactor.inc/b"
        ));
        assert!(!same_origin(
            "https://cdn.example.com/a",
            "https://api.reactor.inc/a"
        ));
        assert!(!same_origin(
            "http://api.reactor.inc/a",
            "https://api.reactor.inc/a"
        ));
        assert!(!same_origin(
            "https://api.reactor.inc:8443/a",
            "https://api.reactor.inc/a"
        ));
    }

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
