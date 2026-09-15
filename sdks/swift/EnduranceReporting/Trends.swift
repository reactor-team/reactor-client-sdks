import Foundation
#if canImport(Darwin)
    import Darwin
#endif

/// Pure trend/count assertions for the endurance-tests suite, plus the
/// `Sample`/`ResourceSampler` they operate on: given the raw numbers a run
/// already collected, decide pass/fail. No `Reactor`, no FFI, no live
/// service — mirrors `sdks/python/endurance-tests/trends.py`, split out of
/// the same module as `helpers.py` for the same reason: these are the one
/// place this suite's pass/fail *semantics* live, so they need to be
/// unit-testable directly (see `../EnduranceReportingTests/TrendsTests.swift`)
/// without pulling in `Reactor`/`CReactorFFI` the way `EnduranceTests`'s own
/// `Helpers.swift` (connect resilience, frame pumping) does.
///
/// `ResourceSampler` lives here too, not in `Helpers.swift` — unlike the
/// Python binding, this one needs no `reactor_sdk`-internal handle registry
/// (`_LIVE_CLIENTS`/`_ORPHANED_CALLBACKS`; see ../EnduranceTests/README.md's
/// "known scope gap" section) to read, only `task_info`/`task_threads`/
/// `getrusage`/`/dev/fd` — Darwin/Foundation APIs with no dependency on this
/// SDK's own native library, so there's no reason to put it on the FFI side
/// of the split at all.

/// One cycle's worth of process-wide resource usage. No `live_clients`/
/// `orphaned_callbacks` fields, unlike the Python suite's identical
/// dataclass — see this directory's own README for why this binding has no
/// equivalent signal to put here.
package struct Sample: Codable, Sendable {
    package let cycle: Int
    package let elapsedS: Double
    package let rssBytes: UInt64
    package let cpuS: Double
    /// % of one CPU core busy since the *previous* sample. 0 on the first
    /// sample, which has no previous one to diff against.
    package var cpuPercent: Double = 0
    package let numThreads: Int
    package let numFds: Int

    package init(
        cycle: Int, elapsedS: Double, rssBytes: UInt64, cpuS: Double, cpuPercent: Double = 0,
        numThreads: Int, numFds: Int
    ) {
        self.cycle = cycle
        self.elapsedS = elapsedS
        self.rssBytes = rssBytes
        self.cpuS = cpuS
        self.cpuPercent = cpuPercent
        self.numThreads = numThreads
        self.numFds = numFds
    }
}

/// Wall-clock driven, not a fixed iteration count — same reasoning as the
/// Python/C++ suites' identical knob: one number, shared by every scenario,
/// handles a quick manual sanity check today and an hours-long leak hunt (or
/// a future scheduled run) without any code changes.
package enum EnduranceConfig {
    package static var durationSeconds: Double {
        ProcessInfo.processInfo.environment["ENDURANCE_DURATION_SECONDS"]
            .flatMap(Double.init) ?? 300
    }

    /// How often `ResourceSampler.sample()` actually records a new `Sample`,
    /// at most — see that method's own comment for why. Mirrors
    /// `sdks/python/endurance-tests/helpers.py`'s
    /// `ENDURANCE_SAMPLE_INTERVAL_SECONDS` and
    /// `sdks/cpp/endurance-tests/helpers.cpp`'s
    /// `endurance_sample_interval_seconds()`.
    package static var sampleIntervalSeconds: Double {
        ProcessInfo.processInfo.environment["ENDURANCE_SAMPLE_INTERVAL_SECONDS"]
            .flatMap(Double.init) ?? 0.1
    }
}

