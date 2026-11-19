//! Negotiating encoded step frames in the SDP.
//!
//! A stepped client that carries its step frames encoded declares the
//! session-level attribute `a=x-reactor-encoded-step-frames` in its offer,
//! beside an m-section per input and output track whose codec list is its
//! preference for that track. A runtime that encodes step frames repeats the
//! attribute in its answer, and each video track then carries the codec its
//! answered m-section lists first: H.264, VP8, VP9 or AV1, as the
//! [`FrameEncoding`] of that name. An audio track, a section that negotiated
//! any other codec or one the offer sent did not list, and every track of an
//! answer without the attribute, or to an offer without it, carry
//! [`FrameEncoding::Raw`] frames. The sections carry no media: every frame
//! travels in data-channel messages. The negotiation holds for the
//! connection, and a new connection negotiates again.
//!
//! A track's codecs are the caller's preference ([`CodecPreferences`]) first,
//! in its order, then every other step codec in [`StepCodec::DEFAULT_ORDER`]:
//! a preference orders the codecs, it does not limit them, so a runtime that
//! has none of the preferred ones still answers with another. That list is
//! filtered twice: by what this side can carry step frames in
//! ([`LocalStepCodecs`]: encode for an input track, decode for an output
//! one), and by what the engine listed in the track's section. The client
//! declares the attribute only when every mapped video track keeps a codec:
//! a section the runtime could answer with a codec this side cannot handle
//! would carry frames nobody here decodes.
//!
//! The core rewrites only the offer it sends, never the one the engine
//! applied locally: it adds the attribute and, on each mapped video section,
//! moves the kept codecs first in that order and drops the step codecs this
//! side cannot handle. The answer stays a subset of what the engine offered,
//! so the engine applies it as it would the unrewritten offer's.

use std::collections::HashMap;

use crate::protocol::session::{TrackCapability, TrackDirection, TrackKind};
use crate::protocol::webrtc::TrackMappingEntry;
use crate::protocol::wire::v1::step::FrameEncoding;

/// The session-level SDP attribute that negotiates encoded step frames.
pub const ENCODED_STEP_FRAMES: &str = "x-reactor-encoded-step-frames";

/// A video codec a step frame can be encoded in.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum StepCodec {
    H264,
    Vp8,
    Vp9,
    Av1,
}

impl StepCodec {
    /// The order a client prefers when the caller sets none: H.264 first,
    /// since hardware decodes it almost everywhere, then VP9 and AV1, which
    /// compress better at a higher encode cost, then VP8.
    pub const DEFAULT_ORDER: [StepCodec; 4] = [
        StepCodec::H264,
        StepCodec::Vp9,
        StepCodec::Av1,
        StepCodec::Vp8,
    ];

    /// The codec's encoding name in an SDP `a=rtpmap` line, upper-cased.
    pub fn sdp_name(self) -> &'static str {
        match self {
            StepCodec::H264 => "H264",
            StepCodec::Vp8 => "VP8",
            StepCodec::Vp9 => "VP9",
            StepCodec::Av1 => "AV1",
        }
    }

    /// The codec an SDP encoding name stands for, in any case.
    pub fn from_sdp_name(name: &str) -> Option<Self> {
        Self::DEFAULT_ORDER
            .into_iter()
            .find(|codec| codec.sdp_name().eq_ignore_ascii_case(name))
    }

    /// The wire encoding of a frame in this codec.
    pub fn frame_encoding(self) -> FrameEncoding {
        match self {
            StepCodec::H264 => FrameEncoding::H264,
            StepCodec::Vp8 => FrameEncoding::Vp8,
            StepCodec::Vp9 => FrameEncoding::Vp9,
            StepCodec::Av1 => FrameEncoding::Av1,
        }
    }

    /// The codec of a frame in `encoding`; `None` for a raw or unknown one.
    pub fn from_frame_encoding(encoding: FrameEncoding) -> Option<Self> {
        Self::DEFAULT_ORDER
            .into_iter()
            .find(|codec| codec.frame_encoding() == encoding)
    }

    /// reactor-webrtc's name for this codec.
    #[cfg(feature = "webrtc-codecs")]
    pub fn video_codec(self) -> reactor_webrtc::VideoCodec {
        use reactor_webrtc::VideoCodec;
        match self {
            StepCodec::H264 => VideoCodec::H264,
            StepCodec::Vp8 => VideoCodec::Vp8,
            StepCodec::Vp9 => VideoCodec::Vp9,
            StepCodec::Av1 => VideoCodec::Av1,
        }
    }

    /// The step codec reactor-webrtc's `codec` stands for; `None` for one a
    /// step frame cannot be carried in (H.265).
    #[cfg(feature = "webrtc-codecs")]
    pub fn from_video_codec(codec: reactor_webrtc::VideoCodec) -> Option<Self> {
        Self::DEFAULT_ORDER
            .into_iter()
            .find(|step| step.video_codec() == codec)
    }
}

