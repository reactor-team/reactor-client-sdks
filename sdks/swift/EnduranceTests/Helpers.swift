import Foundation
import Reactor
import TestSupport
#if canImport(Darwin)
    import Darwin
#endif

/// Endurance-loop plumbing: duration handling, resource sampling, and the
/// trend/count assertions built on top of it. Mirrors
/// `sdks/python/endurance-tests/helpers.py` and
/// `sdks/cpp/endurance-tests/helpers.hpp`/`.cpp` — see the Python suite's own
/// README.md for the fuller rationale behind every signal here, and this
/// directory's own README.md for what's deliberately different in this
/// binding.
///
/// macOS-only: `task_info`/`task_threads`/`getrusage`/`/dev/fd` is how this
/// reads RSS/CPU/thread/fd counts, matching how this suite only ever runs on
/// `macos-latest` (see ../../../.github/workflows/endurance-tests.yml) — the
/// leak this suite hunts lives in the native FFI/WebRTC layer shared with
/// every other binding, not in anything iOS-simulator-specific, so unlike
/// `IntegrationTests` this deliberately has no iOS Simulator counterpart.

enum EnduranceConfig {
    /// Wall-clock driven, not a fixed iteration count — same reasoning as the
    /// Python/C++ suites' identical knob: one number, shared by every
    /// scenario, handles a quick manual sanity check today and an
    /// hours-long leak hunt (or a future scheduled run) without any code
    /// changes.
    static var durationSeconds: Double {
        ProcessInfo.processInfo.environment["ENDURANCE_DURATION_SECONDS"]
            .flatMap(Double.init) ?? 300
    }
}

struct Sample {
    let cycle: Int
    let elapsedS: Double
    let rssBytes: UInt64
    let cpuS: Double
    // % of one CPU core busy since the *previous* sample. 0 on the first
    // sample, which has no previous one to diff against.
    var cpuPercent: Double = 0
    // OS-level, not SDK-level — unlike the Python binding, this SDK has no
    // GC-driven handle registry to also check (`_LIVE_CLIENTS`/
    // `_ORPHANED_CALLBACKS`) — see README.md's "known scope gap" section.
    let numThreads: Int
    let numFDs: Int
}

/// Samples process-wide resource usage once per cycle of an endurance loop,
/// against a shared wall-clock deadline.
final class ResourceSampler {

    private let start = ContinuousClock.now
    private let duration: Duration
    private(set) var samples: [Sample] = []

    init(durationSeconds: Double = EnduranceConfig.durationSeconds) {
        duration = .seconds(durationSeconds)
    }

    var deadlineReached: Bool {
        start.duration(to: .now) >= duration
    }

    @discardableResult
    func sample(cycle: Int) -> Sample {
        let elapsedS = start.duration(to: .now) / .seconds(1)
        var s = Sample(
            cycle: cycle, elapsedS: elapsedS, rssBytes: Self.readRSSBytes(),
            cpuS: Self.readCPUSeconds(), numThreads: Self.readNumThreads(),
            numFDs: Self.readNumFDs())
        if let prev = samples.last {
            let dt = s.elapsedS - prev.elapsedS
            s.cpuPercent = dt > 0 ? 100 * (s.cpuS - prev.cpuS) / dt : 0
        }
        samples.append(s)
        return s
    }

    // The report table's columns, in plain language — see
    // sdks/cpp/endurance-tests/helpers.hpp's identical comment (kept in sync
    // across bindings) for the full column-by-column explanation.
    func printReport() {
        let deltas = cpuDeltas(samples)
        print(
            """

            \(pad("cycle", 6)) \(pad("elapsed_s", 10)) \(pad("ram_mb", 8)) \
            \(pad("cpu_s_per_cycle", 15)) \(pad("cpu_percent", 11)) \
            \(pad("num_threads", 11)) \(pad("num_fds", 7))
            """)
        for (i, s) in samples.enumerated() {
            let cpuPerCycle = i > 0 ? String(format: "%.3f", deltas[i - 1]) : "—"
            print(
                """
                \(pad(String(s.cycle), 6)) \(pad(String(format: "%.1f", s.elapsedS), 10)) \
                \(pad(String(format: "%.2f", Double(s.rssBytes) / 1e6), 8)) \
                \(pad(cpuPerCycle, 15)) \(pad(String(format: "%.1f", s.cpuPercent) + "%", 10)) \
                \(pad(String(s.numThreads), 11)) \(pad(String(s.numFDs), 7))
                """)
        }
    }

