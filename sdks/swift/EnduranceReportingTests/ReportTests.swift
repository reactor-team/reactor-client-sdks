import Foundation
import Testing

import EnduranceReporting

/// Tests for the endurance-tests reporting layer (`../EnduranceReporting/
/// Report.swift`). Pure formatting/aggregation logic, no `Reactor`/FFI
/// involved — mirrors `sdks/python/tests/test_endurance_report.py`.

private func okMetric(
    _ name: String, start: Double, end: Double, unit: String = "MB"
)
    -> MetricResult
{
    MetricResult(
        name: name, start: start, end: end, change: end - start, unit: unit, status: "ok",
        detail: "\(name) stayed within bounds", threshold: "> 15% growth (min 5)")
}

private func failMetric(
    _ name: String, start: Double, end: Double, unit: String = "MB"
)
    -> MetricResult
{
    MetricResult(
        name: name, start: start, end: end, change: end - start, unit: unit, status: "fail",
        detail: "\(name) grew past the threshold", threshold: "> 15% growth (min 5)",
        firstBadCycle: 42)
}

private func passingResult() -> RunResult {
    RunResult(
        testName: "session-churn", sdk: "Swift", status: "PASS", durationS: 7200,
        elapsedS: 7205, iterations: 2184, startedAt: "2026-09-13T10:00:00Z",
        endedAt: "2026-09-13T12:00:05Z",
        metrics: [
            okMetric("rss", start: 284.0, end: 291.0),
            okMetric("num_threads", start: 14, end: 14, unit: "count"),
            okMetric("num_fds", start: 23, end: 23, unit: "count"),
        ], errors: 0, commitSha: "abc123def456abc123def456")
}

private func failingResult() -> RunResult {
    var result = passingResult()
    result.status = "FAIL"
    result.metrics = [
        failMetric("rss", start: 284.0, end: 612.0),
        okMetric("num_threads", start: 14, end: 14, unit: "count"),
    ]
    return result
}

private func conclusion(_ text: String) -> String {
    text.components(separatedBy: "## Conclusion").last ?? ""
}

/// `writeReports` encodes with `.convertToSnakeCase` (see Report.swift) — the
/// matching decode strategy on the way back in, so these tests can round-trip
/// through the JSON `writeReports` actually writes rather than reaching past
/// it into `RunResult`'s Swift-cased properties directly.
private func decodeRunResult(_ data: Data) throws -> RunResult {
    let decoder = JSONDecoder()
    decoder.keyDecodingStrategy = .convertFromSnakeCase
    return try decoder.decode(RunResult.self, from: data)
}

@Suite("Passing report")
struct PassingReportTests {
    @Test("status is PASS")
    func statusIsPass() {
        #expect(passingResult().status == "PASS")
    }

    @Test("conclusion mentions tested metrics")
    func conclusionMentionsTestedMetrics() {
        let md = renderMarkdown(passingResult())
        #expect(md.contains("rss"))
        #expect(md.contains("num_threads"))
        #expect(md.contains("num_fds"))
        #expect(md.contains("PASS"))
    }

    @Test("metric statuses render as Stable")
    func metricStatusesRenderAsStable() {
        let md = renderMarkdown(passingResult())
        #expect(md.contains("Stable"))
        #expect(!md.contains("FAILED"))
    }

    @Test("duration and iteration count included")
    func durationAndIterationCountIncluded() {
        let md = renderMarkdown(passingResult())
        #expect(md.contains("2184"))
        #expect(md.contains("2h 00m"))
    }

    @Test("SDK name says SDK")
    func sdkNameSaysSDK() {
        // Regression: an earlier draft rendered "The Swift completed..."
        // (missing "SDK") — caught by actually reading a generated report,
        // not by a test that only checked the header line.
        let conclusionText = conclusion(renderMarkdown(passingResult()))
        #expect(conclusionText.contains("The Swift SDK completed"))
    }
}

@Suite("Failing report")
struct FailingReportTests {
    @Test("status is FAIL")
    func statusIsFail() {
        #expect(failingResult().status == "FAIL")
    }

    @Test("actual and threshold and delta included")
    func actualAndThresholdAndDeltaIncluded() {
        let md = renderMarkdown(failingResult())
        #expect(md.contains("284.0 MB"))
        #expect(md.contains("612.0 MB"))
        #expect(md.contains("+328.0 MB"))
        #expect(md.contains("15% growth"))
    }

    @Test("failed metric is identified as failed")
    func failedMetricIsIdentifiedAsFailed() {
        #expect(renderMarkdown(failingResult()).contains("FAILED"))
    }

    @Test("conclusion does not claim pass")
    func conclusionDoesNotClaimPass() {
        let conclusionText = conclusion(renderMarkdown(failingResult()))
        #expect(!conclusionText.contains("PASS"))
        #expect(conclusionText.contains("🔴"))
    }