/// Samples process-wide resource usage at most once per
/// `EnduranceConfig.sampleIntervalSeconds` of an endurance loop, against a
/// shared wall-clock deadline. Mirrors
/// `sdks/python/endurance-tests/helpers.py`'s `ResourceSampler` (minus the
/// two `reactor_sdk`-internal fields — see the module doc above) and
/// `sdks/cpp/endurance-tests/helpers.hpp`'s.
package final class ResourceSampler {

    private let start = ContinuousClock.now
    private let duration: Duration
    package private(set) var samples: [Sample] = []
    /// `nil`, not some sentinel `Instant`: the first call to `sample()` must
    /// always record (there is nothing yet to return in its place) — see
    /// `sample()`'s own check.
    private var lastSampleAt: ContinuousClock.Instant?

    package init(durationSeconds: Double = EnduranceConfig.durationSeconds) {
        duration = .seconds(durationSeconds)
    }

    package var deadlineReached: Bool {
        start.duration(to: .now) >= duration
    }

    /// Throttled to at most one real sample per
    /// `EnduranceConfig.sampleIntervalSeconds` — exists because a scenario
    /// with no network wait at all (e.g. pause-resume-churn:
    /// `PauseResumeChurnTests.swift`, no frames, no commands, no reconnect —
    /// `Track.pause()`/`resume()` round-trip through the FFI alone) iterates
    /// far faster than a network-bound scenario, and this method used to
    /// append one `Sample` per call unconditionally — the retained array
    /// itself then became the dominant cost the resource trend was supposed
    /// to be measuring instead of the SDK, the same false "RSS leak" the
    /// Python suite hit and fixed the same way (see
    /// `sdks/python/endurance-tests/helpers.py`'s
    /// `ENDURANCE_SAMPLE_INTERVAL_SECONDS`) and the C++ suite ported
    /// (`sdks/cpp/endurance-tests/helpers.cpp`'s
    /// `endurance_sample_interval_seconds()`). `samples` is never empty past
    /// the first call, so this only ever short-circuits from the second call
    /// on — the very first sample is always taken, unconditionally,
    /// regardless of the interval.
    @discardableResult
    package func sample(cycle: Int) -> Sample {
        let now = ContinuousClock.now
        if let last = lastSampleAt,
            last.duration(to: now) < .seconds(EnduranceConfig.sampleIntervalSeconds)
        {
            return samples[samples.count - 1]
        }
        lastSampleAt = now

        let elapsedS = start.duration(to: .now) / .seconds(1)
        var s = Sample(
            cycle: cycle, elapsedS: elapsedS, rssBytes: Self.readRSSBytes(),
            cpuS: Self.readCPUSeconds(), numThreads: Self.readNumThreads(),
            numFds: Self.readNumFDs())
        if let prev = samples.last {
            let dt = s.elapsedS - prev.elapsedS
            s.cpuPercent = dt > 0 ? 100 * (s.cpuS - prev.cpuS) / dt : 0
        }
        samples.append(s)
        return s
    }

    // MARK: - macOS resource readers
    //
    // Gated on canImport(Darwin), not just the module import at the top of
    // this file: SwiftPM compiles every target's sources before `swift
    // test`'s own --skip/--filter ever applies, so an EnduranceTests that
    // merely *imports* Darwin conditionally but calls Mach APIs
    // unconditionally still fails to compile the whole package on a
    // non-Darwin platform with a Swift toolchain installed — exactly the
    // "Linux contributor running `mise run test`" case scripts/swift.sh's
    // own comments describe as supported (caught by Codex review on PR
    // #171). The #else arm exists only to keep that compile green; nothing
    // calls it, since this suite only ever *runs* on macOS.

    #if canImport(Darwin)
        private static func readRSSBytes() -> UInt64 {
            var info = mach_task_basic_info()
            var count = mach_msg_type_number_t(
                MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
            let result = withUnsafeMutablePointer(to: &info) { pointer -> kern_return_t in
                pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                    task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
                }
            }
            guard result == KERN_SUCCESS else { return 0 }
            return UInt64(info.resident_size)
        }
    #else
        private static func readRSSBytes() -> UInt64 { 0 }
    #endif

    private static func readCPUSeconds() -> Double {
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        func seconds(_ tv: timeval) -> Double {
            Double(tv.tv_sec) + Double(tv.tv_usec) / 1_000_000
        }
        return seconds(usage.ru_utime) + seconds(usage.ru_stime)
    }

    #if canImport(Darwin)
        /// `task_threads` hands back a send right *per thread*, owned by
        /// this call — not just the array itself. Deallocating only the
        /// array (as several widely-copied "get thread count in Swift"
        /// snippets do) leaks one mach port per thread per sample: exactly
        /// the kind of native leak this suite exists to catch, injected by
        /// its own instrumentation.
        private static func readNumThreads() -> Int {
            var threadList: thread_act_array_t?
            var threadCount: mach_msg_type_number_t = 0
            let result = task_threads(mach_task_self_, &threadList, &threadCount)
            guard result == KERN_SUCCESS, let threadList else { return 0 }
            for i in 0..<Int(threadCount) {
                mach_port_deallocate(mach_task_self_, threadList[i])
            }
            vm_deallocate(
                mach_task_self_, vm_address_t(UInt(bitPattern: threadList)),
                vm_size_t(threadCount) * vm_size_t(MemoryLayout<thread_t>.stride))
            return Int(threadCount)
        }
    #else
        private static func readNumThreads() -> Int { 0 }
    #endif

    /// `/dev/fd` lists this process's open descriptors on macOS, the same
    /// role `/proc/self/fd` plays on Linux (see the C++ suite's identical
    /// reader) — and is itself just Foundation's cross-platform
    /// `FileManager`, unlike the Mach-specific readers above, so this one
    /// needs no platform gate to *compile* (only to be meaningful, which is
    /// out of scope on a platform this suite never runs on). Listing it
    /// opens one descriptor of its own, transiently — a constant,
    /// cycle-to-cycle offset that doesn't affect any check here (all of
    /// them compare against a baseline taken the same way).
    private static func readNumFDs() -> Int {
        (try? FileManager.default.contentsOfDirectory(atPath: "/dev/fd"))?.count ?? 0
    }
}

