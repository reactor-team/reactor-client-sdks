import Foundation

/// Reporting layer for the endurance-tests suite: turns the raw
/// `Sample`/`MetricResult` data a scenario already collects into a compact
/// live status block, and — via `writeReports(_:outDir:)` — a JSON dump, a
/// Markdown report, and a plain-text summary, all three rendered from the
/// same `RunResult` so there is exactly one source of truth instead of
/// formats that can drift apart. Mirrors
/// `sdks/python/endurance-tests/report.py`.
///
/// Deliberately has no dependency on `Reactor`/`CReactorFFI` (this whole
/// target, `EnduranceReporting`, does not link them) — keeping this module
/// FFI-free means its own unit tests
/// (`../EnduranceReportingTests/ReportTests.swift`) never need a live
/// service or a built native library, only a `RunResult` built by hand.

/// Same on/off parsing as `EnduranceConfig.durationSeconds` — one env var,
/// no new CLI surface. Purpose is debugging a short run, not normal
/// long-running execution — see ../EnduranceTests/README.md.
package enum EnduranceVerbose {
    package static var isEnabled: Bool {
        let raw = ProcessInfo.processInfo.environment["ENDURANCE_VERBOSE"] ?? ""
        return !["", "0", "false", "False"].contains(raw)
    }
}

/// How often the compact live block reprints during a run. 30s is frequent
/// enough that opening a GitHub Actions log mid-run always shows something
/// recent, and sparse enough that a multi-hour run doesn't scroll a huge
/// log.
private let liveIntervalSeconds: Double =
    ProcessInfo.processInfo.environment["ENDURANCE_LIVE_INTERVAL_SECONDS"].flatMap(Double.init)
    ?? 30

/// `swift test`/`swift build` for this package only ever run from the
/// repository root — `Package.swift` lives there, not under `sdks/swift/`
/// (see its own header comment) — so a plain relative `"endurance-results"`
/// would land at the repo root instead of beside this suite, unlike
/// `sdks/python/endurance-tests/`'s identical default (pytest is invoked
/// with `sdks/python` as its own working directory). Hardcoded relative to
/// the repo root instead, to land exactly where `.github/workflows/
/// endurance-tests.yml`'s `swift` job (`working-directory: sdks/swift`)
/// and this directory's own README already expect it.
package let defaultEnduranceResultsDir = URL(fileURLWithPath: "sdks/swift/endurance-results")

package func nowISO() -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.string(from: Date())
}

/// Best-effort commit SHA for the report header. `GITHUB_SHA` (set by every
/// GitHub Actions run) first, falling back to `git rev-parse HEAD` for a
/// local run — never throws, since a missing commit SHA is fine to just
/// omit.
///
/// The `git rev-parse` fallback is macOS-only (`#if os(macOS)`), not just
/// `canImport(Darwin)`: `Foundation.Process` does not exist on iOS at all
/// (no subprocess spawning on that platform), and this whole target is
/// compiled — never run, but compiled — for the iOS Simulator too, as part
/// of the same package graph `swift-integration-tests-ios-simulator`
/// resolves for `IntegrationTests` (this suite itself stays macOS-only; see
/// ../EnduranceTests/README.md). Missing this gate is a build failure on
/// every iOS Simulator CI run, not just a runtime no-op, since `EnduranceTests`
/// only ever *runs* on macOS but still has to *build* everywhere the package
/// does.
package func gitCommitSHA() -> String? {
    if let sha = ProcessInfo.processInfo.environment["GITHUB_SHA"], !sha.isEmpty {
        return sha
    }
    #if os(macOS)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["git", "rev-parse", "HEAD"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = Pipe()
        do {
            try process.run()
            process.waitUntilExit()
            guard process.terminationStatus == 0 else { return nil }
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let sha = String(data: data, encoding: .utf8)?.trimmingCharacters(
                in: .whitespacesAndNewlines)
            return (sha?.isEmpty ?? true) ? nil : sha
        } catch {
            return nil
        }
    #else
        return nil
    #endif
}

