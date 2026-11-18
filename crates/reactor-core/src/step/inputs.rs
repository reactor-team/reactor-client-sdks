//! The input frames pushed between two steps that could not be sent yet, held
//! to one message's worth.

use std::collections::{HashSet, VecDeque};
use std::sync::Mutex;

use crate::error::CoreError;
use crate::protocol::wire::v1::platform::TrackKind as WireTrackKind;
use crate::protocol::wire::v1::step::{Frame, FrameEncoding, TrackFrames};

/// Input frames pushed since the last step and not sent yet, per track.
///
/// A pushed frame goes to the runtime at once, in a `PushFrames`, when the
/// session is ready and its runtime takes them. Only frames that cannot go
/// yet are buffered here: pushed before the session is ready, or to a runtime
/// that takes input frames only inside a step. They are sent before the next
/// pushed frame, or with the next step as its `media_input`.
///
/// Tracks keep the order of their first buffered push, and frames the order
/// they were pushed in. [`StepInputs::take`] empties the buffer for a step.
///
/// The buffer holds at most one message's worth of frames, counted as they
/// encode inside a `RequestStep`: a frame pushed past that budget pushes out
/// the oldest ones, whatever their track, since a step can never send more
/// than one message. It runs inside the
/// app's process, where a camera pushing faster than steps run would
/// otherwise grow it without end.
#[derive(Default)]
pub struct StepInputs {
    buffer: Mutex<Buffered>,
    /// Input tracks whose next encoded frame must be a keyframe.
    keyframe_requests: Mutex<HashSet<String>>,
}

/// One track's entry for a frame sent on its own, in a `PushFrames`.
pub fn pushed_frame(track: &str, kind: WireTrackKind, frame: Frame) -> TrackFrames {
    TrackFrames {
        track: track.to_string(),
        kind: kind as i32,
        frames: vec![frame],
        // A pushed frame carries no rate of its own to declare.
        rate: 0.0,
    }
}

#[derive(Default)]
struct Buffered {
    tracks: Vec<TrackFrames>,
    /// The track each buffered frame belongs to, oldest first.
    order: VecDeque<usize>,
    bytes: usize,
    /// Frames pushed out by the budget since the last take.
    dropped: usize,
    /// Buffered tracks that lost an encoded frame to the budget since the last
    /// take. A track's encoded frames decode as one stream from its first, so
    /// what is left of them no longer starts at its keyframe. Only tracks still
    /// buffered are named, so it never outgrows `tracks`.
    cut_encoded: Vec<String>,
}

impl Buffered {
    fn add(&mut self, track: &str, kind: i32, frame: Frame) {
        let index = match self.tracks.iter().position(|t| t.track == track) {
            Some(index) => index,
            None => {
                let frames = TrackFrames {
                    track: track.to_string(),
                    kind,
                    frames: Vec::new(),
                    // A pushed frame carries no rate of its own to declare.
                    rate: 0.0,
                };
                self.bytes += track_bytes(&frames);
                self.tracks.push(frames);
                self.tracks.len() - 1
            }
        };
        self.bytes += frame_bytes(&frame);
        self.tracks[index].frames.push(frame);
        self.order.push_back(index);
    }

    /// Push out the oldest frames until the buffer fits `max_bytes`, and
    /// return the tracks that lost an encoded frame. The newest frame stays
    /// even alone over budget: the step it rides on then fails as too large,
    /// which names the problem.
    fn trim(&mut self, max_bytes: usize) -> Vec<String> {
        let mut evicted_encoded: Vec<String> = Vec::new();
        while self.bytes > max_bytes && self.order.len() > 1 {
            let Some(index) = self.order.pop_front() else {
                break;
            };
            let frame = self.tracks[index].frames.remove(0);
            self.bytes -= frame_bytes(&frame);
            self.dropped += 1;
            if is_encoded(&frame) {
                let track = &self.tracks[index].track;
                if !self.cut_encoded.contains(track) {
                    self.cut_encoded.push(track.clone());
                }
                if !evicted_encoded.contains(track) {
                    evicted_encoded.push(track.clone());
                }
            }
            // A track left with no frames goes too, so pushes to ever new track
            // names cannot grow the buffer either. Its cut goes with it: none of
            // the cut frames are left, and frames pushed to it afterwards start
            // a window of their own.
            if self.tracks[index].frames.is_empty() {
                let track = self.tracks.remove(index);
                self.bytes -= track_bytes(&track);
                self.cut_encoded.retain(|cut| *cut != track.track);
                for later in self.order.iter_mut().filter(|i| **i > index) {
                    *later -= 1;
                }
            }
        }
        evicted_encoded
    }
}

fn is_encoded(frame: &Frame) -> bool {
    !matches!(
        FrameEncoding::try_from(frame.encoding),
        Ok(FrameEncoding::Raw | FrameEncoding::Unspecified)
    )
}

