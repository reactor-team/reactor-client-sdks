//! Exponential backoff schedule (pure, no timers).

use std::time::Duration;

/// Polling configuration shared by session polling and SDP-answer polling.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PollConfig {
    pub initial: Duration,
    pub max: Duration,
    pub multiplier: f64,
    pub max_attempts: u32,
}

impl PollConfig {
    /// Defaults used for `GET /sessions/{id}` readiness polling
    /// (200ms → 10s, 20 attempts), matching the JS SDK.
    pub fn session() -> Self {
        Self {
            initial: Duration::from_millis(200),
            max: Duration::from_secs(10),
            multiplier: 2.0,
            max_attempts: 20,
        }
    }

    /// Defaults used for SDP-answer polling (200ms → 15s, 6 attempts),
    /// matching the JS SDK.
    pub fn sdp() -> Self {
        Self {
            initial: Duration::from_millis(200),
            max: Duration::from_secs(15),
            multiplier: 2.0,
            max_attempts: 6,
        }
    }

    pub fn backoff(&self) -> Backoff {
        Backoff {
            next: self.initial,
            max: self.max,
            multiplier: self.multiplier,
        }
    }
}

/// Stateful exponential backoff: yields `initial`, then multiplies by
/// `multiplier` capped at `max`.
#[derive(Debug, Clone)]
pub struct Backoff {
    next: Duration,
    max: Duration,
    multiplier: f64,
}

impl Backoff {
    pub fn next_delay(&mut self) -> Duration {
        let current = self.next;
        let scaled = current.as_secs_f64() * self.multiplier;
        self.next = Duration::from_secs_f64(scaled.min(self.max.as_secs_f64()));
        current
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn doubles_until_capped() {
        let cfg = PollConfig {
            initial: Duration::from_millis(200),
            max: Duration::from_millis(1000),
            multiplier: 2.0,
            max_attempts: 10,
        };
        let mut b = cfg.backoff();
        assert_eq!(b.next_delay(), Duration::from_millis(200));
        assert_eq!(b.next_delay(), Duration::from_millis(400));
        assert_eq!(b.next_delay(), Duration::from_millis(800));
        assert_eq!(b.next_delay(), Duration::from_millis(1000));
        assert_eq!(b.next_delay(), Duration::from_millis(1000));
    }
}