package func formatDuration(_ seconds: Double) -> String {
    let total = max(0, Int(seconds))
    let h = total / 3600
    let m = (total % 3600) / 60
    let s = total % 60
    if h > 0 { return "\(h)h \(String(format: "%02d", m))m" }
    if m > 0 { return "\(m)m \(String(format: "%02d", s))s" }
    return "\(s)s"
}

private func formatValue(_ value: Double, unit: String) -> String {
    switch unit {
    case "MB": return String(format: "%.1f MB", value)
    case "%": return String(format: "%.1f%%", value)
    case "count": return String(format: "%.0f", value)
    case "s": return String(format: "%.3fs", value)
    default: return String(format: "%.3f", value)
    }
}

private func formatDelta(_ value: Double, unit: String) -> String {
    let sign = value >= 0 ? "+" : ""
    return "\(sign)\(formatValue(value, unit: unit))"
}

/// One row of the report table — the start/mid/end/change/status of a
/// single measured signal, built by the `assert*` functions in
/// `Trends.swift` instead of them throwing immediately, so a full table
/// (not just the first failing metric) can be rendered even when something
/// failed. Mirrors Python's `MetricResult`.
package struct MetricResult: Codable, Sendable {
    package let name: String
    package let start: Double
    package let end: Double
    package let change: Double
    /// "MB" | "%" | "count" | "s" | ""
    package let unit: String
    /// "ok" | "fail"
    package let status: String
    package let detail: String
    package var threshold: String?
    /// Cycle number of the first bad sample, when the underlying check
    /// tracks one (`assertAlwaysZero` does; a start/end trend check doesn't
    /// have a single meaningful "first" cycle). Left `nil` rather than
    /// guessed.
    package var firstBadCycle: Int?
    /// The run's middle-third mean — the same number
    /// `assertNoSustainedGrowth` already computes and decides pass/fail
    /// from. `nil` for the exact always-zero/never-grows checks, which have
    /// no middle-third concept.
    package var mid: Double?

    package init(
        name: String, start: Double, end: Double, change: Double, unit: String, status: String,
        detail: String, threshold: String? = nil, firstBadCycle: Int? = nil, mid: Double? = nil
    ) {
        self.name = name
        self.start = start
        self.end = end
        self.change = change
        self.unit = unit
        self.status = status
        self.detail = detail
        self.threshold = threshold
        self.firstBadCycle = firstBadCycle
        self.mid = mid
    }

    package var emoji: String { status == "ok" ? "🟢" : "🔴" }
    package var statusLabel: String { status == "ok" ? "Stable" : "FAILED" }

    /// Change from the midpoint to the end — the actual quantity a trend
    /// check's pass/fail is based on, as opposed to `change` (start-to-end),
    /// which can look like meaningful growth even on a healthy run: a
    /// one-time warm-up ramp moves `start`→`end` but not `mid`→`end`. `nil`
    /// when `mid` itself is `nil`.
    package var midChange: Double? { mid.map { end - $0 } }

    package func rowMarkdown() -> String {
        let midStr = mid.map { formatValue($0, unit: unit) } ?? "—"
        let midChangeStr = midChange.map { formatDelta($0, unit: unit) } ?? "—"
        return
            "| \(name) | \(formatValue(start, unit: unit)) | \(midStr) | "
            + "\(formatValue(end, unit: unit)) | \(midChangeStr) | \(emoji) \(statusLabel) |"
    }

    package func rowText() -> String {
        let midStr = mid.map { formatValue($0, unit: unit) } ?? "—"
        let midChangeStr = midChange.map { formatDelta($0, unit: unit) } ?? "—"
        let namePad = name.padding(toLength: max(name.count, 20), withPad: " ", startingAt: 0)
        return "  \(namePad) \(formatValue(start, unit: unit)) -> \(midStr) -> "
            + "\(formatValue(end, unit: unit))  \(midChangeStr)  \(emoji) \(statusLabel)"
    }
}