/// Room a step's message needs besides its frames: the envelope, the request
/// id and the field headers around `media_input`. The state and commands are
/// not known until `step()`, so they are not reserved; a large state can still
/// make a step too large.
const REQUEST_STEP_ENVELOPE_BYTES: usize = 64;

/// What a frame costs inside its `TrackFrames`: its encoded size plus the
/// repeated field's tag and length prefix. Never zero, so an empty frame still
/// counts against the budget.
fn frame_bytes(frame: &Frame) -> usize {
    let len = prost::Message::encoded_len(frame);
    1 + prost::length_delimiter_len(len) + len
}

/// What a track's entry costs inside a `RequestStep` before its frames: its
/// own fields, its tag, and a length prefix as long as a message's can be.
fn track_bytes(frames: &TrackFrames) -> usize {
    1 + prost::length_delimiter_len(u32::MAX as usize) + prost::Message::encoded_len(frames)
}

impl StepInputs {
    pub fn new() -> Self {
        Self::default()
    }

    /// Buffer `frame` for the next step, keeping the frames within what a
    /// message of `max_bytes` can carry. A track whose encoded frames the
    /// budget pushes out starts its next frames at a keyframe, whether or not
    /// any of its frames are left: the frames after the gap reference ones
    /// that will never be sent.
    pub fn push(&self, track: &str, kind: WireTrackKind, frame: Frame, max_bytes: usize) {
        let evicted = {
            let mut buffer = self.buffer.lock().unwrap();
            buffer.add(track, kind as i32, frame);
            buffer.trim(max_bytes.saturating_sub(REQUEST_STEP_ENVELOPE_BYTES))
        };
        self.request_keyframes(&evicted);
    }

    /// Everything buffered, leaving the buffer empty.
    ///
    /// Refused as [`CoreError::StepRefused`], the buffer emptied all the same,
    /// when the budget pushed out the start of a track's encoded frames and
    /// some of them are left: those no longer decode, and sent as they are the
    /// runtime would refuse the step as undecodable without saying why.
    pub fn take(&self) -> Result<Vec<TrackFrames>, CoreError> {
        let taken = std::mem::take(&mut *self.buffer.lock().unwrap());
        if taken.dropped > 0 {
            log::warn!(
                "dropped {} buffered step input frames, oldest first, to keep the step within one message",
                taken.dropped
            );
        }
        let tracks: Vec<TrackFrames> = taken
            .tracks
            .into_iter()
            .filter(|t| !t.frames.is_empty())
            .collect();
        let cut = tracks
            .iter()
            .enumerate()
            .find(|(_, t)| taken.cut_encoded.contains(&t.track) && t.frames.iter().any(is_encoded));
        if let Some((index, track)) = cut {
            // None of the frames go: every encoded track starts again.
            self.request_keyframes(&Self::encoded_tracks(&tracks));
            return Err(CoreError::StepRefused {
                message: format!(
                    "the encoded frames of '{}' no longer start at their keyframe: frames \
                     pushed past one message's worth pushed out the oldest. Push a shorter \
                     window, or fewer frames alongside it",
                    track.track
                ),
                entry: Some(format!("media_input[{index}]")),
            });
        }
        Ok(tracks)
    }

    /// Drop every buffered frame. A track whose encoded frames go starts its
    /// next frames at a keyframe.
    pub fn clear(&self) {
        let cleared = std::mem::take(&mut *self.buffer.lock().unwrap());
        self.request_keyframes(&Self::encoded_tracks(&cleared.tracks));
    }

    /// The tracks of `tracks` that carry an encoded frame, in order.
    pub fn encoded_tracks(tracks: &[TrackFrames]) -> Vec<String> {
        let mut encoded: Vec<String> = Vec::new();
        for track in tracks {
            if track.frames.iter().any(is_encoded) && !encoded.contains(&track.track) {
                encoded.push(track.track.clone());
            }
        }
        encoded
    }

    /// Drop every buffered encoded frame, keeping the raw ones in push order,
    /// and return the tracks that lost frames. For a connection that closed:
    /// its encoded frames belong to streams the next connection starts over
    /// at keyframes, and decode on none of them.
    pub fn drop_encoded(&self) -> Vec<String> {
        let mut buffer = self.buffer.lock().unwrap();
        let old = std::mem::take(&mut *buffer);
        buffer.dropped = old.dropped;
        let mut tracks: Vec<(String, i32, VecDeque<Frame>)> = old
            .tracks
            .into_iter()
            .map(|t| (t.track, t.kind, t.frames.into()))
            .collect();
        let mut cut: Vec<String> = Vec::new();
        for index in old.order {
            let (track, kind, frames) = &mut tracks[index];
            let Some(frame) = frames.pop_front() else {
                continue;
            };
            if !is_encoded(&frame) {
                buffer.add(track, *kind, frame);
            } else if !cut.contains(track) {
                cut.push(track.clone());
            }
        }
        cut
    }