    @Test("conclusion names the failed metric")
    func conclusionNamesTheFailedMetric() {
        #expect(conclusion(renderMarkdown(failingResult())).contains("rss"))
    }

    @Test("first bad cycle surfaced when available")
    func firstBadCycleSurfacedWhenAvailable() {
        #expect(renderMarkdown(failingResult()).contains("cycle 42"))
    }
}

@Suite("Error report")
struct ErrorReportTests {
    // A test that never got as far as evaluating a metric (an unhandled
    // error, an insufficient-samples guard) — distinct from a threshold
    // violation: no metric failed, the run itself didn't complete.
    private func errorResult() -> RunResult {
        RunResult(
            testName: "lifecycle-churn", sdk: "Swift", status: "ERROR", durationS: 300,
            elapsedS: 12.0, iterations: 1, startedAt: "2026-09-13T10:00:00Z",
            endedAt: "2026-09-13T10:00:12Z", metrics: [],
            runError: "RateLimitedError: quota exceeded")
    }

    @Test("status is ERROR, not FAIL")
    func statusIsErrorNotFail() {
        #expect(errorResult().status == "ERROR")
    }

    @Test("conclusion mentions the run error")
    func conclusionMentionsTheRunError() {
        #expect(renderMarkdown(errorResult()).contains("RateLimitedError"))
    }

    @Test("conclusion does not claim pass or metric failure")
    func conclusionDoesNotClaimPassOrMetricFailure() {
        #expect(!conclusion(renderMarkdown(errorResult())).contains("PASS"))
    }

    @Test("no metrics table when there are no metrics")
    func noMetricsTableWhenThereAreNoMetrics() {
        #expect(!renderMarkdown(errorResult()).contains("| Metric |"))
    }
}

@Suite("Missing optional diagnostics")
struct MissingOptionalDiagnosticsTests {
    @Test("no commit SHA does not crash")
    func noCommitSHADoesNotCrash() {
        var result = passingResult()
        result.commitSha = nil
        #expect(!renderMarkdown(result).contains("Commit"))
    }

    @Test("empty metrics list does not crash")
    func emptyMetricsListDoesNotCrash() {
        var result = passingResult()
        result.metrics = []
        _ = renderMarkdown(result)
        _ = renderText(result)
    }

    @Test("no samples means no Timeline and does not crash")
    func noSamplesMeansNoTimelineAndDoesNotCrash() {
        let result = passingResult()
        #expect(result.samples.isEmpty)
        #expect(!renderMarkdown(result).contains("## Timeline"))
        #expect(!renderText(result).contains("Timeline\n--------"))
    }
}

/// RSS flat for the first ~40%, ramps for the middle ~40%, flat again for
/// the last ~20% — the exact shape that motivated `computeCheckpoints()`: a
/// first-third-vs-last-third trend check correctly flags growth here, but
/// reading only that average makes it look like a steady climb the whole
/// run, when the metric was actually flat most of the time with one ramp in
/// the middle.
private func flatRampFlatSamples(_ n: Int = 100) -> [Sample] {
    (0..<n).map { i in
        let pct = Double(i) / Double(n - 1)
        let rss: Double
        if pct < 0.3 {
            rss = 110.0
        } else if pct < 0.6 {
            rss = 110.0 + (pct - 0.3) / 0.3 * 18.0
        } else {
            rss = 128.0
        }
        return Sample(
            cycle: i, elapsedS: pct * 300, rssBytes: UInt64(rss * 1e6), cpuS: 0, cpuPercent: 3.6,
            numThreads: 23, numFds: 22)
    }
}

@Suite("Timeline")
struct TimelineTests {
    @Test("computeCheckpoints returns requested count")
    func computeCheckpointsReturnsRequestedCount() {
        let checkpoints = computeCheckpoints(flatRampFlatSamples(), n: 5)
        #expect(checkpoints.count == 5)
        #expect(checkpoints.first?.pct == 0)
        #expect(checkpoints.last?.pct == 100)
    }

    @Test("computeCheckpoints on empty samples returns empty")
    func computeCheckpointsOnEmptySamplesReturnsEmpty() {
        #expect(computeCheckpoints([]).isEmpty)
    }

    @Test("checkpoints reveal plateau then ramp then plateau")
    func checkpointsRevealPlateauThenRampThenPlateau() {
        // The whole point: a shape a first-third/last-third average alone
        // would flatten into "steady climb" is visible here as flat, then
        // a jump, then flat again.
        let checkpoints = computeCheckpoints(flatRampFlatSamples(), n: 5)
        let byPct = Dictionary(uniqueKeysWithValues: checkpoints.map { ($0.pct, $0.rssMB) })
            .compactMapValues { $0 }
        #expect(abs((byPct[0] ?? 0) - 110.0) < 0.5)
        #expect(abs((byPct[25] ?? 0) - 110.0) < 0.5)
        #expect((byPct[50] ?? 0) < (byPct[75] ?? 0))
        #expect(abs((byPct[75] ?? 0) - 128.0) < 0.5)
        #expect(abs((byPct[100] ?? 0) - 128.0) < 0.5)
    }