impl LocalStepCodecs {
    /// What reactor-webrtc's standalone codecs can carry step frames in here,
    /// by its capabilities, each direction in [`StepCodec::DEFAULT_ORDER`].
    #[cfg(feature = "webrtc-codecs")]
    pub fn from_capabilities(capabilities: &reactor_webrtc::codec::CodecCapabilities) -> Self {
        let supported = |can: &dyn Fn(reactor_webrtc::VideoCodec) -> bool| {
            StepCodec::DEFAULT_ORDER
                .into_iter()
                .filter(|codec| can(codec.video_codec()))
                .collect()
        };
        Self {
            encode: supported(&|codec| capabilities.can_encode(codec)),
            decode: supported(&|codec| capabilities.can_decode(codec)),
        }
    }
}

/// The codecs a client prefers for its step frames, for the whole connection
/// and per track, read when the next offer is built. A preference is an
/// order, not a limit: the codecs it leaves out follow it, in
/// [`StepCodec::DEFAULT_ORDER`] ([`CodecPreferences::order_for_track`]).
///
/// The connection-wide order defaults to [`StepCodec::DEFAULT_ORDER`]. An
/// empty connection-wide order turns encoded step frames off: the offer then
/// declares nothing and every track is raw, whatever a track prefers. A track
/// with its own order uses it in place of the connection-wide one.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CodecPreferences {
    /// `None` for [`StepCodec::DEFAULT_ORDER`].
    session: Option<Vec<StepCodec>>,
    tracks: HashMap<String, Vec<StepCodec>>,
}

impl CodecPreferences {
    /// Set the connection-wide order, or with `None` go back to the default.
    /// `Some` of an empty list turns encoded step frames off. A codec listed
    /// twice keeps its first place.
    pub fn set_session(&mut self, codecs: Option<Vec<StepCodec>>) {
        self.session = codecs.map(dedup);
    }

    /// Set one track's order, or with `None` go back to the connection-wide
    /// one. An empty list is taken as `None`: a single track cannot turn
    /// encoding off while the others keep it.
    pub fn set_track(&mut self, track: &str, codecs: Option<Vec<StepCodec>>) {
        match codecs.map(dedup).filter(|codecs| !codecs.is_empty()) {
            Some(codecs) => {
                self.tracks.insert(track.to_string(), codecs);
            }
            None => {
                self.tracks.remove(track);
            }
        }
    }

    /// Whether encoded step frames are on: the next offer declares them when
    /// every track also keeps a codec (see [`stepped_offer`]).
    pub fn enabled(&self) -> bool {
        self.session
            .as_ref()
            .is_none_or(|codecs| !codecs.is_empty())
    }

    /// The order `track` prefers, most preferred first. Empty when encoding
    /// is off.
    pub fn for_track(&self, track: &str) -> &[StepCodec] {
        if !self.enabled() {
            return &[];
        }
        if let Some(codecs) = self.tracks.get(track) {
            return codecs;
        }
        self.session.as_deref().unwrap_or(&StepCodec::DEFAULT_ORDER)
    }

    /// Every step codec, in the order the next offer lists them for `track`:
    /// [`CodecPreferences::for_track`] first, then the rest in
    /// [`StepCodec::DEFAULT_ORDER`]. Empty when encoding is off.
    pub fn order_for_track(&self, track: &str) -> Vec<StepCodec> {
        let preferred = self.for_track(track);
        if preferred.is_empty() {
            return Vec::new();
        }
        let rest = StepCodec::DEFAULT_ORDER
            .iter()
            .filter(|codec| !preferred.contains(codec));
        preferred.iter().chain(rest).copied().collect()
    }
}

/// The codecs this side can carry step frames in: encode, for the frames it
/// pushes on an input track, and decode, for the frames a step result brings
/// on an output track. The host sets them from its codecs; empty in both
/// directions, the default, every track stays raw.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LocalStepCodecs {
    pub encode: Vec<StepCodec>,
    pub decode: Vec<StepCodec>,
}

impl LocalStepCodecs {
    /// The codecs a track flowing in `direction` can be carried in: encoded
    /// for a track this side sends, decoded for one it receives.
    pub fn for_direction(&self, direction: TrackDirection) -> &[StepCodec] {
        match direction {
            TrackDirection::Sendonly => &self.encode,
            TrackDirection::Recvonly => &self.decode,
        }
    }
}