/// A handful of evenly-spaced snapshots across a run — see
/// `computeCheckpoints(_:n:)`'s own doc for why this exists alongside the
/// Results table's first-third/last-third means.
package struct Checkpoint: Codable, Sendable {
    package let pct: Int
    package let cycle: Int?
    package let elapsedS: Double?
    package let rssMB: Double?
    package let numThreads: Int?
    package let numFds: Int?
    package let cpuPercent: Double?
}

/// `n` evenly-spaced snapshots across `samples` (by index, not by time —
/// sampling is already roughly evenly spaced in wall-clock time since each
/// cycle takes comparable work).
///
/// Exists because the trend assertions in `Trends.swift` (and the report's
/// own Results table) only ever compare the *mean of the first third* to
/// the *mean of the last third* — that's the right check for "did this end
/// up somewhere worse than it started", but it collapses the actual shape
/// of a metric over time into two numbers. A metric that ramped for one
/// middle stretch and plateaued (a buffer/pool growing once to its
/// steady-state size, then holding) reads identically in that table to one
/// that climbed steadily the whole run — and those mean very different
/// things for whether something is actually still leaking. This is the
/// smallest addition that lets a report (or an agent reading the JSON) tell
/// those apart without reaching for the full `samples` list and writing a
/// bucketing script by hand.
package func computeCheckpoints(_ samples: [Sample], n: Int = 5) -> [Checkpoint] {
    guard !samples.isEmpty else { return [] }
    let lastIdx = samples.count - 1
    return (0..<n).map { i in
        let pct = n > 1 ? Double(i) / Double(n - 1) : 0
        let s = samples[Int((pct * Double(lastIdx)).rounded())]
        return Checkpoint(
            pct: Int((pct * 100).rounded()), cycle: s.cycle, elapsedS: s.elapsedS,
            rssMB: Double(s.rssBytes) / 1e6, numThreads: s.numThreads, numFds: s.numFds,
            cpuPercent: s.cpuPercent)
    }
}

private func timelineRows(_ checkpoints: [Checkpoint]) -> [(String, String, String, String, String)]
{
    checkpoints.map { c in
        var at = "\(c.pct)%"
        if let elapsed = c.elapsedS { at += " (\(formatDuration(elapsed)))" }
        let rss = c.rssMB.map { String(format: "%.1f MB", $0) } ?? "—"
        let threads = c.numThreads.map(String.init) ?? "—"
        let fds = c.numFds.map(String.init) ?? "—"
        let cpu = c.cpuPercent.map { String(format: "%.1f%%", $0) } ?? "—"
        return (at, rss, threads, fds, cpu)
    }
}