// MARK: - trend assertions

/// Per-interval CPU consumption, derived from `Sample.cpuS`. `cpuS` is
/// cumulative process CPU time since start — monotonically non-decreasing by
/// construction, so a trend check on the raw values would always report
/// growth regardless of whether anything is actually getting more expensive.
package func cpuDeltas(_ samples: [Sample]) -> [Double] {
    guard samples.count >= 2 else { return [] }
    return zip(samples, samples.dropFirst()).map { $1.cpuS - $0.cpuS }
}

/// Raised only for a test misconfiguration (not enough samples to compute a
/// trend at all) — never for a metric that crossed its threshold, which
/// returns a `MetricResult` with `status == "fail"` instead. Mirrors
/// Python's identical distinction in `assert_no_sustained_growth`'s own
/// docstring.
package struct EnduranceAssertionError: Error, CustomStringConvertible {
    package let description: String
    package init(_ description: String) { self.description = description }
}

/// Fail if `values` is *still climbing in the back half of the run*: its
/// last-third mean exceeds its middle-third mean (after dropping
/// `warmupFraction` to let one-time costs settle) by more than
/// `maxGrowthRatio`, *and* by more than `minAbsoluteDelta` in absolute
/// terms.
///
/// Deliberately last-vs-*middle*, not last-vs-first: a real CI run showed
/// RSS flat, then a one-time ramp to a new plateau roughly in the middle of
/// the window, then flat again — a native buffer/pool growing once to its
/// steady-state size, not an unbounded leak. Comparing against the *first*
/// third means exactly where that one ramp happens to land is what decides
/// pass/fail (whether the ramp's start lands inside the first-third window
/// at all), which is timing, not signal — the same total jump measured as a
/// comfortable pass or a razor-thin fail purely depending on when in the run
/// it occurred, as happened across two otherwise-similar CI runs. Comparing
/// the last third to the *middle* third instead asks the more direct
/// question — "did it keep growing after that", not "is the end higher than
/// the start" — so a one-time step that has already plateaued by the back
/// third reads as flat (no fail), while something still genuinely climbing
/// in the tail still trips it.
///
/// The trade-off: a real leak that's still only in its early,
/// slow-accelerating phase near the end of a short run could read as
/// "already flat" and pass here where a first-vs-last comparison might have
/// caught it. Preferred anyway — a slow leak has another cycle of this
/// suite (or a longer `ENDURANCE_DURATION_SECONDS` run) to get caught once
/// it's actually still climbing in *that* run's tail; a one-time step
/// misread as a leak fails a real PR or blocks a release on nothing.
///
/// `start`/`end`/`change` on the returned `MetricResult` (and the trend
/// string this prints) still show the true first-third-to-last-third
/// movement — the right "how much did this move overall" number for a human
/// reading the report — only the pass/fail decision itself is based on the
/// middle-vs-last comparison.
///
/// `useMedian: true` swaps the mean for a median within each window —
/// `numThreads` specifically has a documented one-cycle artifact (a cycle
/// that catches the previous cycle's native thread teardown still in
/// flight, briefly double-counting), and on a short run the "third" window
/// can be small enough that a single such cycle landing in the last window
/// skews its *mean* enough to misread as a sustained trend. A median
/// shrugs off one outlier as long as it isn't the majority of the window; a
/// real, sustained leak still moves the median just as surely as the mean.
///
/// Always prints one line verdict, pass or fail — not just on failure — so
/// a clean run still says *why* each signal looked fine.
///
/// Returns a `MetricResult` (status "ok"/"fail") rather than throwing: the
/// caller collects one from every check so a full report table can be built
/// even when one of them fails, instead of stopping at the first failure.
/// `n < 6` (not enough samples for a first/middle/last split at all) still
/// throws directly — that's a test misconfiguration
/// (`ENDURANCE_DURATION_SECONDS` too short), not a metric to report on.
package func assertNoSustainedGrowth(
    _ values: [Double], name: String, unit: String = "", maxGrowthRatio: Double,
    minAbsoluteDelta: Double = 0, warmupFraction: Double = 0.2, useMedian: Bool = false
) throws -> MetricResult {
    let n = values.count
    guard n >= 6 else {
        throw EnduranceAssertionError(
            "only \(n) \(name) sample(s) collected — raise ENDURANCE_DURATION_SECONDS to get "
                + "enough data for a trend")
    }
    let warmedUp = Array(values.dropFirst(Int(Double(n) * warmupFraction)))
    let third = max(1, warmedUp.count / 3)
    let first = Array(warmedUp.prefix(third))
    let middle = Array(warmedUp[third..<min(2 * third, warmedUp.count)])
    let last = Array(warmedUp.suffix(third))

    func mean(_ xs: [Double]) -> Double { xs.reduce(0, +) / Double(xs.count) }
    func median(_ xs: [Double]) -> Double {
        let sorted = xs.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
    }
    let average = useMedian ? median : mean
    let firstMean = average(first)
    let middleMean = average(middle.isEmpty ? first : middle)
    let lastMean = average(last)

    // What decides pass/fail — see the doc comment above for why this is
    // middle-vs-last, not first-vs-last.
    let tailDelta = lastMean - middleMean
    let tailRatio = middleMean != 0 ? tailDelta / middleMean : (tailDelta > 0 ? 1 : 0)
    let isLeak = tailDelta > minAbsoluteDelta && tailRatio > maxGrowthRatio

    // What's reported to a human — the overall first-to-last movement, a
    // different (and both useful) number from what decided the verdict
    // above.
    let overallDelta = lastMean - firstMean
    let overallRatio = firstMean != 0 ? overallDelta / firstMean : (overallDelta > 0 ? 1 : 0)

    let trend = String(
        format: "%.3f -> %.3f -> %.3f (overall %+.0f%%, tail %+.0f%%)", firstMean, middleMean,
        lastMean, overallRatio * 100, tailRatio * 100)
    let reason: String
    if isLeak {
        reason =
            "still climbing in the tail (last third \(String(format: "%+.0f%%", tailRatio * 100))"
            + " over the middle third) — over the \(Int(maxGrowthRatio * 100))% threshold, looks"
            + " like a real leak"
    } else if tailDelta <= minAbsoluteDelta {
        reason =
            "the \(String(format: "%.3g", tailDelta)) tail change is under the "
            + "\(String(format: "%.3g", minAbsoluteDelta)) floor, so it's noise (or an "
            + "already-settled one-time step)"
    } else {
        reason =
            "tail growth is under the \(Int(maxGrowthRatio * 100))% threshold — not still climbing"
    }
    print("[\(name)] \(isLeak ? "LEAK?" : "ok"): \(trend) — \(reason)")

    return MetricResult(
        name: name, start: firstMean, end: lastMean, change: overallDelta, unit: unit,
        status: isLeak ? "fail" : "ok", detail: reason,
        threshold:
            "still climbing: last third > \(Int(maxGrowthRatio * 100))% over middle third "
            + "(min \(String(format: "%.3g", minAbsoluteDelta)))",
        mid: middleMean)
}