    @Test("Timeline section appears in markdown and text")
    func timelineSectionAppearsInMarkdownAndText() {
        var result = passingResult()
        result.samples = flatRampFlatSamples()
        let md = renderMarkdown(result)
        let text = renderText(result)
        #expect(md.contains("## Timeline"))
        #expect(text.contains("Timeline\n--------"))
        #expect(md.contains("110.0 MB"))
        #expect(md.contains("128.0 MB"))
    }

    @Test("writeReports computes checkpoints from samples")
    func writeReportsComputesCheckpointsFromSamples() throws {
        var result = passingResult()
        result.samples = flatRampFlatSamples()
        #expect(result.checkpoints == nil)
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        try writeReports(result, outDir: tmp)
        // `writeReports` computes checkpoints on a local copy for
        // serialization — read them back from the written JSON instead of
        // the caller's own `result`, which `writeReports` does not mutate
        // in place (a value type, unlike Python's mutable dataclass).
        let data = try Data(contentsOf: tmp.appendingPathComponent("session-churn.json"))
        let decoded = try decodeRunResult(data)
        #expect(decoded.checkpoints?.count == 5)
    }

    @Test("written JSON contains checkpoints")
    func writtenJSONContainsCheckpoints() throws {
        var result = passingResult()
        result.samples = flatRampFlatSamples()
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let paths = try writeReports(result, outDir: tmp)
        let data = try Data(contentsOf: paths["json"]!)
        let decoded = try decodeRunResult(data)
        #expect(decoded.checkpoints?.count == 5)
        #expect(decoded.checkpoints?.first?.pct == 0)
    }
}

@Suite("JSON output")
struct JSONOutputTests {
    @Test("written JSON contains metadata, samples, metrics, and status")
    func writtenJSONContainsMetadataSamplesMetricsAndStatus() throws {
        var result = passingResult()
        result.samples = [
            Sample(cycle: 0, elapsedS: 0, rssBytes: 1000, cpuS: 0, numThreads: 1, numFds: 1),
            Sample(cycle: 1, elapsedS: 1, rssBytes: 1010, cpuS: 0, numThreads: 1, numFds: 1),
        ]
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let paths = try writeReports(result, outDir: tmp)

        let data = try Data(contentsOf: paths["json"]!)
        let decoded = try decodeRunResult(data)
        #expect(decoded.testName == "session-churn")
        #expect(decoded.sdk == "Swift")
        #expect(decoded.status == "PASS")
        #expect(decoded.iterations == 2184)
        #expect(decoded.samples.count == 2)
        #expect(decoded.metrics.count == 3)
        #expect(decoded.metrics.first?.name == "rss")
    }

    @Test("writeReports creates all three files")
    func writeReportsCreatesAllThreeFiles() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let paths = try writeReports(passingResult(), outDir: tmp)
        #expect(FileManager.default.fileExists(atPath: paths["json"]!.path))
        #expect(FileManager.default.fileExists(atPath: paths["markdown"]!.path))
        #expect(FileManager.default.fileExists(atPath: paths["text"]!.path))
        #expect(paths["json"]!.lastPathComponent == "session-churn.json")
        #expect(paths["markdown"]!.lastPathComponent == "session-churn-report.md")
        #expect(paths["text"]!.lastPathComponent == "session-churn-summary.txt")
    }
}

@Suite("Markdown/text consistency")
struct MarkdownTextConsistencyTests {
    @Test("both report the same status")
    func bothReportTheSameStatus() {
        let result = failingResult()
        #expect(renderMarkdown(result).contains("FAIL"))
        #expect(renderText(result).contains("FAIL"))
    }

    @Test("both mention the same metric values")
    func bothMentionTheSameMetricValues() {
        let result = failingResult()
        let md = renderMarkdown(result)
        let text = renderText(result)
        for token in ["284.0 MB", "612.0 MB"] {
            #expect(md.contains(token))
            #expect(text.contains(token))
        }
    }
}

@Suite("Errors field")
struct ErrorsFieldTests {
    // `RunResult.errors` is `Int?`, not defaulted to 0 — a scenario that
    // never actually counts anything leaves it unset rather than render a
    // hardcoded-looking "Errors 0" that was never really measured.
    @Test("errors nil omits the row in markdown and text")
    func errorsNilOmitsTheRowInMarkdownAndText() {
        var result = passingResult()
        result.errors = nil
        #expect(!renderMarkdown(result).contains("Errors"))
        #expect(!renderText(result).contains("Errors"))
    }

