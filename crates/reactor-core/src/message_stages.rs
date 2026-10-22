//! Where a client's data-channel messages spend their time, stage by stage.
//!
//! A command's trip between the app and the model crosses several stages, like
//! a frame's (see [`crate::stage_times`]). Each client stats batch reports, for
//! the connection, the time the `"data"` channel's messages spent in each stage
//! since the previous batch (see `MessageStage` in the wire protocol). The
//! stages outside the engine the SDK times itself, command by command, in
//! [`MessageTimes`]. Each ends where the next starts, so none overlaps another:
//!
//! * `ffi_parse`: from the binding's call into the FFI to the command's
//!   arguments parsed from the binding's JSON. Only a binding that goes
//!   through the FFI reports it.
//! * `spawn`: from the arguments parsed to the core's `send_command()`
//!   starting on the async runtime. FFI only, as `ffi_parse`.
//! * `build`: from the core's `send_command()` starting to the command built
//!   as a protobuf message, its JSON arguments turned into a `Struct`.
//! * `encode`: from the command built to it being encoded, registered for its
//!   reply and handed to the data channel's send. The channel's `queue_wait`
//!   and `send` start there.
//! * `reply_decode`: from the core reading the reply off the data channel to
//!   the reply decoded and its request resolved.
//! * `delivery`: from the request resolved to `send_command()` returning the
//!   reply, its `Struct` turned back into JSON.
//! * `ffi_return`: from the core's `send_command()` returning to the FFI
//!   calling the binding back with the reply. FFI only, as `ffi_parse`.
//!
//! The SDK's stages count a command only once the data channel took it, so
//! every stage counts the same commands.
//!
//! The engine's stages come from the chunked channel's running totals, in
//! [`MessageStageReporter`]. For the messages this client sends: `enqueue`,
//! `queue_wait` and its parts `first_check`, `first_build` and
//! `first_native`, `send` and its part `rest_native`, and `stall`. For the
//! ones it receives: `reassembly`. A part names its stage in `part_of`. A
//! channel that does not chunk has no such totals, and its batches carry only
//! the SDK's stages.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use crate::peer::ChunkingTotals;
use crate::protocol::wire::v1::platform::MessageStage;

/// From the binding's FFI call to the command's arguments parsed.
pub const FFI_PARSE: &str = "ffi_parse";
/// From the arguments parsed to the core's `send_command()` starting.
pub const SPAWN: &str = "spawn";
/// From the core's `send_command()` starting to the command built.
pub const BUILD: &str = "build";
/// From the command built to it being handed to the data channel's send.
pub const ENCODE: &str = "encode";
/// From the reply read off the data channel to its request resolved.
pub const REPLY_DECODE: &str = "reply_decode";
/// From the request resolved to `send_command()` returning the reply.
pub const DELIVERY: &str = "delivery";
/// From the core's `send_command()` returning to the binding called back.
pub const FFI_RETURN: &str = "ffi_return";

// The engine's stages, from the chunked channel's totals (see the module
// docs), and the runtime's.
pub const ENQUEUE: &str = "enqueue";
pub const QUEUE_WAIT: &str = "queue_wait";
/// Part of [`QUEUE_WAIT`].
pub const FIRST_CHECK: &str = "first_check";
/// Part of [`QUEUE_WAIT`].
pub const FIRST_BUILD: &str = "first_build";
/// Part of [`QUEUE_WAIT`].
pub const FIRST_NATIVE: &str = "first_native";
pub const SEND: &str = "send";
/// Part of [`SEND`].
pub const REST_NATIVE: &str = "rest_native";
pub const STALL: &str = "stall";
pub const REASSEMBLY: &str = "reassembly";
pub const DISPATCH: &str = "dispatch";
pub const HANDLER: &str = "handler";

/// When a binding's call to send a command came into the FFI, and when the
/// FFI had parsed its arguments, on [`crate::Reactor::monotonic_ms`]'s clock.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CommandEntry {
    pub entered_ms: f64,
    pub parsed_ms: f64,
}

/// Every stage, in the order a command and its reply go through them, for
/// sorting a batch's stages. A side reports the stages it sees: the runtime
/// reports its own `reassembly`, `dispatch`, `handler` and its sends.
pub const MESSAGE_STAGES: &[&str] = &[
    FFI_PARSE,
    SPAWN,
    BUILD,
    ENCODE,
    ENQUEUE,
    QUEUE_WAIT,
    FIRST_CHECK,
    FIRST_BUILD,
    FIRST_NATIVE,
    SEND,
    REST_NATIVE,
    STALL,
    REASSEMBLY,
    DISPATCH,
    HANDLER,
    REPLY_DECODE,
    DELIVERY,
    FFI_RETURN,
];