/// The full result of one scenario's run — one shared in-memory value
/// rendered to JSON, Markdown, and plain text (see `writeReports`) so the
/// three formats can never drift apart. Mirrors Python's `RunResult`.
package struct RunResult: Codable, Sendable {
    package var testName: String
    package var sdk: String
    /// "PASS" | "FAIL" | "ERROR"
    package var status: String
    package var durationS: Double
    package var elapsedS: Double
    package var iterations: Int
    package var startedAt: String
    package var endedAt: String
    package var metrics: [MetricResult]
    /// A one-line, plain-language statement of what this scenario actually
    /// does. Rendered right under the title — the report is read standalone
    /// (a GitHub Actions Job Summary, a downloaded artifact), and the
    /// `testName` slug alone doesn't say what's different between e.g.
    /// publish-churn and session-churn's broader mix.
    package var description: String?
    /// `nil` means this scenario doesn't count anything as a "transient
    /// error" in the first place — left unset rather than defaulted to 0,
    /// so the report doesn't render a measurement that was never actually
    /// taken.
    package var errors: Int?
    /// The SDK version under test (`ReactorSDK.version`) — passed in by the
    /// scenario, not computed here, since this module deliberately has no
    /// dependency on `Reactor` (see the module doc above).
    package var sdkVersion: String?
    package var commitSha: String?
    /// Set only when the run raised before/without any metric failing (an
    /// unhandled error, an insufficient-samples guard) — distinct from a
    /// metric actually crossing its threshold. Swift has no equivalent of
    /// Python's `sys.exc_info()` introspection inside a `finally` block, so
    /// unlike Python's `finish_run` this is passed in explicitly by the
    /// scenario (which already catches its own loop's error into a local
    /// variable to keep the report-writing path unconditional — see any
    /// file in `../EnduranceTests/`).
    package var runError: String?
    /// Computed automatically by `writeReports` from `samples` when left
    /// `nil` — not meant to be set directly.
    package var checkpoints: [Checkpoint]?
    package var samples: [Sample] = []

    package init(
        testName: String, sdk: String, status: String, durationS: Double, elapsedS: Double,
        iterations: Int, startedAt: String, endedAt: String, metrics: [MetricResult],
        description: String? = nil, errors: Int? = nil, sdkVersion: String? = nil,
        commitSha: String? = nil, runError: String? = nil, checkpoints: [Checkpoint]? = nil,
        samples: [Sample] = []
    ) {
        self.testName = testName
        self.sdk = sdk
        self.status = status
        self.durationS = durationS
        self.elapsedS = elapsedS
        self.iterations = iterations
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.metrics = metrics
        self.description = description
        self.errors = errors
        self.sdkVersion = sdkVersion
        self.commitSha = commitSha
        self.runError = runError
        self.checkpoints = checkpoints
        self.samples = samples
    }

    package var emoji: String {
        switch status {
        case "PASS": return "🟢"
        case "FAIL": return "🔴"
        case "ERROR": return "🟠"
        default: return "⚪"
        }
    }
}

private func conclusionLines(_ result: RunResult) -> [String] {
    if result.status == "ERROR" {
        return [
            "The test did not complete: \(result.runError ?? "an unexpected error occurred").",
            "This looks like a test error or an infrastructure/setup problem, not a detected "
                + "leak — no metric ran to completion to judge.",
            "See the downloadable JSON and logs for details.",
        ]
    }

    let tested =
        result.metrics.isEmpty ? "no metrics" : result.metrics.map(\.name).joined(separator: ", ")

    if result.status == "PASS" {
        var lines = [
            "The \(result.sdk) SDK completed \(formatDuration(result.elapsedS)) of endurance "
                + "testing across \(result.iterations) iterations without detecting resource "
                + "leaks or sustained growth in the metrics tested (\(tested))."
        ]
        for m in result.metrics {
            if m.unit == "count" && m.change == 0 {
                lines.append("\(m.name) stayed constant at \(formatValue(m.end, unit: m.unit)).")
            } else {
                lines.append(
                    "\(m.name) changed by \(formatDelta(m.change, unit: m.unit)) "
                        + "(\(formatValue(m.start, unit: m.unit)) → \(formatValue(m.end, unit: m.unit))) "
                        + "and remained stable.")
            }
        }
        if let errors = result.errors {
            lines.append(
                errors == 0
                    ? "No test errors occurred."
                    : "\(errors) transient error(s) occurred but did not affect the result.")
        }
        return lines
    }

    // FAIL
    let failed = result.metrics.filter { $0.status == "fail" }
    var lines: [String] = []
    if !failed.isEmpty {
        let names = failed.map(\.name).joined(separator: ", ")
        lines.append("The endurance test detected a failure in: \(names).")
        for m in failed {
            let thresholdStr =
                m.threshold.map { ", exceeding the configured threshold (\($0))" } ?? ""
            let cycleStr = m.firstBadCycle.map { " First detected at cycle \($0)." } ?? ""
            lines.append(
                "\(m.name) went from \(formatValue(m.start, unit: m.unit)) to "
                    + "\(formatValue(m.end, unit: m.unit)) (\(formatDelta(m.change, unit: m.unit)))"
                    + "\(thresholdStr).\(cycleStr)")
        }
    } else {
        lines.append(
            "The endurance test failed without a specific metric crossing its threshold — "
                + "likely a test error or infrastructure/setup failure rather than a detected leak."
        )
    }
    lines.append("See the downloadable JSON and diagnostic artifacts for detailed samples.")
    return lines
}