    /// Number of frames buffered across all tracks.
    pub fn len(&self) -> usize {
        self.buffer.lock().unwrap().order.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Mark `track` for a keyframe: the runtime could not decode its pushed
    /// frames and drops them up to the next keyframe.
    pub fn request_keyframe(&self, track: &str) {
        self.keyframe_requests
            .lock()
            .unwrap()
            .insert(track.to_string());
    }

    /// Mark each of `tracks` for a keyframe: frames encoded for them were not
    /// sent, so their streams have a gap.
    pub fn request_keyframes(&self, tracks: &[String]) {
        if tracks.is_empty() {
            return;
        }
        self.keyframe_requests
            .lock()
            .unwrap()
            .extend(tracks.iter().cloned());
    }

    /// Whether `track`'s next frames must start at a keyframe, clearing the
    /// mark: the one about to encode them asks, and starts at one.
    pub fn take_keyframe_request(&self, track: &str) -> bool {
        self.keyframe_requests.lock().unwrap().remove(track)
    }

    /// Forget every keyframe mark, for a new connection, whose first frames
    /// of each track start at a keyframe anyway.
    pub fn clear_keyframe_requests(&self) {
        self.keyframe_requests.lock().unwrap().clear();
    }
}

/// One encoder per input video track, for the frames pushed on it: each
/// track is one encoded stream for the whole connection, and every frame
/// references the one pushed before it.
///
/// A track's first frame on a connection is a keyframe: [`Self::reset`] drops
/// every encoder as the data channel closes, and a new encoder starts at one.
/// So is the frame after a keyframe request (the runtime could not decode the
/// track's frames, or frames encoded for it were never sent), and the first
/// frame in another codec or of another size.
#[cfg(feature = "webrtc-codecs")]
pub struct InputEncoders {
    codecs: reactor_webrtc::codec::VideoCodecs,
    tracks: Mutex<std::collections::HashMap<String, TrackEncoder>>,
}

#[cfg(feature = "webrtc-codecs")]
struct TrackEncoder {
    codec: super::StepCodec,
    encoder: reactor_webrtc::codec::VideoEncoder,
    /// Frames encoded so far, which time a frame pushed without a capture
    /// time.
    frames: i64,
}

/// Pixels pushed on an input video track, before encoding.
#[cfg(feature = "webrtc-codecs")]
pub struct PushedPicture<'a> {
    pub pixels: &'a [u8],
    pub width: u32,
    pub height: u32,
    pub layout: super::PixelLayout,
    pub metadata: Option<&'a [u8]>,
    pub capture_time_us: Option<i64>,
}

/// The interval a frame without a capture time is stamped at: 30 frames per
/// second, the rate the encoders spread their bitrate over.
#[cfg(feature = "webrtc-codecs")]
const UNTIMED_FRAME_US: i64 = 33_333;

#[cfg(feature = "webrtc-codecs")]
impl InputEncoders {
    pub fn new(codecs: reactor_webrtc::codec::VideoCodecs) -> Self {
        Self {
            codecs,
            tracks: Mutex::default(),
        }
    }