/// The time the SDK's own stages took, since the last take. Commands are
/// timed from the tasks that send them, so the totals sit behind a lock.
#[derive(Default)]
pub struct MessageTimes {
    totals: Mutex<HashMap<&'static str, (f64, u64)>>,
    /// Which connection the totals belong to, as in
    /// [`crate::stage_times::StageTimes`]: only changed under the lock.
    generation: AtomicU64,
}

impl MessageTimes {
    pub fn new() -> Self {
        Self::default()
    }

    /// Start a new connection's window, and refuse commands of earlier ones.
    pub fn begin_connection(&self) {
        let mut totals = self.totals.lock().unwrap();
        totals.clear();
        self.generation.fetch_add(1, Ordering::AcqRel);
    }

    /// The current connection, to tag a command with when its timing starts.
    pub fn generation(&self) -> u64 {
        self.generation.load(Ordering::Acquire)
    }

    /// Add one command of connection *generation* that spent *ms*
    /// milliseconds in *stage*. A time that is negative or not finite, or a
    /// command of an earlier connection, is left out.
    pub fn add_from(&self, generation: u64, stage: &'static str, ms: f64) {
        if !ms.is_finite() || ms < 0.0 {
            return;
        }
        let mut totals = self.totals.lock().unwrap();
        if self.generation() != generation {
            return;
        }
        let entry = totals.entry(stage).or_default();
        entry.0 += ms;
        entry.1 += 1;
    }

    /// The stages since the previous take, and start a new window, for the
    /// reporter of connection *generation*. `None` once a newer connection
    /// has begun.
    pub fn take_from(&self, generation: u64) -> Option<Vec<MessageStage>> {
        let mut totals = self.totals.lock().unwrap();
        if self.generation() != generation {
            return None;
        }
        Some(
            std::mem::take(&mut *totals)
                .into_iter()
                .map(|(stage, (total_ms, messages))| MessageStage {
                    name: stage.to_string(),
                    total_ms,
                    messages,
                    part_of: String::new(),
                })
                .collect(),
        )
    }

    /// Put back stages taken for a batch that did not go out.
    pub fn give_back(&self, generation: u64, stages: Vec<MessageStage>) {
        let mut totals = self.totals.lock().unwrap();
        if self.generation() != generation {
            return;
        }
        for stage in stages {
            if let Some(name) = MESSAGE_STAGES.iter().find(|known| **known == stage.name) {
                let entry = totals.entry(name).or_default();
                entry.0 += stage.total_ms;
                entry.1 += stage.messages;
            }
        }
    }
}

/// Sort *stages* into trip order. A stage this SDK does not know goes last.
pub fn sort_message_stages(stages: &mut [MessageStage]) {
    stages.sort_by_key(|s| {
        MESSAGE_STAGES
            .iter()
            .position(|known| *known == s.name)
            .unwrap_or(MESSAGE_STAGES.len())
    });
}

/// The engine's stages timed in parts, by part: each part's time is within
/// its stage's, so adding both counts it twice.
const PARTS: &[(&str, &str)] = &[
    (FIRST_CHECK, QUEUE_WAIT),
    (FIRST_BUILD, QUEUE_WAIT),
    (FIRST_NATIVE, QUEUE_WAIT),
    (REST_NATIVE, SEND),
];

/// `rest_native` and `stall` seconds from windows in which no message
/// finished leaving, held for the next window that has one to spread them
/// over.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
struct Held {
    native_send_s: f64,
    stall_s: f64,
}

/// Turns the engine's running totals into a window's worth per batch, the way
/// [`crate::stats::ClientStatsReporter`] does for frame stages: the baseline
/// moves only when [`Self::commit`] says the batch went out.
#[derive(Default)]
pub struct MessageStageReporter {
    last: Option<ChunkingTotals>,
    held: Held,
    pending: Option<(Option<ChunkingTotals>, Held)>,
}

impl MessageStageReporter {
    pub fn new() -> Self {
        Self::default()
    }