    @Test("errors nil omits the conclusion sentence")
    func errorsNilOmitsTheConclusionSentence() {
        var result = passingResult()
        result.errors = nil
        let conclusionText = conclusion(renderMarkdown(result))
        #expect(!conclusionText.contains("test errors occurred"))
        #expect(!conclusionText.contains("transient error"))
    }

    @Test("errors zero is still shown as a real measurement")
    func errorsZeroIsStillShownAsARealMeasurement() {
        var result = passingResult()
        result.errors = 0
        let md = renderMarkdown(result)
        #expect(md.contains("| Errors | 0 | — | 0 |"))
        #expect(conclusion(md).contains("No test errors occurred."))
    }

    @Test("errors nonzero is reported")
    func errorsNonzeroIsReported() {
        var result = passingResult()
        result.errors = 3
        #expect(conclusion(renderMarkdown(result)).contains("3 transient error(s) occurred"))
    }
}

@Suite("finishRun")
struct FinishRunTests {
    @Test("PASS when no metric failed and nothing raised")
    func passWhenNoMetricFailedAndNothingRaised() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let result = finishRun(
            testName: "lifecycle-churn", sdk: "Swift", durationS: 300,
            startedAt: "2026-09-13T10:00:00Z", samples: [],
            metrics: [okMetric("rss", start: 1.0, end: 1.0)], iterations: 5, outDir: tmp)
        #expect(result.status == "PASS")
        #expect(result.runError == nil)
    }

    @Test("FAIL when a metric failed and nothing raised")
    func failWhenAMetricFailedAndNothingRaised() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let result = finishRun(
            testName: "lifecycle-churn", sdk: "Swift", durationS: 300,
            startedAt: "2026-09-13T10:00:00Z", samples: [],
            metrics: [failMetric("rss", start: 1.0, end: 2.0)], iterations: 5, outDir: tmp)
        #expect(result.status == "FAIL")
        #expect(result.runError == nil)
    }

    @Test("ERROR when a runError is passed regardless of metrics")
    func errorWhenARunErrorIsPassedRegardlessOfMetrics() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let result = finishRun(
            testName: "lifecycle-churn", sdk: "Swift", durationS: 300,
            startedAt: "2026-09-13T10:00:00Z", samples: [], metrics: [], iterations: 1,
            runError: "RuntimeError: boom", outDir: tmp)
        #expect(result.status == "ERROR")
        #expect(result.runError == "RuntimeError: boom")
    }

    @Test("writes reports to outDir")
    func writesReportsToOutDir() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        _ = finishRun(
            testName: "lifecycle-churn", sdk: "Swift", durationS: 300,
            startedAt: "2026-09-13T10:00:00Z", samples: [],
            metrics: [okMetric("rss", start: 1.0, end: 1.0)], iterations: 5, outDir: tmp)
        #expect(
            FileManager.default.fileExists(
                atPath: tmp.appendingPathComponent("lifecycle-churn.json").path))
        #expect(
            FileManager.default.fileExists(
                atPath: tmp.appendingPathComponent("lifecycle-churn-report.md").path))
    }
}

@Suite("finishAndCheck")
struct FinishAndCheckTests {
    @Test("throws when the run's status is FAIL")
    func throwsWhenTheRunsStatusIsFAIL() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let live = LiveReporter(testName: "lifecycle-churn", durationS: 300)
        #expect(throws: EnduranceTestFailure.self) {
            try finishAndCheck(
                testName: "lifecycle-churn", sdk: "Swift", description: "d", sdkVersion: "1.0.0",
                durationS: 300, startedAt: "2026-09-13T10:00:00Z", samples: [], live: live,
                metrics: [failMetric("rss", start: 1.0, end: 2.0)], iterations: 5, outDir: tmp)
        }
    }

    @Test("does not throw for ERROR — the caller re-throws its own pending error")
    func doesNotThrowForErrorTheCallerReThrowsItsOwnPendingError() throws {
        // Swift has no `sys.exc_info()` equivalent, so `finishAndCheck`
        // only ever throws for a FAIL status — an ERROR status (a `nil`
        // `runError` was never in flight, so nothing propagated on its
        // own) is recorded in the report but left for the scenario's own
        // `if let pending { throw pending }` to actually fail the test.
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let live = LiveReporter(testName: "lifecycle-churn", durationS: 300)
        try finishAndCheck(
            testName: "lifecycle-churn", sdk: "Swift", description: "d", sdkVersion: "1.0.0",
            durationS: 300, startedAt: "2026-09-13T10:00:00Z", samples: [], live: live,
            metrics: [], iterations: 1, runError: "boom", outDir: tmp)
    }
}