    private func pad(_ s: String, _ width: Int) -> String {
        s.count >= width ? s : String(repeating: " ", count: width - s.count) + s
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
func cpuDeltas(_ samples: [Sample]) -> [Double] {
    guard samples.count >= 2 else { return [] }
    return zip(samples, samples.dropFirst()).map { $1.cpuS - $0.cpuS }
}

struct EnduranceAssertionError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

/// Fail if `values`'s mean over the run's last third exceeds its mean over
/// the first third (after dropping `warmupFraction` to let one-time costs
/// settle) by more than `maxGrowthRatio`, *and* by more than
/// `minAbsoluteDelta` in absolute terms. Mirrors
/// `assert_no_sustained_growth` in the Python/C++ suites — see either's
/// docstring for the full reasoning, including `useMedian`.
///
/// Always prints one line verdict, pass or fail, so a clean run still says
/// *why* each signal looked fine.
func assertNoSustainedGrowth(
    _ values: [Double], name: String, maxGrowthRatio: Double, minAbsoluteDelta: Double = 0,
    warmupFraction: Double = 0.2, useMedian: Bool = false
) throws {
    let n = values.count
    guard n >= 6 else {
        throw EnduranceAssertionError(
            "only \(n) \(name) sample(s) collected — raise ENDURANCE_DURATION_SECONDS to get "
                + "enough data for a trend")
    }
    let warmedUp = Array(values.dropFirst(Int(Double(n) * warmupFraction)))
    let third = max(1, warmedUp.count / 3)
    let first = Array(warmedUp.prefix(third))
    let last = Array(warmedUp.suffix(third))

    func mean(_ xs: [Double]) -> Double { xs.reduce(0, +) / Double(xs.count) }
    func median(_ xs: [Double]) -> Double {
        let sorted = xs.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
    }
    let average = useMedian ? median : mean
    let firstMean = average(first)
    let lastMean = average(last)
    let delta = lastMean - firstMean
    let ratio = firstMean != 0 ? delta / firstMean : (delta > 0 ? 1 : 0)
    let isLeak = delta > minAbsoluteDelta && ratio > maxGrowthRatio

    let trend = String(format: "%.3f -> %.3f (%+.0f%%)", firstMean, lastMean, ratio * 100)
    let reason: String
    if isLeak {
        reason =
            "over the \(Int(maxGrowthRatio * 100))% growth threshold — looks like a real leak, not noise"
    } else if delta <= minAbsoluteDelta {
        reason = "under the \(minAbsoluteDelta) floor, so it's noise"
    } else {
        reason = "under the \(Int(maxGrowthRatio * 100))% growth threshold"
    }
    print("[\(name)] \(isLeak ? "LEAK?" : "ok"): \(trend) — \(reason)")

    if isLeak {
        throw EnduranceAssertionError(
            "\(name) grew \(Int(ratio * 100))% across the run (\(trend)) — \(reason)")
    }
}

/// Fail if `get(sample)`'s peak across `samples` ever exceeds its value on
/// the first sample — for a count expected to stay flat across the whole
/// run, *and* whose teardown is synchronous enough that no single cycle
/// should ever catch it mid-flight. Mirrors the Python/C++ suites'
/// identical `assert_never_grows` — kept for parity, though (like the C++
/// port) nothing sampled here has proven exact enough in a real run to use
/// it; see LifecycleChurnTests.swift's own comment on `numFDs`.
func assertNeverGrows(_ samples: [Sample], _ get: (Sample) -> Int, name: String) throws {
    let baseline = get(samples[0])
    let peak = samples.map(get).max() ?? baseline
    if peak > baseline {
        print("[\(name)] LEAK?: grew from \(baseline) to \(peak) during the run")
        throw EnduranceAssertionError("\(name) grew from \(baseline) to \(peak) during the run")
    }
    print("[\(name)] ok: never exceeded its starting value (\(baseline); peak seen was \(peak))")
}

// MARK: - connect resilience

/// Creates a `Reactor` (`IntegrationConfig.makeReactor`) and connects it
/// (`pacedConnect`), retrying the *whole* attempt up to `maxAttempts` times
/// with a fixed delay, before letting the last failure propagate.
///
/// Written after a real CI run lost ~20 minutes of a Python endurance
/// run's accumulated trend to one coordinator-side blip — the
/// token-exchange endpoint answering a single, isolated 503, confirmed
/// via Grafana as a one-off rather than a real outage. An
/// endurance run is long and unattended specifically so a human doesn't
/// have to babysit it; failing the whole run over a handful of seconds of
/// transient unavailability defeats that.
///
/// Wraps *construction*, not just `pacedConnect`: unlike the Python/C++
/// SDKs, where an API key is exchanged for a JWT lazily inside `connect()`,
/// this SDK's `Reactor(model:apiKey:...)` initializer does that exchange
/// itself (see `Auth.swift`) — so the failure this exists to absorb can be
/// thrown by `IntegrationConfig.makeReactor` before `pacedConnect` is ever
/// reached, not just by it. Retrying a *non*-transient failure (a
/// genuinely invalid key) just costs a few extra fast attempts before still
/// failing with the same error.
func makeAndConnectWithRetries(
    model: String = IntegrationConfig.defaultModel, maxAttempts: Int = 5,
    retryDelay: Duration = .seconds(5)
) async throws -> Reactor {
    for attempt in 1...maxAttempts {
        // Declared outside the `do`, not just a `let` inside it: if
        // `pacedConnect` throws *after* the coordinator already created a
        // session (a documented case in `pacedConnect`'s own doc — a
        // session that polled 20 times without ever reaching `ready`), the
        // reactor still has to be caught here to disconnect it before this
        // attempt is abandoned. `Reactor.close()` explicitly does not end
        // the session server-side, and letting `reactor` just fall out of
        // scope would only ever reach `deinit`'s `close()` — never
        // `disconnect()` — orphaning a session that then blocks the next
        // attempt/run from starting (caught by Codex review on PR #171).
        var reactor: Reactor?
        do {
            let created = try await IntegrationConfig.makeReactor(model: model)
            reactor = created
            try await pacedConnect(created)
            return created
        } catch {
            if let reactor {
                try? await reactor.disconnect()
                reactor.close()
            }
            guard attempt < maxAttempts else { throw error }
            try? await Task.sleep(for: retryDelay)
        }
    }
    fatalError("unreachable: the loop above always returns or throws on its last attempt")
}

/// Pushes solid-colour frames into `track` at ~30fps until `isReceived`
/// reports true (flipped by an `onFrame` handler registered on the
/// *receiving* track) or `timeout` elapses. Mirrors
/// `pump_until_frame_received` in the Python/C++ suites — best-effort and
/// never throws, for the same reason: this suite is about churn/leak
/// detection over many cycles, not frame-delivery correctness
/// (`IntegrationTests` already covers that with a real timeout-and-fail
/// `waitUntil`).
@discardableResult
func pumpUntilFrameReceived(
    _ track: Track, frame: Data, width: UInt32, height: UInt32, timeout: Duration = .seconds(2),
    fps: Double = 30, isReceived: () -> Bool
) async -> Bool {
    let deadline = ContinuousClock.now.advanced(by: timeout)
    while !isReceived() && ContinuousClock.now < deadline {
        try? track.pushFrame(frame, width: width, height: height)
        try? await Task.sleep(for: .seconds(1 / fps))
    }
    return isReceived()
}

/// The latest boolean a callback has flipped, safe to read from another
/// thread — the endurance suites' equivalent of
/// `02_UploadImageTests.swift`'s `LatestFrameBox`, generalised to a flag
/// rather than a frame: an `onFrame` handler runs `@Sendable` on the FFI's
/// own delivery thread, so a plain `var` a test reads back is a data race,
/// not just a stale answer.
final class AtomicFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false

    var value: Bool {
        lock.lock()
        defer { lock.unlock() }
        return flag
    }

    func set() {
        lock.lock()
        defer { lock.unlock() }
        flag = true
    }
}