    /// The engine's stages since the previous batch. Empty on the first
    /// batch, when the channel does not chunk, and when a count went
    /// backwards (a new channel).
    ///
    /// `stall` is the time the pump stopped, spread over the messages sent in
    /// the window, so its average is the stall time per message.
    /// `rest_native` is libwebrtc's send of every chunk but each message's
    /// first, spread the same way. Both run while a message is still leaving,
    /// so a window in which none finished holds them for the next one that
    /// has a message to spread them over.
    pub fn stages(&mut self, chunking: Option<ChunkingTotals>) -> Vec<MessageStage> {
        let mut held = self.held;
        let mut stages = Vec::new();
        let mut add = |name: &str, seconds: f64, messages: u64| {
            let total_ms = seconds * 1_000.0;
            // A time that is negative or not finite is no measurement.
            if total_ms.is_finite() && total_ms >= 0.0 {
                stages.push(MessageStage {
                    name: name.to_string(),
                    total_ms,
                    messages,
                    part_of: PARTS
                        .iter()
                        .find(|(part, _)| *part == name)
                        .map(|(_, stage)| stage.to_string())
                        .unwrap_or_default(),
                });
            }
        };
        match (chunking, self.last) {
            (Some(now), Some(before)) if now.messages_sent < before.messages_sent => {
                held = Held::default();
            }
            (Some(now), Some(before)) => {
                let sent = now.messages_sent - before.messages_sent;
                let native_send_s = now.native_send_s - before.native_send_s + held.native_send_s;
                let stall_s = now.stall_s - before.stall_s + held.stall_s;
                if sent == 0 {
                    held = Held {
                        native_send_s,
                        stall_s,
                    };
                } else {
                    held = Held::default();
                    let first_native_s = now.first_native_s - before.first_native_s;
                    for (name, seconds) in [
                        (ENQUEUE, now.enqueue_s - before.enqueue_s),
                        (QUEUE_WAIT, now.queue_wait_s - before.queue_wait_s),
                        (FIRST_CHECK, now.first_check_s - before.first_check_s),
                        (FIRST_BUILD, now.first_build_s - before.first_build_s),
                        (FIRST_NATIVE, first_native_s),
                        (SEND, now.send_s - before.send_s),
                        (REST_NATIVE, native_send_s - first_native_s),
                        (STALL, stall_s),
                    ] {
                        add(name, seconds, sent);
                    }
                }
            }
            _ => held = Held::default(),
        }
        if let (Some(now), Some(before)) = (chunking, self.last) {
            if now.messages_received > before.messages_received {
                add(
                    REASSEMBLY,
                    now.reassembly_s - before.reassembly_s,
                    now.messages_received - before.messages_received,
                );
            }
        }
        self.pending = Some((chunking, held));
        stages
    }

    /// Move the baseline to the readings of the last batch, once it went out.
    pub fn commit(&mut self) {
        if let Some((chunking, held)) = self.pending.take() {
            self.last = chunking;
            self.held = held;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn totals(sent: u64, queue_wait_s: f64, received: u64, reassembly_s: f64) -> ChunkingTotals {
        ChunkingTotals {
            messages_sent: sent,
            queue_wait_s,
            send_s: queue_wait_s * 2.0,
            stall_s: 0.0,
            messages_received: received,
            reassembly_s,
            ..Default::default()
        }
    }

    fn by_name<'a>(stages: &'a [MessageStage], name: &str) -> &'a MessageStage {
        stages.iter().find(|s| s.name == name).unwrap()
    }

    #[test]
    fn sdk_stages_add_up_until_taken() {
        let times = MessageTimes::new();
        let g = times.generation();
        times.add_from(g, BUILD, 0.2);
        times.add_from(g, BUILD, 0.4);
        times.add_from(g, DELIVERY, 0.1);
        times.add_from(g, DELIVERY, f64::NAN);

        let mut stages = times.take_from(g).unwrap();
        sort_message_stages(&mut stages);
        assert_eq!(stages[0].name, BUILD);
        assert_eq!(stages[0].messages, 2);
        assert!((stages[0].total_ms - 0.6).abs() < 1e-9);
        assert_eq!(by_name(&stages, DELIVERY).messages, 1);
        assert!(times.take_from(g).unwrap().is_empty());
    }

    #[test]
    fn a_command_of_an_earlier_connection_is_left_out() {
        let times = MessageTimes::new();
        let before = times.generation();
        times.begin_connection();
        times.add_from(before, BUILD, 1.0);
        assert!(times.take_from(before).is_none());
        assert!(times.take_from(times.generation()).unwrap().is_empty());
    }

    #[test]
    fn stages_given_back_reach_the_next_take() {
        let times = MessageTimes::new();
        let g = times.generation();
        times.add_from(g, BUILD, 1.0);
        let taken = times.take_from(g).unwrap();
        times.give_back(g, taken);
        times.add_from(g, BUILD, 2.0);
        let stages = times.take_from(g).unwrap();
        assert_eq!(stages[0].messages, 2);
        assert!((stages[0].total_ms - 3.0).abs() < 1e-9);
    }

