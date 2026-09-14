import Testing

import EnduranceReporting

/// Tests for the endurance-tests suite's pass/fail semantics
/// (`../EnduranceReporting/Trends.swift`). Pure functions, no `Reactor`/FFI
/// involved — mirrors `sdks/python/tests/test_trends.py`.

private func sample(
    cycle: Int, elapsedS: Double? = nil, rssBytes: UInt64 = 100_000_000, cpuS: Double? = nil,
    cpuPercent: Double = 5.0, numThreads: Int = 20, numFds: Int = 15
) -> Sample {
    Sample(
        cycle: cycle, elapsedS: elapsedS ?? Double(cycle), rssBytes: rssBytes,
        cpuS: cpuS ?? Double(cycle) * 0.1, cpuPercent: cpuPercent, numThreads: numThreads,
        numFds: numFds)
}

@Suite("cpuDeltas")
struct CPUDeltasTests {
    @Test("diffs consecutive cumulative values")
    func diffsConsecutiveCumulativeValues() {
        let samples = [0.0, 0.5, 1.2, 1.4].enumerated().map { i, cpuS in
            sample(cycle: i, cpuS: cpuS)
        }
        let deltas = cpuDeltas(samples)
        #expect(deltas.count == 3)
        #expect(abs(deltas[0] - 0.5) < 1e-9)
        #expect(abs(deltas[1] - 0.7) < 1e-9)
        #expect(abs(deltas[2] - 0.2) < 1e-9)
    }

    @Test("empty and single sample yield no deltas")
    func emptyAndSingleSampleYieldNoDeltas() {
        #expect(cpuDeltas([]).isEmpty)
        #expect(cpuDeltas([sample(cycle: 0)]).isEmpty)
    }
}

@Suite("assertNoSustainedGrowth")
struct AssertNoSustainedGrowthTests {
    @Test("too few samples throws, does not return")
    func tooFewSamplesThrows() {
        // n < 6 is a test misconfiguration (ENDURANCE_DURATION_SECONDS too
        // short), not a metric to report on — see the function's own doc
        // comment for why this one case still throws directly.
        #expect(throws: EnduranceAssertionError.self) {
            try assertNoSustainedGrowth([1.0, 2.0, 3.0], name: "x", maxGrowthRatio: 0.15)
        }
    }

    @Test("flat values pass")
    func flatValuesPass() throws {
        let result = try assertNoSustainedGrowth(
            Array(repeating: 100.0, count: 30), name: "rss", unit: "MB", maxGrowthRatio: 0.15,
            minAbsoluteDelta: 5.0)
        #expect(result.status == "ok")
    }

    @Test("still climbing in the tail fails")
    func stillClimbingInTheTailFails() throws {
        // Genuinely still trending up in the last third relative to the
        // middle third, well past both the ratio and absolute floors.
        let values = (0..<30).map { 100.0 + Double($0) * 5.0 }
        let result = try assertNoSustainedGrowth(
            values, name: "rss", unit: "MB", maxGrowthRatio: 0.15, minAbsoluteDelta: 5.0)
        #expect(result.status == "fail")
        #expect(result.detail.hasPrefix("still climbing"))
    }

    @Test("one-time ramp that has already plateaued passes")
    func oneTimeRampThatHasAlreadyPlateauedPasses() throws {
        // The exact shape that motivated middle-vs-last over first-vs-last:
        // flat, then a one-time ramp to a new plateau early in the run,
        // then flat for the rest. The *overall* first-to-last movement is
        // large, but the tail (last third vs middle third) is flat — a
        // native buffer/pool growing once to steady-state, not an
        // unbounded leak, so this must pass.
        let n = 30
        var values: [Double] = []
        for i in 0..<n {
            let pct = Double(i) / Double(n - 1)
            if pct < 0.25 {
                values.append(100.0)
            } else if pct < 0.45 {
                values.append(100.0 + (pct - 0.25) / 0.20 * 40.0)
            } else {
                values.append(140.0)
            }
        }
        let result = try assertNoSustainedGrowth(
            values, name: "rss", unit: "MB", maxGrowthRatio: 0.15, minAbsoluteDelta: 5.0)
        #expect(result.status == "ok")
        // The reported start/end still show the true overall movement, even
        // though that's not what decided pass/fail.
        #expect(result.change > 20.0)
    }

    @Test("min absolute delta floor suppresses a small ratio looking big")
    func minAbsoluteDeltaFloorSuppressesASmallRatioLookingBig() throws {
        // A tiny near-zero baseline can turn an insignificant wobble into a
        // ratio that looks huge — the absolute floor exists for exactly
        // this.
        let values = Array(repeating: 0.01, count: 15) + Array(repeating: 0.02, count: 15)
        let result = try assertNoSustainedGrowth(
            values, name: "cpu_s_per_cycle", unit: "s", maxGrowthRatio: 0.15,
            minAbsoluteDelta: 0.05)
        #expect(result.status == "ok")
    }

    @Test("useMedian ignores a single outlier cycle")
    func useMedianIgnoresASingleOutlierCycle() throws {
        // A one-cycle double-counted-thread artifact landing in the last
        // window shouldn't by itself read as a sustained trend.
        let values =
            Array(repeating: 25.0, count: 12) + [25.0, 25.0, 31.0, 25.0, 25.0, 25.0]
        let result = try assertNoSustainedGrowth(
            values, name: "num_threads", unit: "count", maxGrowthRatio: 0.15,
            minAbsoluteDelta: 4, useMedian: true)
        #expect(result.status == "ok")
    }
}