/// Fail if `field` was ever nonzero, on *any* cycle — for a count that
/// should return to exactly 0 every time, a trend isn't the right test: a
/// leak on cycle 3 that happens to get cleaned up by cycle 40 is still a
/// real bug. Mirrors Python's `assert_always_zero`.
///
/// Always prints one line verdict, pass or fail. Returns a `MetricResult`
/// rather than throwing — see `assertNoSustainedGrowth`'s own doc comment
/// for why.
package func assertAlwaysZero(
    _ samples: [Sample], name: String, unit: String = "count", field: (Sample) -> Int
) -> MetricResult {
    let bad = samples.map { ($0.cycle, field($0)) }.filter { $0.1 != 0 }
    if let first = bad.first {
        let peak = bad.map(\.1).max() ?? first.1
        let reason =
            "nonzero on \(bad.count)/\(samples.count) cycles (first at cycle \(first.0): "
            + "\(first.1), peak \(peak)) — a handle leaked mid-run"
        print("[\(name)] LEAK?: \(reason)")
        // Reports the peak, not the final sample: if `field` already settled
        // back to 0 by the last cycle, using the final sample's value here
        // would render "went from 0 to 0 (+0)" in the Markdown/text reports
        // (which don't include `detail`), hiding the mid-run leak entirely.
        return MetricResult(
            name: name, start: 0, end: Double(peak), change: Double(peak), unit: unit,
            status: "fail", detail: reason, threshold: "always 0", firstBadCycle: first.0)
    }
    let lastValue = samples.last.map(field) ?? 0
    let reason = "stayed at exactly 0 across all \(samples.count) cycles"
    print("[\(name)] ok: \(reason)")
    return MetricResult(
        name: name, start: 0, end: Double(lastValue), change: Double(lastValue), unit: unit,
        status: "ok", detail: reason, threshold: "always 0")
}