private func checkpointsFor(_ result: RunResult) -> [Checkpoint] {
    result.checkpoints ?? computeCheckpoints(result.samples)
}

package func renderMarkdown(_ result: RunResult) -> String {
    var lines: [String] = []
    lines.append("# \(result.emoji) Endurance Test Report — \(result.testName)")
    lines.append("")
    if let description = result.description {
        lines.append(description)
        lines.append("")
    }
    lines.append(
        "\(result.sdk) SDK · \(formatDuration(result.elapsedS)) · \(result.iterations) "
            + "iterations · **\(result.status)**")
    lines.append("")
    if let version = result.sdkVersion { lines.append("- **SDK version:** `\(version)`") }
    if let sha = result.commitSha { lines.append("- **Commit:** `\(sha.prefix(12))`") }
    lines.append("- **Started:** \(result.startedAt)")
    lines.append("- **Ended:** \(result.endedAt)")
    lines.append("- **Target duration:** \(formatDuration(result.durationS))")
    lines.append("")

    if !result.metrics.isEmpty {
        lines.append("## Results")
        lines.append("")
        lines.append("| Metric | Start | Mid | End | Δ (Mid→End) | Status |")
        lines.append("|---|---:|---:|---:|---:|---|")
        for m in result.metrics { lines.append(m.rowMarkdown()) }
        if let errors = result.errors {
            lines.append(
                "| Errors | \(errors) | — | \(errors) | — | \(errors == 0 ? "🟢" : "🟡") |")
        }
        lines.append("")
    }

    let checkpoints = checkpointsFor(result)
    if !checkpoints.isEmpty {
        // The Results table above only compares first-third vs last-third
        // means — see computeCheckpoints's own doc for why that can read as
        // "steady climb" when the real shape is e.g. flat, then one ramp,
        // then flat again. This shows the actual shape.
        lines.append("## Timeline")
        lines.append("")
        lines.append("| At | RSS | Threads | FDs | CPU% |")
        lines.append("|---|---:|---:|---:|---:|")
        for (at, rss, threads, fds, cpu) in timelineRows(checkpoints) {
            lines.append("| \(at) | \(rss) | \(threads) | \(fds) | \(cpu) |")
        }
        lines.append("")
    }

    lines.append("## Conclusion")
    lines.append("")
    lines.append("\(result.emoji) \(result.status)")
    lines.append("")
    lines.append(contentsOf: conclusionLines(result))
    lines.append("")

    lines.append("_Detailed samples are available as a downloadable JSON workflow artifact._")
    return lines.joined(separator: "\n").trimmingCharacters(in: .newlines) + "\n"
}

package func renderText(_ result: RunResult) -> String {
    var lines: [String] = []
    lines.append("Endurance Test Report — \(result.testName)")
    if let description = result.description { lines.append(description) }
    lines.append("\(result.sdk) SDK")
    lines.append(result.status)
    lines.append("Duration: \(formatDuration(result.elapsedS))")
    lines.append("")
    if let version = result.sdkVersion { lines.append("SDK version:    \(version)") }
    if let sha = result.commitSha { lines.append("Commit:         \(sha.prefix(12))") }
    lines.append("Started:        \(result.startedAt)")
    lines.append("Ended:          \(result.endedAt)")
    lines.append("Target duration: \(formatDuration(result.durationS))")
    lines.append("Iterations:     \(result.iterations)")
    lines.append("")

    if !result.metrics.isEmpty {
        lines.append("Results")
        lines.append("-------")
        for m in result.metrics { lines.append(m.rowText()) }
        if let errors = result.errors {
            lines.append("  Errors               \(errors)  \(errors == 0 ? "🟢" : "🟡")")
        }
        lines.append("")
    }

    let checkpoints = checkpointsFor(result)
    if !checkpoints.isEmpty {
        lines.append("Timeline")
        lines.append("--------")
        for (at, rss, threads, fds, cpu) in timelineRows(checkpoints) {
            lines.append("  \(at)  RSS \(rss)  threads \(threads)  fds \(fds)  cpu \(cpu)")
        }
        lines.append("")
    }

    lines.append("Conclusion")
    lines.append("----------")
    lines.append(result.status)
    lines.append("")
    lines.append(contentsOf: conclusionLines(result))
    lines.append("")

    lines.append("Detailed samples are available as a downloadable JSON workflow artifact.")
    return lines.joined(separator: "\n").trimmingCharacters(in: .newlines) + "\n"
}

