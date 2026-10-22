//! The input frames pushed between two steps, held to one message's worth.

use std::collections::VecDeque;
use std::sync::Mutex;

use crate::protocol::wire::v1::platform::TrackKind as WireTrackKind;
use crate::protocol::wire::v1::step::{Frame, TrackFrames};

/// Input frames pushed since the last step, per track.
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
}

#[derive(Default)]
struct Buffered {
    tracks: Vec<TrackFrames>,
    /// The track each buffered frame belongs to, oldest first.
    order: VecDeque<usize>,
    bytes: usize,
    /// Frames pushed out by the budget since the last take.
    dropped: usize,
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

    /// Push out the oldest frames until the buffer fits `max_bytes`. The
    /// newest frame stays even alone over budget: the step it rides on then
    /// fails as too large, which names the problem.
    fn trim(&mut self, max_bytes: usize) {
        while self.bytes > max_bytes && self.order.len() > 1 {
            let Some(index) = self.order.pop_front() else {
                break;
            };
            let frame = self.tracks[index].frames.remove(0);
            self.bytes -= frame_bytes(&frame);
            self.dropped += 1;
            // A track left with no frames goes too, so pushes to ever new track
            // names cannot grow the buffer either.
            if self.tracks[index].frames.is_empty() {
                let track = self.tracks.remove(index);
                self.bytes -= track_bytes(&track);
                for later in self.order.iter_mut().filter(|i| **i > index) {
                    *later -= 1;
                }
            }
        }
    }
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
    /// message of `max_bytes` can carry.
    pub fn push(&self, track: &str, kind: WireTrackKind, frame: Frame, max_bytes: usize) {
        let mut buffer = self.buffer.lock().unwrap();
        buffer.add(track, kind as i32, frame);
        buffer.trim(max_bytes.saturating_sub(REQUEST_STEP_ENVELOPE_BYTES));
    }

    /// Everything buffered, leaving the buffer empty.
    pub fn take(&self) -> Vec<TrackFrames> {
        let taken = std::mem::take(&mut *self.buffer.lock().unwrap());
        if taken.dropped > 0 {
            log::warn!(
                "dropped {} buffered step input frames, oldest first, to keep the step within one message",
                taken.dropped
            );
        }
        taken
            .tracks
            .into_iter()
            .filter(|t| !t.frames.is_empty())
            .collect()
    }

    pub fn clear(&self) {
        *self.buffer.lock().unwrap() = Buffered::default();
    }

    /// Number of frames buffered across all tracks.
    pub fn len(&self) -> usize {
        self.buffer.lock().unwrap().order.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
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

        let taken = inputs.take();
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

        let taken = inputs.take();
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

        let taken = inputs.take();
        assert_eq!(taken.len(), 1);
        assert_eq!(taken[0].track, "b");
    }
}