/// Fail if `field(sample)`'s peak across `samples` ever exceeds its value on
/// the first sample — for a count expected to stay flat across the whole
/// run (not necessarily 0), whose teardown is synchronous enough that no
/// single cycle should ever catch it mid-flight. Mirrors Python's
/// `assert_never_grows`.
///
/// Always prints one line verdict, pass or fail. Returns a `MetricResult`
/// rather than throwing — see `assertNoSustainedGrowth`'s own doc comment
/// for why.
package func assertNeverGrows(
    _ samples: [Sample], name: String, unit: String = "count", field: (Sample) -> Int
) -> MetricResult {
    let baseline = field(samples[0])
    let peak = samples.map(field).max() ?? baseline
    if peak > baseline {
        let reason = "grew from \(baseline) to \(peak) during the run"
        print("[\(name)] LEAK?: \(reason)")
        return MetricResult(
            name: name, start: Double(baseline), end: Double(peak),
            change: Double(peak - baseline), unit: unit, status: "fail", detail: reason,
            threshold: "never above \(baseline)")
    }
    let reason = "never exceeded its starting value (\(baseline); peak seen was \(peak))"
    print("[\(name)] ok: \(reason)")
    return MetricResult(
        name: name, start: Double(baseline), end: Double(peak), change: Double(peak - baseline),
        unit: unit, status: "ok", detail: reason, threshold: "never above \(baseline)")
}

/// The RSS/CPU/thread/fd checks every scenario in this suite runs at the end
/// of its loop. Extracted so a new scenario is "write the loop body, call
/// this," not another copy of this same block — see this directory's own
/// README (its "Adding a new scenario" section) and the sdk-from-ffi skill's
/// "Endurance and leak tests" section for the shape this is meant to
/// support.
///
/// No `live_clients`/`orphaned_callbacks` checks here, unlike Python's
/// identical `standard_resource_metrics` (which also takes a
/// `live_clients_baseline_zero` flag) — this binding has no equivalent
/// signal to check in the first place; see ../EnduranceTests/README.md's
/// "known scope gap" section.
///
/// `fdsExact`: `true` when fd teardown is synchronous with this scenario's
/// own cycle boundary (only lifecycle-churn's disconnect()/close() per
/// cycle proved this in practice) — `numFds` must never exceed its starting
/// value (`assertNeverGrows`). `false` (the default) for anything that can
/// legitimately open a few more during warm-up and then plateau — `numFds`
/// gets the same trend-based check as RSS/CPU instead.
///
/// A scenario with its own extra invariants (publish-churn's
/// `track.published` check, pause-resume-churn's `pausedTracks` check)
/// still checks those itself, in its own loop — they are specific to what
/// that scenario exercises, not generic resource accounting, so they do not
/// belong here.
package func standardResourceMetrics(
    _ samples: [Sample], fdsExact: Bool = false
) throws
    -> [MetricResult]
{
    var metrics: [MetricResult] = []
    if fdsExact {
        metrics.append(assertNeverGrows(samples, name: "num_fds", field: \.numFds))
    }
    metrics.append(
        try assertNoSustainedGrowth(
            samples.map { Double($0.rssBytes) / 1e6 }, name: "rss", unit: "MB",
            maxGrowthRatio: 0.15, minAbsoluteDelta: 5.0))
    metrics.append(
        try assertNoSustainedGrowth(
            cpuDeltas(samples), name: "cpu_s_per_cycle", unit: "s", maxGrowthRatio: 0.5,
            minAbsoluteDelta: 0.05))
    metrics.append(
        try assertNoSustainedGrowth(
            samples.map(\.cpuPercent), name: "cpu_percent", unit: "%", maxGrowthRatio: 0.5,
            minAbsoluteDelta: 5.0))
    metrics.append(
        try assertNoSustainedGrowth(
            samples.map { Double($0.numThreads) }, name: "num_threads", unit: "count",
            maxGrowthRatio: 0.15, minAbsoluteDelta: 4, useMedian: true))
    if !fdsExact {
        metrics.append(
            try assertNoSustainedGrowth(
                samples.map { Double($0.numFds) }, name: "num_fds", unit: "count",
                maxGrowthRatio: 0.15, minAbsoluteDelta: 3))
    }
    return metrics
}