/// Writes `{testName}.json` / `{testName}-report.md` / `{testName}-
/// summary.txt` under `outDir`, all three derived from the same `RunResult`
/// — see the module doc for why that matters.
///
/// Fills in `result.checkpoints` from `result.samples` first, when not
/// already set — callers only need to hand over the raw samples they
/// already collect, not know `computeCheckpoints` exists.
@discardableResult
package func writeReports(
    _ input: RunResult, outDir: URL = defaultEnduranceResultsDir
)
    throws -> [String: URL]
{
    var result = input
    if result.checkpoints == nil && !result.samples.isEmpty {
        result.checkpoints = computeCheckpoints(result.samples)
    }
    try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
    let jsonURL = outDir.appendingPathComponent("\(result.testName).json")
    let markdownURL = outDir.appendingPathComponent("\(result.testName)-report.md")
    let textURL = outDir.appendingPathComponent("\(result.testName)-summary.txt")

    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    encoder.keyEncodingStrategy = .convertToSnakeCase
    let jsonData = try encoder.encode(result)
    try jsonData.write(to: jsonURL)
    try renderMarkdown(result).write(to: markdownURL, atomically: true, encoding: .utf8)
    try renderText(result).write(to: textURL, atomically: true, encoding: .utf8)
    return ["json": jsonURL, "markdown": markdownURL, "text": textURL]
}

/// Builds the `RunResult` for one scenario's end-of-run reporting step and
/// writes its reports — the "assemble the result, write the reports" step
/// every scenario needs, in one place instead of copied verbatim into each
/// `*Tests.swift` file.
///
/// `runError` is passed in explicitly rather than introspected (see
/// `RunResult.runError`'s own doc for why this differs from Python's
/// `finish_run`, which reads `sys.exc_info()`).
///
/// Writing the reports is wrapped in its own `do`/`catch` on purpose: if it
/// itself throws (disk full, an encoding failure), printing and moving on
/// keeps whatever error the *test* raised — the actual failure — as what
/// propagates, instead of a reporting-layer bug masking it.
package func finishRun(
    testName: String, sdk: String, durationS: Double, startedAt: String, samples: [Sample],
    metrics: [MetricResult], iterations: Int, errors: Int? = nil, sdkVersion: String? = nil,
    description: String? = nil, runError: String? = nil, outDir: URL = defaultEnduranceResultsDir
) -> RunResult {
    let failed = metrics.contains { $0.status == "fail" }
    let status = runError != nil ? "ERROR" : (failed ? "FAIL" : "PASS")
    let result = RunResult(
        testName: testName, sdk: sdk, status: status, durationS: durationS,
        elapsedS: samples.last?.elapsedS ?? 0, iterations: iterations, startedAt: startedAt,
        endedAt: nowISO(), metrics: metrics, description: description, errors: errors,
        sdkVersion: sdkVersion, commitSha: gitCommitSHA(), runError: runError, samples: samples)
    do {
        try writeReports(result, outDir: outDir)
    } catch {
        print("[finishRun] writeReports(_:) failed, continuing: \(error)")
    }
    return result
}

/// Raised by `finishAndCheck` when the run's status came back "FAIL" —
/// mirrors Python's plain `AssertionError` raised in the same spot.
package struct EnduranceTestFailure: Error, CustomStringConvertible {
    package let description: String
    package init(_ description: String) { self.description = description }
}

