package inc.reactor.sdk.endurance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

/**
 * One result, three renderings, written whichever way the run ends.
 *
 * <p>A report that only appears on success is useless for the run you actually need to debug. All
 * three files are written in a {@code finally}, and only then does a failing metric raise.
 *
 * <p>Deliberately imports nothing from the SDK. Its own tests need no live service and no native
 * library, which is what keeps them able to check the formatting of a report about a suite they
 * never run.
 */
final class Report {

    /** Where reports land. Ignored by git; uploaded whole by CI, including on a failure. */
    static final Path DIRECTORY = Path.of("endurance-results");

    private Report() {}

    /**
     * Writes the three files and fails the run if any metric did.
     *
     * @param scenario the slug, e.g. {@code publish-churn}
     * @param description one hand-written line saying what this scenario's loop actually does
     * @param sdkVersion what was under test
     * @param commit the commit under test
     * @param durationSeconds how long the run was asked to go on for
     * @param samples every reading taken
     * @param metrics every verdict
     */
    static void finishAndCheck(
            String scenario,
            String description,
            String sdkVersion,
            String commit,
            double durationSeconds,
            List<Sample> samples,
            List<MetricResult> metrics) {

        String markdown = markdown(scenario, description, sdkVersion, commit, durationSeconds, samples, metrics);
        try {
            Files.createDirectories(DIRECTORY);
            Files.writeString(DIRECTORY.resolve(scenario + "-report.md"), markdown);
            Files.writeString(
                    DIRECTORY.resolve(scenario + "-report.json"),
                    json(scenario, description, sdkVersion, commit, durationSeconds, samples, metrics));
            Files.writeString(DIRECTORY.resolve(scenario + "-report.txt"), text(scenario, metrics));
        } catch (IOException e) {
            throw new UncheckedIOException("the endurance report could not be written", e);
        }

        System.out.println(markdown);

        List<MetricResult> failed =
                metrics.stream().filter(metric -> !metric.passed()).toList();
        if (!failed.isEmpty()) {
            StringJoiner why = new StringJoiner("\n  ", scenario + " failed:\n  ", "");
            failed.forEach(metric -> why.add(metric.name() + ": " + metric.detail()));
            throw new AssertionError(why.toString());
        }
    }

    private static String markdown(
            String scenario,
            String description,
            String sdkVersion,
            String commit,
            double durationSeconds,
            List<Sample> samples,
            List<MetricResult> metrics) {

        boolean passed = metrics.stream().allMatch(MetricResult::passed);
        StringBuilder out = new StringBuilder();
        out.append("## Endurance · ")
                .append(scenario)
                .append(passed ? " · PASS" : " · FAIL")
                .append("\n\n");
        // The slug alone does not say "no frames, no commands", and that distinction is the whole
        // point once there is more than one scenario.
        out.append(description).append("\n\n");
        out.append("| | |\n| --- | --- |\n");
        out.append("| SDK version | `").append(sdkVersion).append("` |\n");
        out.append("| Commit | `").append(commit).append("` |\n");
        out.append("| Duration | ").append("%.0f s".formatted(durationSeconds)).append(" |\n");
        out.append("| Cycles | ")
                .append(samples.isEmpty() ? 0 : samples.get(samples.size() - 1).cycle())
                .append(" |\n");
        out.append("| Finished | ").append(Instant.now()).append(" |\n\n");

        out.append("### Results\n\n");
        out.append("| Metric | start | mid | end | mid → end | Verdict |\n");
        out.append("| --- | ---: | ---: | ---: | ---: | --- |\n");
        for (MetricResult metric : metrics) {
            out.append("| ").append(metric.name());
            out.append(" | ").append("%.1f".formatted(metric.start()));
            out.append(" | ").append("%.1f".formatted(metric.mid()));
            out.append(" | ").append("%.1f".formatted(metric.end()));
            // The delta the verdict is actually based on, in its own column: start -> end reads as
            // growth on a healthy run, because a one-time step early on moves end relative to
            // start without ever being a leak.
            out.append(" | ").append("%+.1f".formatted(metric.midToEnd()));
            out.append(" | ").append(metric.passed() ? "pass" : "**FAIL**");
            out.append(" — ").append(metric.detail()).append(" |\n");
        }

        out.append("\n### Timeline\n\n");
        out.append("| at | cycle | RSS MB | threads | fds | live | orphaned |\n");
        out.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (Sample sample : checkpoints(samples)) {
            out.append("| ").append("%.0fs".formatted(sample.elapsedSeconds()));
            out.append(" | ").append(sample.cycle());
            out.append(" | ").append("%.1f".formatted(sample.rssBytes() / 1_048_576.0));
            out.append(" | ").append(sample.threads());
            out.append(" | ").append(sample.openFds());
            out.append(" | ").append(sample.liveClients());
            out.append(" | ").append(sample.orphanedArenas()).append(" |\n");
        }
        out.append("\nFlat, a one-time ramp, or still climbing — the shape is what a reader needs,")
                .append(" and two numbers cannot show it.\n");
        return out.toString();
    }

    /** A handful of evenly spaced readings, which is what shows the shape. */
    private static List<Sample> checkpoints(List<Sample> samples) {
        if (samples.size() <= 8) {
            return samples;
        }
        List<Sample> picked = new java.util.ArrayList<>();
        for (int index = 0; index < 8; index++) {
            picked.add(samples.get(index * (samples.size() - 1) / 7));
        }
        return picked;
    }

    private static String json(
            String scenario,
            String description,
            String sdkVersion,
            String commit,
            double durationSeconds,
            List<Sample> samples,
            List<MetricResult> metrics) {

        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"sdk\": \"java\",\n");
        out.append("  \"test_name\": \"").append(scenario).append("\",\n");
        out.append("  \"description\": \"").append(escape(description)).append("\",\n");
        out.append("  \"sdk_version\": \"").append(sdkVersion).append("\",\n");
        out.append("  \"commit\": \"").append(commit).append("\",\n");
        out.append("  \"duration_seconds\": ").append(durationSeconds).append(",\n");
        out.append("  \"passed\": ")
                .append(metrics.stream().allMatch(MetricResult::passed))
                .append(",\n");
        out.append("  \"metrics\": [\n");
        for (int index = 0; index < metrics.size(); index++) {
            MetricResult metric = metrics.get(index);
            out.append("    {\"name\": \"").append(metric.name()).append("\"");
            out.append(", \"start\": ").append(metric.start());
            out.append(", \"mid\": ").append(metric.mid());
            out.append(", \"end\": ").append(metric.end());
            out.append(", \"mid_to_end\": ").append(metric.midToEnd());
            out.append(", \"passed\": ").append(metric.passed());
            out.append(", \"detail\": \"").append(escape(metric.detail())).append("\"}");
            out.append(index == metrics.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ],\n  \"samples\": ").append(samples.size()).append("\n}\n");
        return out.toString();
    }

    private static String text(String scenario, List<MetricResult> metrics) {
        StringBuilder out = new StringBuilder("endurance " + scenario + "\n");
        for (MetricResult metric : metrics) {
            out.append(metric.passed() ? "  ok   " : "  FAIL ")
                    .append(metric.name())
                    .append(": ")
                    .append(metric.detail())
                    .append('\n');
        }
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