fn dedup(codecs: Vec<StepCodec>) -> Vec<StepCodec> {
    let mut unique = Vec::with_capacity(codecs.len());
    for codec in codecs {
        if !unique.contains(&codec) {
            unique.push(codec);
        }
    }
    unique
}

/// One m-section of an SDP: its lines, the `m=` line first.
struct Section<'a> {
    lines: Vec<&'a str>,
}

impl<'a> Section<'a> {
    fn mid(&self) -> Option<&'a str> {
        self.lines
            .iter()
            .find_map(|line| line.strip_prefix("a=mid:"))
    }

    fn is_video(&self) -> bool {
        self.lines[0].starts_with("m=video ")
    }

    /// The m-line's payload types, in order.
    fn payload_types(&self) -> Vec<&'a str> {
        self.lines[0].split_whitespace().skip(3).collect()
    }

    /// The encoding name `a=rtpmap` gives `pt`, upper-cased.
    fn codec_name(&self, pt: &str) -> Option<String> {
        self.lines.iter().find_map(|line| {
            let rest = line.strip_prefix("a=rtpmap:")?;
            let (line_pt, name) = rest.split_once(' ')?;
            (line_pt == pt).then(|| name.split('/').next().unwrap_or("").trim().to_uppercase())
        })
    }

    /// The payload type an RTX payload type `pt` repairs (`a=fmtp:<pt> apt=`).
    fn rtx_apt(&self, pt: &str) -> Option<&'a str> {
        self.lines.iter().find_map(|line| {
            let rest = line.strip_prefix("a=fmtp:")?;
            let (line_pt, params) = rest.split_once(' ')?;
            if line_pt != pt {
                return None;
            }
            params
                .split(';')
                .find_map(|param| param.trim().strip_prefix("apt="))
        })
    }

    /// The payload types a RED payload type `pt` carries redundancy for
    /// (`a=fmtp:<pt> 96/96`): empty for any other payload type, or a RED one
    /// without that list.
    fn red_payload_types(&self, pt: &str) -> Vec<&'a str> {
        if self.codec_name(pt).as_deref() != Some("RED") {
            return Vec::new();
        }
        self.lines
            .iter()
            .find_map(|line| {
                let rest = line.strip_prefix("a=fmtp:")?;
                let (line_pt, params) = rest.split_once(' ')?;
                (line_pt == pt).then(|| params.trim().split('/').map(str::trim).collect())
            })
            .unwrap_or_default()
    }

    /// The step codecs the section lists.
    fn codecs(&self) -> Vec<StepCodec> {
        self.payload_types()
            .into_iter()
            .filter_map(|pt| StepCodec::from_sdp_name(&self.codec_name(pt)?))
            .collect()
    }

    /// The step codec the section lists first, the one an answer negotiated.
    fn first_codec(&self) -> Option<StepCodec> {
        // A rejected section (port 0) negotiated nothing.
        if self.lines[0].split_whitespace().nth(1) == Some("0") {
            return None;
        }
        let first = *self.payload_types().first()?;
        StepCodec::from_sdp_name(&self.codec_name(first)?)
    }

    /// This section with `preference`'s codecs first, in its order, and the
    /// step codecs it does not name (those this side cannot handle; a
    /// caller's preference only orders the rest) dropped with their
    /// retransmission payload types and
    /// attributes, and with every RED payload type that carries redundancy
    /// for one of them (an `a=fmtp` list naming a payload type the m-line no
    /// longer has is invalid). Unchanged when it lists none of `preference`, which
    /// [`stepped_offer`] never asks: removing every codec would leave a
    /// section the answerer cannot accept.
    fn prefer(&self, preference: &[StepCodec]) -> Vec<String> {
        let pts = self.payload_types();
        let codec_of = |pt: &str| {
            self.codec_name(pt)
                .and_then(|name| StepCodec::from_sdp_name(&name))
        };
        let mut preferred: Vec<&str> = Vec::new();
        for codec in preference {
            preferred.extend(pts.iter().filter(|pt| codec_of(pt) == Some(*codec)));
        }
        if preferred.is_empty() {
            return self.lines.iter().map(|line| line.to_string()).collect();
        }
        let mut dropped: Vec<&str> = pts
            .iter()
            .copied()
            .filter(|pt| codec_of(pt).is_some() && !preferred.contains(pt))
            .collect();
        let dropped_red: Vec<&str> = pts
            .iter()
            .copied()
            .filter(|pt| {
                self.red_payload_types(pt)
                    .iter()
                    .any(|carried| dropped.contains(carried))
            })
            .collect();
        dropped.extend(dropped_red);
        let dropped_rtx: Vec<&str> = pts
            .iter()
            .copied()
            .filter(|pt| self.rtx_apt(pt).is_some_and(|apt| dropped.contains(&apt)))
            .collect();
        dropped.extend(dropped_rtx);
        let rest = pts
            .iter()
            .copied()
            .filter(|pt| !preferred.contains(pt) && !dropped.contains(pt));
        let order: Vec<&str> = preferred.iter().copied().chain(rest).collect();

        let m_fields: Vec<&str> = self.lines[0].split_whitespace().take(3).collect();
        let mut lines = vec![format!("{} {}", m_fields.join(" "), order.join(" "))];
        lines.extend(
            self.lines[1..]
                .iter()
                .filter(|line| !names_payload_type(line, &dropped))
                .map(|line| line.to_string()),
        );
        lines
    }
}