    /// Encode `picture` as the next frame of `track`'s stream in the codec
    /// `codec` names, a keyframe when `keyframe` says so, and hand it to
    /// `send` before the next frame of any track is encoded, so frames go out
    /// in the order they were encoded. `codec` and `keyframe` are asked with
    /// no other frame encoding, so a codec a teardown cleared under
    /// [`Self::reset`], or a mark set under [`Self::hold`], is seen by the
    /// next frame encoded after it.
    ///
    /// `Ok(None)` when `codec` names none (the connection negotiated none, or
    /// is gone): nothing is encoded, for the caller to send the frame raw.
    /// `Ok(Some(false))` when the encoder dropped the frame: nothing is sent,
    /// and the next frame still follows on from the last one sent.
    ///
    /// On an error nothing is sent, and the track's next frame is a keyframe.
    pub fn encode(
        &self,
        track: &str,
        codec: impl FnOnce() -> Option<super::StepCodec>,
        picture: &PushedPicture<'_>,
        keyframe: impl FnOnce() -> bool,
        send: impl FnOnce(Frame),
    ) -> Result<Option<bool>, reactor_webrtc::codec::CodecError> {
        use reactor_webrtc::codec::{CodecError, VideoEncoderConfig};
        use reactor_webrtc::PixelFormat;

        let PushedPicture {
            pixels,
            width,
            height,
            layout,
            metadata,
            capture_time_us,
        } = *picture;
        let (pixels, format) = match layout {
            super::PixelLayout::Bgra => (std::borrow::Cow::Borrowed(pixels), PixelFormat::Bgra),
            super::PixelLayout::Rgb => (std::borrow::Cow::Borrowed(pixels), PixelFormat::Rgb24),
            super::PixelLayout::Rgba => {
                let rgb = super::raw_video_frame(pixels, width, height, layout, None, None)
                    .ok_or_else(|| {
                        CodecError::InvalidInput(format!(
                            "{} bytes is not {width}x{height} RGBA",
                            pixels.len()
                        ))
                    })?;
                (std::borrow::Cow::Owned(rgb.data), PixelFormat::Rgb24)
            }
        };

        let mut tracks = self.tracks.lock().unwrap();
        let Some(codec) = codec() else {
            return Ok(None);
        };
        let entry = match tracks.get_mut(track) {
            Some(entry) if entry.codec == codec => entry,
            _ => {
                // A new stream, or one in another codec: a fresh encoder,
                // whose first frame is a keyframe.
                tracks.remove(track);
                let config = VideoEncoderConfig::new(codec.video_codec(), width, height);
                let encoder = self.codecs.encoder(&config)?;
                tracks.entry(track.to_string()).or_insert(TrackEncoder {
                    codec,
                    encoder,
                    frames: 0,
                })
            }
        };
        if keyframe() {
            entry.encoder.request_key_frame();
        }
        let timestamp_us = capture_time_us.unwrap_or(entry.frames * UNTIMED_FRAME_US);
        let encoded = match entry
            .encoder
            .encode(&pixels, width, height, format, timestamp_us)
        {
            Ok(encoded) => encoded,
            Err(error) => {
                // The next frame starts a fresh stream at a keyframe.
                tracks.remove(track);
                return Err(error);
            }
        };
        entry.frames += 1;
        let Some(encoded) = encoded else {
            return Ok(Some(false));
        };
        send(Frame {
            encoding: codec.frame_encoding() as i32,
            dtype: super::VIDEO_DTYPE.to_string(),
            shape: vec![height, width, 3],
            data: encoded.data,
            metadata: metadata.filter(|m| !m.is_empty()).map(<[u8]>::to_vec),
            capture_time_us,
            keyframe: encoded.key_frame,
        });
        Ok(Some(true))
    }

    /// Drop every track's encoder, as the data channel closes, and run `then`
    /// before any frame is encoded again: each track's first frame on the
    /// next connection is a keyframe. Never call it holding a lock `send`
    /// takes.
    pub fn reset<R>(&self, then: impl FnOnce() -> R) -> R {
        let mut tracks = self.tracks.lock().unwrap();
        tracks.clear();
        then()
    }

    /// Run `f` with no frame encoding, for dropping encoded frames not sent
    /// yet and marking their tracks for a keyframe as one step: no frame is
    /// encoded in between, on the references just dropped. Never call it
    /// holding a lock `send` takes, nor from inside `send`.
    pub fn hold<R>(&self, f: impl FnOnce() -> R) -> R {
        let _tracks = self.tracks.lock().unwrap();
        f()
    }

    /// Drop `track`'s encoder, as a frame encoded elsewhere goes out on it,
    /// and run `send`, which sends that frame, before any frame is encoded
    /// again: its next frame encoded here starts a fresh stream at a
    /// keyframe, behind the frame sent. Never call it holding a lock `send`
    /// takes, nor from inside `send`.
    pub fn restart<R>(&self, track: &str, send: impl FnOnce() -> R) -> R {
        let mut tracks = self.tracks.lock().unwrap();
        tracks.remove(track);
        send()
    }