    #[test]
    fn engine_stages_are_the_difference_between_two_batches() {
        let mut reporter = MessageStageReporter::new();
        assert!(reporter.stages(Some(totals(4, 0.010, 1, 0.0))).is_empty());
        reporter.commit();

        let stages = reporter.stages(Some(totals(14, 0.030, 3, 0.050)));
        let queue_wait = by_name(&stages, "queue_wait");
        assert_eq!(queue_wait.messages, 10);
        assert!((queue_wait.total_ms - 20.0).abs() < 1e-9);
        assert!((by_name(&stages, "send").total_ms - 40.0).abs() < 1e-9);
        let reassembly = by_name(&stages, "reassembly");
        assert_eq!(reassembly.messages, 2);
        assert!((reassembly.total_ms - 50.0).abs() < 1e-9);
    }

    #[test]
    fn a_batch_that_did_not_go_out_keeps_the_baseline() {
        let mut reporter = MessageStageReporter::new();
        reporter.stages(Some(totals(1, 0.0, 0, 0.0)));
        reporter.commit();
        reporter.stages(Some(totals(2, 0.001, 0, 0.0)));
        // Not committed: the next batch still covers both windows.
        let stages = reporter.stages(Some(totals(3, 0.002, 0, 0.0)));
        assert_eq!(by_name(&stages, "queue_wait").messages, 2);
    }

    #[test]
    fn no_chunking_or_a_counter_that_went_backwards_reports_nothing() {
        let mut reporter = MessageStageReporter::new();
        assert!(reporter.stages(None).is_empty());
        reporter.commit();
        reporter.stages(Some(totals(10, 1.0, 10, 1.0)));
        reporter.commit();
        assert!(reporter.stages(Some(totals(2, 0.1, 1, 0.1))).is_empty());
    }

    fn sending(sent: u64, native_send_s: f64, first_native_s: f64, stall_s: f64) -> ChunkingTotals {
        ChunkingTotals {
            messages_sent: sent,
            native_send_s,
            first_native_s,
            stall_s,
            ..Default::default()
        }
    }

    #[test]
    fn the_parts_name_their_stage() {
        let mut reporter = MessageStageReporter::new();
        reporter.stages(Some(sending(0, 0.0, 0.0, 0.0)));
        reporter.commit();

        let stages = reporter.stages(Some(sending(2, 0.003, 0.001, 0.0)));

        for (part, stage) in [
            ("first_check", "queue_wait"),
            ("first_build", "queue_wait"),
            ("first_native", "queue_wait"),
            ("rest_native", "send"),
        ] {
            assert_eq!(by_name(&stages, part).part_of, stage);
        }
        for stage in ["enqueue", "queue_wait", "send", "stall"] {
            assert_eq!(by_name(&stages, stage).part_of, "");
        }
        // Every chunk's native send but each message's first.
        assert!((by_name(&stages, "rest_native").total_ms - 2.0).abs() < 1e-9);
    }

    #[test]
    fn a_window_with_no_message_sent_holds_its_sends_and_stalls_for_the_next() {
        let mut reporter = MessageStageReporter::new();
        reporter.stages(Some(sending(1, 0.0, 0.0, 0.0)));
        reporter.commit();
        // A large message still leaving: chunks sent, a stall, none finished.
        assert!(reporter
            .stages(Some(sending(1, 0.004, 0.0, 0.002)))
            .is_empty());
        reporter.commit();

        let stages = reporter.stages(Some(sending(2, 0.006, 0.001, 0.003)));

        let rest = by_name(&stages, "rest_native");
        assert_eq!(rest.messages, 1);
        assert!((rest.total_ms - 5.0).abs() < 1e-9);
        assert!((by_name(&stages, "stall").total_ms - 3.0).abs() < 1e-9);
    }

    #[test]
    fn a_held_time_is_not_counted_again_after_it_went_out() {
        let mut reporter = MessageStageReporter::new();
        reporter.stages(Some(sending(1, 0.0, 0.0, 0.0)));
        reporter.commit();
        reporter.stages(Some(sending(1, 0.004, 0.0, 0.0)));
        reporter.commit();
        reporter.stages(Some(sending(2, 0.004, 0.0, 0.0)));
        reporter.commit();

        let stages = reporter.stages(Some(sending(3, 0.005, 0.0, 0.0)));

        assert!((by_name(&stages, "rest_native").total_ms - 1.0).abs() < 1e-9);
    }

    #[test]
    fn a_negative_time_is_left_out() {
        let mut reporter = MessageStageReporter::new();
        reporter.stages(Some(sending(1, 0.0, 0.0, 0.0)));
        reporter.commit();
        // first_native ahead of native_send: rest_native would be negative.
        let stages = reporter.stages(Some(sending(2, 0.001, 0.002, 0.0)));

        assert!(stages.iter().all(|s| s.name != "rest_native"));
        assert!(stages.iter().any(|s| s.name == "first_native"));
    }
}