/// Whether `line` is an `a=rtpmap`, `a=fmtp` or `a=rtcp-fb` attribute of one
/// of `pts`.
fn names_payload_type(line: &str, pts: &[&str]) -> bool {
    ["a=rtpmap:", "a=fmtp:", "a=rtcp-fb:"].iter().any(|prefix| {
        line.strip_prefix(prefix)
            .and_then(|rest| rest.split_whitespace().next())
            .is_some_and(|pt| pts.contains(&pt))
    })
}

/// An SDP split into its session lines and its m-sections, with the line
/// ending it uses.
struct Sdp<'a> {
    eol: &'static str,
    session: Vec<&'a str>,
    sections: Vec<Section<'a>>,
}

impl<'a> Sdp<'a> {
    fn parse(sdp: &'a str) -> Self {
        let eol = if sdp.contains("\r\n") { "\r\n" } else { "\n" };
        let mut session = Vec::new();
        let mut sections: Vec<Section<'a>> = Vec::new();
        for line in sdp.lines() {
            if line.is_empty() {
                continue;
            }
            if line.starts_with("m=") {
                sections.push(Section { lines: vec![line] });
            } else if let Some(section) = sections.last_mut() {
                section.lines.push(line);
            } else {
                session.push(line);
            }
        }
        Self {
            eol,
            session,
            sections,
        }
    }

    fn has_session_attribute(&self, name: &str) -> bool {
        self.session.iter().any(|line| {
            line.strip_prefix("a=")
                .is_some_and(|attr| attr == name || attr.starts_with(&format!("{name}:")))
        })
    }

    fn section(&self, mid: &str) -> Option<&Section<'a>> {
        self.sections
            .iter()
            .find(|section| section.mid() == Some(mid))
    }
}

/// Rewrite the offer the engine built into the one a stepped client sends:
/// the [`ENCODED_STEP_FRAMES`] attribute at session level, and each mapped
/// video track's section with the codecs it keeps first (see the module
/// docs). Unchanged when encoding is off, and when a mapped video track keeps
/// no codec, which leaves every track raw.
pub fn stepped_offer(
    sdp: &str,
    mapping: &[TrackMappingEntry],
    preferences: &CodecPreferences,
    local: &LocalStepCodecs,
) -> String {
    if !preferences.enabled() {
        return sdp.to_string();
    }
    let parsed = Sdp::parse(sdp);
    // The codecs each mapped video track keeps, by its section's mid.
    let mut kept: HashMap<&str, Vec<StepCodec>> = HashMap::new();
    for entry in mapping.iter().filter(|e| e.kind == TrackKind::Video) {
        let Some(section) = parsed.section(&entry.mid).filter(|s| s.is_video()) else {
            return sdp.to_string();
        };
        let listed = section.codecs();
        let codecs: Vec<StepCodec> = preferences
            .order_for_track(&entry.name)
            .into_iter()
            .filter(|codec| local.for_direction(entry.direction).contains(codec))
            .filter(|codec| listed.contains(codec))
            .collect();
        if codecs.is_empty() {
            return sdp.to_string();
        }
        kept.insert(entry.mid.as_str(), codecs);
    }
    let mut lines: Vec<String> = parsed.session.iter().map(|l| l.to_string()).collect();
    if !parsed.has_session_attribute(ENCODED_STEP_FRAMES) {
        lines.push(format!("a={ENCODED_STEP_FRAMES}"));
    }
    for section in &parsed.sections {
        match section.mid().and_then(|mid| kept.get(mid)) {
            Some(codecs) => lines.extend(section.prefer(codecs)),
            None => lines.extend(section.lines.iter().map(|line| line.to_string())),
        }
    }
    let mut out = lines.join(parsed.eol);
    out.push_str(parsed.eol);
    out
}

