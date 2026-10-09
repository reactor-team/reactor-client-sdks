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

/// Time in milliseconds and frames counted, per track and stage.
type Totals = HashMap<(String, &'static str), (f64, u64)>;

/// The time each track's frames spent in each stage the SDK times, since the
/// last [`StageTimes::take`]. Frames are timed from the threads that move
/// them, so every access holds a lock.
#[derive(Default)]
pub struct StageTimes {
    totals: Mutex<Totals>,
}

impl StageTimes {
    pub fn new() -> Self {
        Self::default()
    }

    /// Add one frame of *track* that spent *ms* milliseconds in *stage*. A
    /// time that is negative or not finite is no measurement, and is left out.
    pub fn add(&self, track: &str, stage: &'static str, ms: f64) {
        if !ms.is_finite() || ms < 0.0 {
            return;
        }
        let mut totals = self.totals.lock().unwrap();
        let entry = totals.entry((track.to_string(), stage)).or_default();
        entry.0 += ms;
        entry.1 += 1;
    }

    /// Each track's stages since the previous take, and start a new window.
    pub fn take(&self) -> HashMap<String, Vec<FrameStage>> {
        let totals = std::mem::take(&mut *self.totals.lock().unwrap());
        let mut stages: HashMap<String, Vec<FrameStage>> = HashMap::new();
        for ((track, stage), (total_ms, frames)) in totals {
            stages.entry(track).or_default().push(FrameStage {
                name: stage.to_string(),
                total_ms,
                frames,
            });
        }
        stages
    }
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
