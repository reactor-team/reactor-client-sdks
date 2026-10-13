//! Where a client's frames spend their time, stage by stage.
//!
//! A frame's trip between the app and the model crosses several stages. Each
//! client stats batch reports, per track, the time the track's frames spent in
//! each stage since the previous batch (see `FrameStage` in the wire
//! protocol). Two stages happen outside the engine, so the SDK times them
//! itself, frame by frame, in [`StageTimes`]:
//!
//! * `submit`: from the app pushing a video frame to the engine taking it.
//! * `delivery`: from the decoder handing a frame over to the app's callback.
//!
//! The engine's own stages come from its running totals, in
//! [`crate::stats::ClientStatsReporter`].

use std::collections::HashMap;
use std::sync::Mutex;

use crate::protocol::wire::v1::platform::FrameStage;

/// The stage a sent frame spends being handed to the engine.
pub const SUBMIT: &str = "submit";
/// The stage a received frame spends going from the decoder to the app.
pub const DELIVERY: &str = "delivery";

/// Every stage, in the order a frame goes through them, for sorting a track's
/// stages into trip order.
pub const STAGES: &[&str] = &[
    SUBMIT,
    "encode_wait",
    "encode",
    "packetize",
    "pacer",
    "jitter_buffer",
    "decode",
    DELIVERY,
    "output_pacing",
    "output_queue",
];

/// Time in milliseconds and frames counted, per stage, per track. Keyed by
/// track first, so a frame of a known track is added without allocating.
type Totals = HashMap<String, HashMap<&'static str, (f64, u64)>>;

/// The time each track's frames spent in each stage the SDK times, since the
/// last [`StageTimes::take`]. Frames are timed from the threads that move
/// them, so every access holds a lock.
#[derive(Default)]
pub struct StageTimes {
    inner: Mutex<Inner>,
}

#[derive(Default)]
struct Inner {
    totals: Totals,
    /// Which connection the totals belong to, advanced by
    /// [`StageTimes::begin_connection`].
    generation: u64,
}

impl StageTimes {
    pub fn new() -> Self {
        Self::default()
    }

    /// Start a new connection's window: drop what earlier connections timed,
    /// and refuse their frames that are still on their way (see
    /// [`StageTimes::add_from`]).
    pub fn begin_connection(&self) {
        let mut inner = self.inner.lock().unwrap();
        inner.totals.clear();
        inner.generation = inner.generation.wrapping_add(1);
    }

    /// The current connection, to tag a frame with when its timing starts
    /// on one thread and ends on another.
    pub fn generation(&self) -> u64 {
        self.inner.lock().unwrap().generation
    }

    /// Add one frame of *track* that spent *ms* milliseconds in *stage*. A
    /// time that is negative or not finite is no measurement, and is left out.
    pub fn add(&self, track: &str, stage: &'static str, ms: f64) {
        let mut inner = self.inner.lock().unwrap();
        add_to(&mut inner.totals, track, stage, ms);
    }

    /// [`StageTimes::add`] for a frame tagged with *generation* when its
    /// timing started. A frame of an earlier connection, still queued when
    /// the next one began, is left out rather than counted in the new one.
    pub fn add_from(&self, generation: u64, track: &str, stage: &'static str, ms: f64) {
        let mut inner = self.inner.lock().unwrap();
        if inner.generation == generation {
            add_to(&mut inner.totals, track, stage, ms);
        }
    }

    /// Each track's stages since the previous take, and start a new window.
    pub fn take(&self) -> HashMap<String, Vec<FrameStage>> {
        std::mem::take(&mut self.inner.lock().unwrap().totals)
            .into_iter()
            .map(|(track, by_stage)| {
                let stages = by_stage
                    .into_iter()
                    .map(|(stage, (total_ms, frames))| FrameStage {
                        name: stage.to_string(),
                        total_ms,
                        frames,
                    })
                    .collect();
                (track, stages)
            })
            .collect()
    }
}

fn add_to(totals: &mut Totals, track: &str, stage: &'static str, ms: f64) {
    if !ms.is_finite() || ms < 0.0 {
        return;
    }
    let stages = match totals.get_mut(track) {
        Some(stages) => stages,
        None => totals.entry(track.to_string()).or_default(),
    };
    let entry = stages.entry(stage).or_default();
    entry.0 += ms;
    entry.1 += 1;
}

/// Sort *stages* into trip order. A stage this SDK does not know goes last.
pub fn sort_stages(stages: &mut [FrameStage]) {
    stages.sort_by_key(|s| {
        STAGES
            .iter()
            .position(|known| *known == s.name)
            .unwrap_or(STAGES.len())
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_add_up_per_track_and_stage_until_taken() {
        let times = StageTimes::new();
        times.add("webcam", SUBMIT, 0.03);
        times.add("webcam", SUBMIT, 0.05);
        times.add("main_video", DELIVERY, 0.01);

        let stages = times.take();

        let submit = &stages["webcam"][0];
        assert_eq!((submit.name.as_str(), submit.frames), (SUBMIT, 2));
        assert!((submit.total_ms - 0.08).abs() < 1e-9);
        assert_eq!(stages["main_video"][0].frames, 1);
        assert!(times.take().is_empty(), "a take starts a new window");
    }

    #[test]
    fn a_frame_from_an_earlier_connection_is_left_out() {
        let times = StageTimes::new();
        let before = times.generation();
        times.begin_connection();
        times.add_from(before, "main_video", DELIVERY, 2.0);
        times.add_from(times.generation(), "main_video", DELIVERY, 3.0);

        let stages = times.take();
        assert_eq!(stages["main_video"][0].frames, 1);
        assert!((stages["main_video"][0].total_ms - 3.0).abs() < 1e-9);
    }

    #[test]
    fn a_new_connection_drops_what_the_last_one_timed() {
        let times = StageTimes::new();
        times.add("webcam", SUBMIT, 1.0);
        times.begin_connection();
        assert!(times.take().is_empty());
    }

    #[test]
    fn a_time_that_is_no_measurement_is_left_out() {
        let times = StageTimes::new();
        times.add("webcam", SUBMIT, -1.0);
        times.add("webcam", SUBMIT, f64::NAN);

        assert!(times.take().is_empty());
    }

    #[test]
    fn stages_sort_into_trip_order_with_an_unknown_one_last() {
        let stage = |name: &str| FrameStage {
            name: name.to_string(),
            total_ms: 1.0,
            frames: 1,
        };
        let mut stages = vec![
            stage("custom"),
            stage(DELIVERY),
            stage("decode"),
            stage("jitter_buffer"),
        ];

        sort_stages(&mut stages);

        let names: Vec<&str> = stages.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(names, ["jitter_buffer", "decode", DELIVERY, "custom"]);
    }
}