/// Whether [`stepped_offer`] can declare encoded step frames for `tracks`, as
/// far as this side decides it: encoding is on and every video track keeps a
/// codec this side handles in its direction. Only the engine's offer can
/// still turn it down, by listing none of a track's codecs.
pub fn may_encode_step_frames(
    tracks: &[TrackCapability],
    preferences: &CodecPreferences,
    local: &LocalStepCodecs,
) -> bool {
    preferences.enabled()
        && tracks
            .iter()
            .filter(|track| track.kind == TrackKind::Video)
            .all(|track| {
                preferences
                    .order_for_track(&track.name)
                    .iter()
                    .any(|codec| local.for_direction(track.direction).contains(codec))
            })
}

/// Whether a connection negotiated encoded step frames: the `offer` this side
/// sent and the runtime's `answer` both carry [`ENCODED_STEP_FRAMES`]. Only
/// then does the runtime take `PushFrames`; otherwise input frames ride the
/// next step's `media_input`.
pub fn negotiates_encoded_step_frames(answer: &str, offer: &str) -> bool {
    Sdp::parse(offer).has_session_attribute(ENCODED_STEP_FRAMES)
        && Sdp::parse(answer).has_session_attribute(ENCODED_STEP_FRAMES)
}

/// The encoding each mapped track's step frames take on a connection, by
/// track name, read from the runtime's answer to the `offer` this side sent:
/// a video track takes the codec its section lists first when both the offer
/// and the answer carry [`ENCODED_STEP_FRAMES`] and the offer listed that
/// codec in the track's section, and every other track is
/// [`FrameEncoding::Raw`]. The engine applied its own, unrewritten offer, so
/// an answer that strays from the one sent can still be applied: checking it
/// here keeps a codec this side cannot handle from being taken.
pub fn negotiated_encodings(
    answer: &str,
    offer: &str,
    mapping: &[TrackMappingEntry],
) -> HashMap<String, FrameEncoding> {
    let parsed = Sdp::parse(answer);
    let offered = Sdp::parse(offer);
    let declared = negotiates_encoded_step_frames(answer, offer);
    mapping
        .iter()
        .map(|entry| {
            let encoding = if declared && entry.kind == TrackKind::Video {
                parsed
                    .section(&entry.mid)
                    .filter(|section| section.is_video())
                    .and_then(Section::first_codec)
                    .filter(|codec| {
                        offered
                            .section(&entry.mid)
                            .is_some_and(|section| section.codecs().contains(codec))
                    })
                    .map_or(FrameEncoding::Raw, StepCodec::frame_encoding)
            } else {
                FrameEncoding::Raw
            };
            (entry.name.clone(), encoding)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::session::TrackDirection;

    /// An engine's offer: a send-only input video track, a receive-only
    /// output video track, an output audio track and the data channel.
    const OFFER: &str = "v=0\r\n\
o=- 1 2 IN IP4 127.0.0.1\r\n\
s=-\r\n\
t=0 0\r\n\
a=group:BUNDLE 0 1 2 3\r\n\
m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99 100 101 102 103 104\r\n\
a=mid:0\r\n\
a=sendonly\r\n\
a=rtpmap:96 VP8/90000\r\n\
a=rtcp-fb:96 nack\r\n\
a=rtpmap:97 rtx/90000\r\n\
a=fmtp:97 apt=96\r\n\
a=rtpmap:98 VP9/90000\r\n\
a=fmtp:98 profile-id=0\r\n\
a=rtpmap:99 rtx/90000\r\n\
a=fmtp:99 apt=98\r\n\
a=rtpmap:100 H264/90000\r\n\
a=fmtp:100 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n\
a=rtpmap:101 rtx/90000\r\n\
a=fmtp:101 apt=100\r\n\
a=rtpmap:102 AV1/90000\r\n\
a=rtpmap:103 rtx/90000\r\n\
a=fmtp:103 apt=102\r\n\
a=rtpmap:104 red/90000\r\n\
m=video 9 UDP/TLS/RTP/SAVPF 96 97 100 101\r\n\
a=mid:1\r\n\
a=recvonly\r\n\
a=rtpmap:96 VP8/90000\r\n\
a=rtpmap:97 rtx/90000\r\n\
a=fmtp:97 apt=96\r\n\
a=rtpmap:100 H264/90000\r\n\
a=rtpmap:101 rtx/90000\r\n\
a=fmtp:101 apt=100\r\n\
m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n\
a=mid:2\r\n\
a=recvonly\r\n\
a=rtpmap:111 opus/48000/2\r\n\
m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n\
a=mid:3\r\n";

    fn mapping() -> Vec<TrackMappingEntry> {
        vec![
            entry("camera", TrackKind::Video, TrackDirection::Sendonly, "0"),
            entry(
                "main_video",
                TrackKind::Video,
                TrackDirection::Recvonly,
                "1",
            ),
            entry(
                "main_audio",
                TrackKind::Audio,
                TrackDirection::Recvonly,
                "2",
            ),
        ]
    }

    fn entry(
        name: &str,
        kind: TrackKind,
        direction: TrackDirection,
        mid: &str,
    ) -> TrackMappingEntry {
        TrackMappingEntry {
            name: name.into(),
            kind,
            direction,
            mid: mid.into(),
        }
    }

    /// A side that encodes and decodes every step codec.
    fn every_codec() -> LocalStepCodecs {
        LocalStepCodecs {
            encode: StepCodec::DEFAULT_ORDER.to_vec(),
            decode: StepCodec::DEFAULT_ORDER.to_vec(),
        }
    }

    fn m_line(sdp: &str, mid: &str) -> String {
        Sdp::parse(sdp).section(mid).expect("section").lines[0].to_string()
    }

    #[test]
    fn a_stepped_offer_declares_the_attribute_and_prefers_h264_first() {
        let offer = stepped_offer(
            OFFER,
            &mapping(),
            &CodecPreferences::default(),
            &every_codec(),
        );

        let session_end = offer.find("m=").unwrap();
        assert!(offer[..session_end].contains("a=x-reactor-encoded-step-frames\r\n"));
        // The input track's section is there, its step codecs in the default
        // order, each followed later by what is not a step codec.
        assert_eq!(
            m_line(&offer, "0"),
            "m=video 9 UDP/TLS/RTP/SAVPF 100 98 102 96 97 99 101 103 104"
        );
        assert_eq!(
            m_line(&offer, "1"),
            "m=video 9 UDP/TLS/RTP/SAVPF 100 96 97 101"
        );
        // Audio and the data channel are left alone.
        assert_eq!(m_line(&offer, "2"), "m=audio 9 UDP/TLS/RTP/SAVPF 111");
        assert!(offer.contains("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:3\r\n"));
        assert!(offer.ends_with("\r\n"));
    }

    /// A preference orders a track's codecs, it does not limit them: the
    /// ones it leaves out follow, in the default order, with their RTX and
    /// attributes, so a runtime without the preferred ones answers with
    /// another.
    #[test]
    fn a_changed_preference_reorders_the_next_offer_and_keeps_the_other_codecs() {
        let mut preferences = CodecPreferences::default();
        let first = stepped_offer(OFFER, &mapping(), &preferences, &every_codec());
        assert!(m_line(&first, "0").ends_with(" 100 98 102 96 97 99 101 103 104"));

        preferences.set_session(Some(vec![StepCodec::Vp9, StepCodec::H264]));
        preferences.set_track("main_video", Some(vec![StepCodec::Vp8]));
        let next = stepped_offer(OFFER, &mapping(), &preferences, &every_codec());

        assert_eq!(
            m_line(&next, "0"),
            "m=video 9 UDP/TLS/RTP/SAVPF 98 100 102 96 97 99 101 103 104"
        );
        let input = &next[next.find("a=mid:0").unwrap()..next.find("a=mid:1").unwrap()];
        for kept in [
            "a=rtpmap:96 VP8",
            "a=fmtp:97 apt=96",
            "a=rtpmap:102 AV1",
            "a=fmtp:103 apt=102",
            "a=rtpmap:104 red",
        ] {
            assert!(input.contains(kept), "{kept} should stay");
        }
        // The output track's own preference wins over the session's.
        assert_eq!(
            m_line(&next, "1"),
            "m=video 9 UDP/TLS/RTP/SAVPF 96 100 97 101"
        );
    }

    /// A RED payload type that carries redundancy for a dropped codec goes
    /// with it, and with its RTX; one that carries only kept codecs stays.
    #[test]
    fn a_red_payload_type_for_a_dropped_codec_is_dropped_with_it() {
        let offer = OFFER
            .replace(
                "a=rtpmap:104 red/90000\r\n",
                "a=rtpmap:104 red/90000\r\n\
a=fmtp:104 96/96\r\n\
a=rtpmap:105 rtx/90000\r\n\
a=fmtp:105 apt=104\r\n\
a=rtpmap:106 red/90000\r\n\
a=fmtp:106 100/100\r\n",
            )
            .replace(
                "UDP/TLS/RTP/SAVPF 96 97 98 99 100 101 102 103 104\r\n",
                "UDP/TLS/RTP/SAVPF 96 97 98 99 100 101 102 103 104 105 106\r\n",
            );
        // This side encodes no VP8: the input section drops it.
        let local = LocalStepCodecs {
            encode: vec![StepCodec::H264, StepCodec::Vp9, StepCodec::Av1],
            ..every_codec()
        };
        let next = stepped_offer(&offer, &mapping(), &CodecPreferences::default(), &local);

        assert_eq!(
            m_line(&next, "0"),
            "m=video 9 UDP/TLS/RTP/SAVPF 100 98 102 99 101 103 106"
        );
        let input = &next[next.find("a=mid:0").unwrap()..next.find("a=mid:1").unwrap()];
        for gone in [
            "a=rtpmap:104 ",
            "a=fmtp:104 ",
            "a=rtpmap:105 ",
            "a=fmtp:105 ",
        ] {
            assert!(!input.contains(gone), "{gone} should be dropped");
        }
        assert!(input.contains("a=fmtp:106 100/100"));
    }

    /// The output section lists no AV1, the codec preferred: it offers the
    /// others it lists instead, and the input track takes AV1 first.
    #[test]
    fn a_preferred_codec_a_section_lacks_falls_back_to_the_others() {
        let mut preferences = CodecPreferences::default();
        preferences.set_session(Some(vec![StepCodec::Av1]));
        let offer = stepped_offer(OFFER, &mapping(), &preferences, &every_codec());
        assert!(offer.contains("a=x-reactor-encoded-step-frames\r\n"));
        assert_eq!(
            m_line(&offer, "0"),
            "m=video 9 UDP/TLS/RTP/SAVPF 102 100 98 96 97 99 101 103 104"
        );
        assert_eq!(
            m_line(&offer, "1"),
            "m=video 9 UDP/TLS/RTP/SAVPF 100 96 97 101"
        );
    }

    /// Each track keeps only the codecs this side handles in its direction:
    /// encode for the input, decode for the output.
    #[test]
    fn the_preference_is_filtered_by_what_this_side_encodes_and_decodes() {
        let local = LocalStepCodecs {
            encode: vec![StepCodec::Vp8, StepCodec::Vp9],
            decode: vec![StepCodec::Vp8],
        };
        let offer = stepped_offer(OFFER, &mapping(), &CodecPreferences::default(), &local);
        assert_eq!(
            m_line(&offer, "0"),
            "m=video 9 UDP/TLS/RTP/SAVPF 98 96 97 99 104"
        );
        assert_eq!(m_line(&offer, "1"), "m=video 9 UDP/TLS/RTP/SAVPF 96 97");

        // A side that decodes nothing cannot take encoded output frames, nor
        // one with no codec at all: the offer stays the engine's.
        let encode_only = LocalStepCodecs {
            encode: StepCodec::DEFAULT_ORDER.to_vec(),
            decode: vec![],
        };
        let preferences = CodecPreferences::default();
        assert_eq!(
            stepped_offer(OFFER, &mapping(), &preferences, &encode_only),
            OFFER
        );
        let none = LocalStepCodecs::default();
        assert_eq!(stepped_offer(OFFER, &mapping(), &preferences, &none), OFFER);
    }

    #[test]
    fn encoding_off_sends_the_engine_offer_unchanged() {
        let mut preferences = CodecPreferences::default();
        preferences.set_track("camera", Some(vec![StepCodec::Vp9]));
        preferences.set_session(Some(vec![]));
        assert!(!preferences.enabled());
        assert!(preferences.for_track("camera").is_empty());
        assert_eq!(
            stepped_offer(OFFER, &mapping(), &preferences, &every_codec()),
            OFFER
        );

        preferences.set_session(None);
        assert!(preferences.enabled());
        assert_eq!(preferences.for_track("camera"), &[StepCodec::Vp9]);
        preferences.set_track("camera", Some(vec![]));
        assert_eq!(preferences.for_track("camera"), &StepCodec::DEFAULT_ORDER);
    }

    #[test]
    fn a_preference_keeps_each_codec_once() {
        let mut preferences = CodecPreferences::default();
        preferences.set_session(Some(vec![StepCodec::Vp8, StepCodec::H264, StepCodec::Vp8]));
        assert_eq!(
            preferences.for_track("any"),
            &[StepCodec::Vp8, StepCodec::H264]
        );
        // The codecs it leaves out follow, in the default order.
        assert_eq!(
            preferences.order_for_track("any"),
            [
                StepCodec::Vp8,
                StepCodec::H264,
                StepCodec::Vp9,
                StepCodec::Av1
            ]
        );
        preferences.set_session(Some(vec![]));
        assert!(preferences.order_for_track("any").is_empty());
    }

    /// A runtime's answer to [`OFFER`], with or without the attribute.
    fn answer(declared: bool) -> String {
        let attribute = if declared {
            "a=x-reactor-encoded-step-frames\r\n"
        } else {
            ""
        };
        format!(
            "v=0\r\no=- 9 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n{attribute}\
m=video 9 UDP/TLS/RTP/SAVPF 98 99\r\na=mid:0\r\na=recvonly\r\n\
a=rtpmap:98 VP9/90000\r\na=rtpmap:99 rtx/90000\r\n\
m=video 9 UDP/TLS/RTP/SAVPF 100 101\r\na=mid:1\r\na=sendonly\r\n\
a=rtpmap:100 H264/90000\r\na=rtpmap:101 rtx/90000\r\n\
m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:2\r\na=sendonly\r\na=rtpmap:111 opus/48000/2\r\n\
m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:3\r\n"
        )
    }

    /// The offer sent with every codec this side handles.
    fn sent_offer() -> String {
        stepped_offer(
            OFFER,
            &mapping(),
            &CodecPreferences::default(),
            &every_codec(),
        )
    }

    #[test]
    fn each_track_takes_the_codec_its_answered_section_lists_first() {
        let encodings = negotiated_encodings(&answer(true), &sent_offer(), &mapping());
        assert_eq!(encodings["camera"], FrameEncoding::Vp9);
        assert_eq!(encodings["main_video"], FrameEncoding::H264);
        assert_eq!(encodings["main_audio"], FrameEncoding::Raw);
    }

    #[test]
    fn an_answer_without_the_attribute_leaves_every_track_raw() {
        let encodings = negotiated_encodings(&answer(false), &sent_offer(), &mapping());
        assert_eq!(encodings.len(), 3);
        assert!(encodings.values().all(|e| *e == FrameEncoding::Raw));
    }

    /// The answer is held to the offer sent: the attribute counts only when
    /// the offer declared it, and a codec only when the offer listed it.
    #[test]
    fn an_answer_straying_from_the_sent_offer_is_raw() {
        let encodings = negotiated_encodings(&answer(true), OFFER, &mapping());
        assert!(encodings.values().all(|e| *e == FrameEncoding::Raw));

        // VP9 for the input and H.264 for the output were dropped from the
        // offer sent, as codecs this side cannot handle there.
        let local = LocalStepCodecs {
            encode: vec![StepCodec::H264, StepCodec::Av1, StepCodec::Vp8],
            decode: vec![StepCodec::Vp9, StepCodec::Av1, StepCodec::Vp8],
        };
        let offer = stepped_offer(OFFER, &mapping(), &CodecPreferences::default(), &local);
        let encodings = negotiated_encodings(&answer(true), &offer, &mapping());
        assert_eq!(encodings["camera"], FrameEncoding::Raw);
        assert_eq!(encodings["main_video"], FrameEncoding::Raw);
    }

    #[test]
    fn whether_step_frames_may_be_encoded_is_decided_before_the_offer() {
        let tracks: Vec<TrackCapability> = mapping()
            .into_iter()
            .map(|entry| TrackCapability {
                name: entry.name,
                kind: entry.kind,
                direction: entry.direction,
            })
            .collect();
        let preferences = CodecPreferences::default();
        assert!(may_encode_step_frames(
            &tracks,
            &preferences,
            &every_codec()
        ));

        // An output this side cannot decode, an input it cannot encode, or
        // encoding off.
        let encode_only = LocalStepCodecs {
            encode: StepCodec::DEFAULT_ORDER.to_vec(),
            decode: vec![],
        };
        assert!(!may_encode_step_frames(&tracks, &preferences, &encode_only));
        let decode_only = LocalStepCodecs {
            encode: vec![],
            decode: StepCodec::DEFAULT_ORDER.to_vec(),
        };
        assert!(!may_encode_step_frames(&tracks, &preferences, &decode_only));
        // A preference this side has none of falls back to what it has.
        let mut vp8_only = CodecPreferences::default();
        vp8_only.set_track("camera", Some(vec![StepCodec::Vp8]));
        let h264 = LocalStepCodecs {
            encode: vec![StepCodec::H264],
            decode: vec![StepCodec::H264],
        };
        assert!(may_encode_step_frames(&tracks, &vp8_only, &h264));
        let mut off = CodecPreferences::default();
        off.set_session(Some(vec![]));
        assert!(!may_encode_step_frames(&tracks, &off, &every_codec()));
    }

    #[test]
    fn a_rejected_section_or_another_codec_is_raw() {
        let answer = answer(true)
            .replace(
                "m=video 9 UDP/TLS/RTP/SAVPF 98 99",
                "m=video 0 UDP/TLS/RTP/SAVPF 98 99",
            )
            .replace("a=rtpmap:100 H264/90000", "a=rtpmap:100 H265/90000");
        let encodings = negotiated_encodings(&answer, &sent_offer(), &mapping());
        assert_eq!(encodings["camera"], FrameEncoding::Raw);
        assert_eq!(encodings["main_video"], FrameEncoding::Raw);
    }
}