    /// The tracks that have an encoder, sorted.
    #[cfg(test)]
    fn live_tracks(&self) -> Vec<String> {
        let mut tracks: Vec<_> = self.tracks.lock().unwrap().keys().cloned().collect();
        tracks.sort();
        tracks
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::step::raw;

    #[test]
    fn inputs_keep_push_order_per_track_and_take_empties() {
        let inputs = StepInputs::new();
        inputs.push(
            "a",
            WireTrackKind::Video,
            raw("uint8", vec![1], vec![1]),
            usize::MAX,
        );
        inputs.push(
            "b",
            WireTrackKind::Audio,
            raw("int16", vec![1, 1], vec![0, 0]),
            usize::MAX,
        );
        inputs.push(
            "a",
            WireTrackKind::Video,
            raw("uint8", vec![1], vec![2]),
            usize::MAX,
        );
        assert_eq!(inputs.len(), 3);

        let taken = inputs.take().unwrap();
        assert!(inputs.is_empty());
        assert_eq!(
            taken.iter().map(|t| t.track.as_str()).collect::<Vec<_>>(),
            ["a", "b"]
        );
        assert_eq!(taken[0].kind, WireTrackKind::Video as i32);
        assert_eq!(taken[1].kind, WireTrackKind::Audio as i32);
        assert_eq!(
            taken[0]
                .frames
                .iter()
                .map(|f| f.data.clone())
                .collect::<Vec<_>>(),
            [vec![1], vec![2]]
        );
    }

    #[test]
    fn pushing_past_the_budget_drops_the_oldest_frames_across_tracks() {
        let inputs = StepInputs::new();
        // Room for both tracks and 3 of their frames, each the same size.
        let track = |name: &str| TrackFrames {
            track: name.into(),
            kind: WireTrackKind::Video as i32,
            frames: vec![],
            rate: 0.0,
        };
        let max = REQUEST_STEP_ENVELOPE_BYTES
            + track_bytes(&track("a"))
            + track_bytes(&track("b"))
            + 3 * frame_bytes(&raw("uint8", vec![2], vec![0, 0]));
        inputs.push(
            "a",
            WireTrackKind::Video,
            raw("uint8", vec![2], vec![1, 1]),
            max,
        );
        inputs.push(
            "b",
            WireTrackKind::Video,
            raw("uint8", vec![2], vec![2, 2]),
            max,
        );
        inputs.push(
            "a",
            WireTrackKind::Video,
            raw("uint8", vec![2], vec![3, 3]),
            max,
        );
        inputs.push(
            "b",
            WireTrackKind::Video,
            raw("uint8", vec![2], vec![4, 4]),
            max,
        );
        assert_eq!(inputs.len(), 3);

        let taken = inputs.take().unwrap();
        let firsts =
            |track: &TrackFrames| track.frames.iter().map(|f| f.data[0]).collect::<Vec<_>>();
        assert_eq!(taken.len(), 2);
        assert_eq!((taken[0].track.as_str(), firsts(&taken[0])), ("a", vec![3]));
        assert_eq!(
            (taken[1].track.as_str(), firsts(&taken[1])),
            ("b", vec![2, 4])
        );
        assert!(inputs.is_empty());
    }

    #[test]
    fn pushes_to_ever_new_tracks_stay_within_the_budget() {
        let inputs = StepInputs::new();
        for n in 0..10_000 {
            inputs.push(
                &format!("track-{n}"),
                WireTrackKind::Video,
                raw("uint8", vec![1], vec![1]),
                1024,
            );
        }
        let buffer = inputs.buffer.lock().unwrap();
        assert!(buffer.tracks.len() < 1024, "{} tracks", buffer.tracks.len());
        assert!(buffer.bytes <= 1024, "{} bytes", buffer.bytes);
    }

    #[test]
    fn encoded_frames_cut_from_their_start_refuse_the_step() {
        let encoded = |byte: u8| Frame {
            encoding: FrameEncoding::H264 as i32,
            ..raw("uint8", vec![2], vec![byte, byte])
        };
        let track = TrackFrames {
            track: "cam".into(),
            kind: WireTrackKind::Video as i32,
            frames: vec![],
            rate: 0.0,
        };
        // Room for the track and 2 of its frames: the keyframe goes.
        let max = REQUEST_STEP_ENVELOPE_BYTES + track_bytes(&track) + 2 * frame_bytes(&encoded(0));
        let inputs = StepInputs::new();
        for byte in 1..=3 {
            inputs.push("cam", WireTrackKind::Video, encoded(byte), max);
        }
        assert!(
            inputs.take_keyframe_request("cam"),
            "asked for as the keyframe goes"
        );

        match inputs.take() {
            Err(CoreError::StepRefused { message, entry }) => {
                assert!(message.contains("'cam'"), "{message}");
                assert_eq!(entry.as_deref(), Some("media_input[0]"));
            }
            other => panic!("expected StepRefused, got {other:?}"),
        }
        assert!(inputs.is_empty());

        // Raw frames cut the same way leave a step that still makes sense.
        for byte in 1..=3 {
            inputs.push(
                "cam",
                WireTrackKind::Video,
                raw("uint8", vec![2], vec![byte; 2]),
                max,
            );
        }
        assert_eq!(inputs.take().unwrap()[0].frames.len(), 2);
    }

    #[test]
    fn a_track_whose_encoded_frames_were_all_pushed_out_takes_a_fresh_window() {
        let encoded = |byte: u8| Frame {
            encoding: FrameEncoding::H264 as i32,
            ..raw("uint8", vec![2], vec![byte, byte])
        };
        let track = |name: &str| TrackFrames {
            track: name.into(),
            kind: WireTrackKind::Video as i32,
            frames: vec![],
            rate: 0.0,
        };
        // Room for both tracks and 2 frames.
        let max = REQUEST_STEP_ENVELOPE_BYTES
            + track_bytes(&track("cam"))
            + track_bytes(&track("other"))
            + 2 * frame_bytes(&encoded(0));
        let inputs = StepInputs::new();
        inputs.push("cam", WireTrackKind::Video, encoded(0), max);
        // Raw frames on another track push all of cam's out.
        for byte in 1..=2 {
            inputs.push(
                "other",
                WireTrackKind::Video,
                raw("uint8", vec![2], vec![byte; 2]),
                max,
            );
        }
        assert!(inputs.buffer.lock().unwrap().cut_encoded.is_empty());
        // Its encoder is asked for a keyframe all the same: the frames it
        // encodes next would reference ones that will never be sent.
        assert!(inputs.take_keyframe_request("cam"));
        assert!(!inputs.take_keyframe_request("other"));
        // A fresh window on cam, from its own keyframe, is sent as it is.
        inputs.push("cam", WireTrackKind::Video, encoded(1), max);
        let taken = inputs.take().unwrap();
        let cam = taken
            .iter()
            .find(|t| t.track == "cam")
            .expect("cam is sent");
        assert_eq!(cam.frames.len(), 1);
    }

    #[test]
    fn encoded_pushes_to_ever_new_tracks_keep_no_cut_names() {
        let encoded = Frame {
            encoding: FrameEncoding::H264 as i32,
            ..raw("uint8", vec![1], vec![1])
        };
        let inputs = StepInputs::new();
        for n in 0..10_000 {
            inputs.push(
                &format!("track-{n}"),
                WireTrackKind::Video,
                encoded.clone(),
                1024,
            );
        }
        let buffer = inputs.buffer.lock().unwrap();
        assert!(
            buffer.cut_encoded.len() <= buffer.tracks.len(),
            "{} cut names for {} tracks",
            buffer.cut_encoded.len(),
            buffer.tracks.len()
        );
    }

    #[test]
    fn a_keyframe_request_is_taken_once_per_track() {
        let inputs = StepInputs::new();
        inputs.request_keyframe("cam");
        inputs.request_keyframe("cam");
        inputs.request_keyframe("depth");
        assert!(inputs.take_keyframe_request("cam"));
        assert!(!inputs.take_keyframe_request("cam"));
        assert!(!inputs.take_keyframe_request("other"));

        inputs.clear_keyframe_requests();
        assert!(!inputs.take_keyframe_request("depth"));
    }

    fn h264(byte: u8) -> Frame {
        Frame {
            encoding: FrameEncoding::H264 as i32,
            ..raw("uint8", vec![1], vec![byte])
        }
    }

    #[test]
    fn dropping_encoded_frames_keeps_the_raw_ones_in_push_order() {
        let inputs = StepInputs::new();
        inputs.push("cam", WireTrackKind::Video, h264(1), usize::MAX);
        inputs.push(
            "mic",
            WireTrackKind::Audio,
            raw("int16", vec![1, 1], vec![2, 0]),
            usize::MAX,
        );
        inputs.push(
            "cam",
            WireTrackKind::Video,
            raw("uint8", vec![1], vec![3]),
            usize::MAX,
        );
        inputs.push("depth", WireTrackKind::Video, h264(4), usize::MAX);
        inputs.push("cam", WireTrackKind::Video, h264(5), usize::MAX);

        assert_eq!(inputs.drop_encoded(), ["cam", "depth"]);
        assert_eq!(inputs.len(), 2);
        let taken = inputs.take().unwrap();
        let firsts: Vec<_> = taken
            .iter()
            .map(|t| (t.track.as_str(), t.frames[0].data[0]))
            .collect();
        assert_eq!(firsts, [("mic", 2), ("cam", 3)]);
        assert!(inputs.drop_encoded().is_empty());
    }

    #[test]
    fn dropped_encoded_frames_mark_their_tracks_for_a_keyframe() {
        let inputs = StepInputs::new();
        inputs.push("cam", WireTrackKind::Video, h264(1), usize::MAX);
        inputs.push(
            "raw",
            WireTrackKind::Video,
            raw("uint8", vec![1], vec![1]),
            usize::MAX,
        );
        inputs.clear();
        assert!(inputs.take_keyframe_request("cam"));
        assert!(!inputs.take_keyframe_request("raw"));

        // A step refused for a cut stream sends none of its frames.
        let track = TrackFrames {
            track: "cam".into(),
            kind: WireTrackKind::Video as i32,
            frames: vec![],
            rate: 0.0,
        };
        let max = REQUEST_STEP_ENVELOPE_BYTES + track_bytes(&track) + 2 * frame_bytes(&h264(0));
        for byte in 1..=3 {
            inputs.push("cam", WireTrackKind::Video, h264(byte), max);
        }
        assert!(inputs.take().is_err());
        assert!(inputs.take_keyframe_request("cam"));

        inputs.request_keyframes(&StepInputs::encoded_tracks(&[
            pushed_frame("a", WireTrackKind::Video, h264(1)),
            pushed_frame("b", WireTrackKind::Video, raw("uint8", vec![1], vec![1])),
        ]));
        assert!(inputs.take_keyframe_request("a"));
        assert!(!inputs.take_keyframe_request("b"));
    }

    #[test]
    fn a_pushed_frame_is_one_track_entry_without_a_rate() {
        let frame = raw("uint8", vec![1], vec![1]);
        let track = pushed_frame("cam", WireTrackKind::Video, frame.clone());
        assert_eq!(track.track, "cam");
        assert_eq!(track.kind, WireTrackKind::Video as i32);
        assert_eq!(track.frames, [frame]);
        assert_eq!(track.rate, 0.0);
    }

    #[test]
    fn a_frame_alone_over_budget_stays_and_a_track_left_empty_is_not_sent() {
        let inputs = StepInputs::new();
        inputs.push("a", WireTrackKind::Video, raw("uint8", vec![1], vec![1]), 4);
        inputs.push(
            "b",
            WireTrackKind::Video,
            raw("uint8", vec![8], vec![0; 8]),
            4,
        );
        assert_eq!(inputs.len(), 1);

        let taken = inputs.take().unwrap();
        assert_eq!(taken.len(), 1);
        assert_eq!(taken[0].track, "b");
    }
}

#[cfg(all(test, feature = "webrtc-codecs"))]
mod encoder_tests {
    use super::*;
    use crate::step::{FrameDecoders, LocalStepCodecs, PixelLayout, StepCodec};
    use reactor_webrtc::codec::VideoCodecs;