@Suite("assertAlwaysZero")
struct AssertAlwaysZeroTests {
    @Test("stays zero passes")
    func staysZeroPasses() {
        let samples = (0..<10).map { sample(cycle: $0) }
        let result = assertAlwaysZero(samples, name: "orphaned_callbacks") { _ in 0 }
        #expect(result.status == "ok")
    }

    @Test("nonzero mid-run fails even if it settles back to zero")
    func nonzeroMidRunFailsEvenIfItSettlesBackToZero() {
        // Regression: a leak on cycle 3 that happens to get cleaned up by
        // the final cycle is still a real bug — must not read as "ok" just
        // because the last sample is 0.
        let values = [0, 0, 0, 2, 0, 0]
        let samples = values.enumerated().map { i, _ in sample(cycle: i) }
        let result = assertAlwaysZero(samples, name: "orphaned_callbacks") { s in values[s.cycle] }
        #expect(result.status == "fail")
        #expect(result.firstBadCycle == 3)
    }

    @Test("reports the peak, not the final sample")
    func reportsThePeakNotTheFinalSample() {
        // Regression: using the *final* sample's value for `end`/`change`
        // renders "went from 0 to 0 (+0)" in the Markdown/text reports
        // (which don't include `detail`) whenever the count had already
        // settled back to 0 by the last cycle — hiding the leaked value
        // from the primary failure artifact entirely.
        let values = [0, 0, 5, 3, 0, 0]
        let samples = values.enumerated().map { i, _ in sample(cycle: i) }
        let result = assertAlwaysZero(samples, name: "orphaned_callbacks") { s in values[s.cycle] }
        #expect(result.status == "fail")
        #expect(result.end == 5)
        #expect(result.change == 5)
        #expect(result.detail.contains("peak 5"))
    }
}

@Suite("assertNeverGrows")
struct AssertNeverGrowsTests {
    @Test("flat at baseline passes")
    func flatAtBaselinePasses() {
        let samples = (0..<10).map { _ in sample(cycle: 0, numFds: 1) }
        let result = assertNeverGrows(samples, name: "live_clients") { _ in 1 }
        #expect(result.status == "ok")
    }

    @Test("growth past the starting value fails")
    func growthPastTheStartingValueFails() {
        let values = [1, 1, 1, 2, 1]
        let samples = values.enumerated().map { i, _ in sample(cycle: i) }
        let result = assertNeverGrows(samples, name: "live_clients") { s in values[s.cycle] }
        #expect(result.status == "fail")
        #expect(result.start == 1)
        #expect(result.end == 2)
    }
}