/// The end-of-loop boilerplate every scenario needs: force one last
/// `LiveReporter` update, call `finishRun`, then throw if the run's status
/// came back "FAIL" — in one place instead of copied into each
/// `*Tests.swift` file. Mirrors Python's `finish_and_check`.
///
/// Call this unconditionally at the end of a scenario (after its own
/// `do`/`catch` around the loop has captured any error into `runError`),
/// the same way every scenario in `../EnduranceTests/` already structures
/// its teardown.
package func finishAndCheck(
    testName: String, sdk: String, description: String, sdkVersion: String?, durationS: Double,
    startedAt: String, samples: [Sample], live: LiveReporter, metrics: [MetricResult],
    iterations: Int, errors: Int? = nil, runError: String? = nil,
    outDir: URL = defaultEnduranceResultsDir
) throws {
    if !samples.isEmpty {
        live.update(samples, errors: errors, force: true)
    }
    let result = finishRun(
        testName: testName, sdk: sdk, durationS: durationS, startedAt: startedAt,
        samples: samples, metrics: metrics, iterations: iterations, errors: errors,
        sdkVersion: sdkVersion, description: description, runError: runError, outDir: outDir)
    if result.status == "FAIL" {
        let names = metrics.filter { $0.status == "fail" }.map(\.name).joined(separator: ", ")
        throw EnduranceTestFailure(
            "endurance test detected a failure in: \(names) — see the report for details")
    }
}

/// Prints a periodic, human-scale status block during a long endurance run
/// so a log stays legible instead of accumulating one row per cycle for
/// hours (the default). Under `ENDURANCE_VERBOSE=1` this stays silent.
/// Mirrors Python's `LiveReporter`.
package final class LiveReporter: @unchecked Sendable {
    private let testName: String
    private let durationS: Double
    private let intervalS: Double
    private var lastPrint: ContinuousClock.Instant?
    private var printedOnce = false

    package init(testName: String, durationS: Double, intervalS: Double? = nil) {
        self.testName = testName
        self.durationS = durationS
        self.intervalS = intervalS ?? liveIntervalSeconds
    }

    package func update(_ samples: [Sample], errors: Int? = nil, force: Bool = false) {
        guard !EnduranceVerbose.isEnabled, !samples.isEmpty else { return }
        let now = ContinuousClock.now
        if !force, printedOnce, let lastPrint,
            lastPrint.duration(to: now) < .seconds(intervalS)
        {
            return
        }
        lastPrint = now
        printedOnce = true
        print(format(samples, errors: errors))
    }

    private func format(_ samples: [Sample], errors: Int?) -> String {
        let first = samples[0]
        let last = samples[samples.count - 1]
        let elapsed = last.elapsedS
        let pct = durationS > 0 ? min(100, Int(elapsed / durationS * 100)) : 0
        let rssStartMB = Double(first.rssBytes) / 1e6
        let rssNowMB = Double(last.rssBytes) / 1e6
        let avgCPU = samples.map(\.cpuPercent).reduce(0, +) / Double(samples.count)
        let bar = String(repeating: "─", count: 46)
        var lines = [
            bar,
            "🚀 Endurance Test — \(testName)",
            bar,
            "",
            "Duration       \(formatDuration(durationS))",
            "Elapsed        \(formatDuration(elapsed))",
            "Progress       \(pct)%",
            "",
            "Iterations     \(last.cycle + 1)",
        ]
        if let errors { lines.append("Errors         \(errors)") }
        lines += [
            "",
            "Resources",
            "  RSS           \(String(format: "%.0f", rssStartMB)) MB → "
                + "\(String(format: "%.0f", rssNowMB)) MB   "
                + "(\(formatDelta(rssNowMB - rssStartMB, unit: "MB")))",
            "  CPU           avg \(String(format: "%.1f", avgCPU))%",
            "  Threads       \(first.numThreads) → \(last.numThreads)",
            "  File desc.    \(first.numFds) → \(last.numFds)",
        ]
        let status = (errors ?? 0) > 0 ? "🟡 Errors detected" : "🟢 Healthy"
        lines.append("")
        lines.append("Status         \(status)")
        lines.append(bar)
        return lines.joined(separator: "\n")
    }
}