    fn picture<'a>(pixels: &'a [u8], layout: PixelLayout) -> PushedPicture<'a> {
        PushedPicture {
            pixels,
            width: 64,
            height: 48,
            layout,
            metadata: None,
            capture_time_us: None,
        }
    }

    /// Every codec this process both encodes and decodes.
    fn round_trip_codecs(codecs: &VideoCodecs) -> Vec<StepCodec> {
        let local = LocalStepCodecs::from_capabilities(&codecs.capabilities());
        local
            .encode
            .into_iter()
            .filter(|codec| local.decode.contains(codec))
            .collect()
    }

    fn encode(
        encoders: &InputEncoders,
        track: &str,
        codec: StepCodec,
        picture: &PushedPicture<'_>,
        keyframe: bool,
    ) -> Frame {
        let mut sent = None;
        assert_eq!(
            encoders
                .encode(
                    track,
                    || Some(codec),
                    picture,
                    || keyframe,
                    |frame| sent = Some(frame)
                )
                .unwrap(),
            Some(true)
        );
        sent.expect("an encoded frame is handed on")
    }

    /// Each track keeps its encoder: its first frame is a keyframe, the next
    /// follow on, a request or a reset starts it again at one, and BGRA, RGBA
    /// and RGB pixels encode alike.
    #[test]
    fn each_track_is_one_stream_from_its_keyframe() {
        let codecs = VideoCodecs::new().unwrap();
        for codec in round_trip_codecs(&codecs) {
            let encoders = InputEncoders::new(codecs.clone());
            let rgb = vec![90u8; 64 * 48 * 3];
            let rgba = vec![90u8; 64 * 48 * 4];
            let mut keyframes = vec![];
            for (track, layout, pixels, keyframe) in [
                ("a", PixelLayout::Rgb, &rgb, false),
                ("a", PixelLayout::Bgra, &rgba, false),
                ("b", PixelLayout::Rgba, &rgba, false),
                ("a", PixelLayout::Rgb, &rgb, true),
                ("a", PixelLayout::Rgb, &rgb, false),
            ] {
                let frame = encode(&encoders, track, codec, &picture(pixels, layout), keyframe);
                assert_eq!(frame.encoding, codec.frame_encoding() as i32);
                assert_eq!(frame.shape, [48, 64, 3]);
                keyframes.push(frame.keyframe);
            }
            assert_eq!(keyframes, [true, false, true, true, false], "{codec:?}");
            assert_eq!(encoders.live_tracks(), ["a", "b"]);

            encoders.reset(|| ());
            assert!(encoders.live_tracks().is_empty());
            let frame = encode(
                &encoders,
                "a",
                codec,
                &picture(&rgb, PixelLayout::Rgb),
                false,
            );
            assert!(frame.keyframe, "{codec:?}");
        }
    }

    /// The frames one track's encoder makes decode on one track decoder,
    /// back to the picture.
    #[test]
    fn encoded_frames_decode_back_on_a_track_decoder() {
        let codecs = VideoCodecs::new().unwrap();
        let round_trip = round_trip_codecs(&codecs);
        assert!(!round_trip.is_empty(), "no codec runs both ways here");
        #[cfg(target_vendor = "apple")]
        assert!(round_trip.contains(&StepCodec::H264), "{round_trip:?}");
        for codec in round_trip {
            let encoders = InputEncoders::new(codecs.clone());
            let mut decoders = FrameDecoders::default();
            decoders.register_webrtc(&codecs, &[codec]);
            for shade in [40u8, 80, 120] {
                let rgb = vec![shade; 64 * 48 * 3];
                let frame = encode(
                    &encoders,
                    "a",
                    codec,
                    &picture(&rgb, PixelLayout::Rgb),
                    false,
                );
                let decoded = decoders.decode_frame("a", frame).unwrap();
                assert_eq!(decoded.encoding, codec.frame_encoding());
                assert_eq!(decoded.shape, [48, 64, 3]);
                let off = decoded
                    .data
                    .iter()
                    .map(|v| u32::from(v.abs_diff(shade)))
                    .max()
                    .unwrap();
                assert!(off <= 8, "{codec:?} shade {shade}: off by {off}");
            }
        }
    }

    /// A picture the codec cannot take fails with nothing sent; the track's
    /// next frame starts at a keyframe.
    #[test]
    fn a_frame_the_encoder_refuses_sends_nothing() {
        let codecs = VideoCodecs::new().unwrap();
        let codec = round_trip_codecs(&codecs)[0];
        let encoders = InputEncoders::new(codecs);
        let odd = vec![0u8; 3 * 3 * 3];
        let refused = encoders.encode(
            "a",
            || Some(codec),
            &PushedPicture {
                width: 3,
                height: 3,
                ..picture(&odd, PixelLayout::Rgb)
            },
            || false,
            |_| panic!("nothing is sent"),
        );
        assert!(refused.is_err());
        let rgb = vec![0u8; 64 * 48 * 3];
        assert!(
            encode(
                &encoders,
                "a",
                codec,
                &picture(&rgb, PixelLayout::Rgb),
                false
            )
            .keyframe
        );
    }

    /// A frame a live encoder fails on drops the track's encoder: its next
    /// frame starts a fresh stream at a keyframe.
    #[test]
    fn a_frame_a_live_encoder_fails_on_restarts_the_track_at_a_keyframe() {
        let codecs = VideoCodecs::new().unwrap();
        let codec = round_trip_codecs(&codecs)[0];
        let encoders = InputEncoders::new(codecs);
        let rgb = vec![0u8; 64 * 48 * 3];
        encode(
            &encoders,
            "a",
            codec,
            &picture(&rgb, PixelLayout::Rgb),
            false,
        );
        encode(
            &encoders,
            "a",
            codec,
            &picture(&rgb, PixelLayout::Rgb),
            false,
        );
        let short = vec![0u8; 16];
        let failed = encoders.encode(
            "a",
            || Some(codec),
            &picture(&short, PixelLayout::Rgb),
            || false,
            |_| panic!("nothing is sent"),
        );
        assert!(failed.is_err());
        assert!(encoders.live_tracks().is_empty());
        let frame = encode(
            &encoders,
            "a",
            codec,
            &picture(&rgb, PixelLayout::Rgb),
            false,
        );
        assert!(frame.keyframe, "{codec:?}");
    }

    /// With no codec named, nothing is encoded nor sent, and no encoder made:
    /// the caller sends the frame raw.
    #[test]
    fn a_track_with_no_codec_named_encodes_nothing() {
        let encoders = InputEncoders::new(VideoCodecs::new().unwrap());
        let rgb = vec![0u8; 64 * 48 * 3];
        let encoded = encoders.encode(
            "a",
            || None,
            &picture(&rgb, PixelLayout::Rgb),
            || panic!("no keyframe is asked for"),
            |_| panic!("nothing is sent"),
        );
        assert_eq!(encoded.unwrap(), None);
        assert!(encoders.live_tracks().is_empty());
    }

    /// A track restarted for frames encoded elsewhere starts its next frame
    /// here at a keyframe.
    #[test]
    fn a_restarted_track_starts_at_a_keyframe() {
        let codecs = VideoCodecs::new().unwrap();
        let codec = round_trip_codecs(&codecs)[0];
        let encoders = InputEncoders::new(codecs);
        let rgb = vec![0u8; 64 * 48 * 3];
        encode(
            &encoders,
            "a",
            codec,
            &picture(&rgb, PixelLayout::Rgb),
            false,
        );
        let mut sent = false;
        encoders.restart("a", || sent = true);
        assert!(sent);
        let frame = encode(
            &encoders,
            "a",
            codec,
            &picture(&rgb, PixelLayout::Rgb),
            false,
        );
        assert!(frame.keyframe, "{codec:?}");
    }
}
