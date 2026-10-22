package inc.reactor.sdk;

import java.util.List;
import java.util.Optional;

/**
 * What one {@link Reactor#step step} produced: its number in the session, per output track the
 * frames it generated, the files the model kept with it, and — when the step ran but the model
 * reported a failure — why.
 *
 * <pre>{@code
 * StepResult result = reactor.step(state, List.of()).join();
 * result.error().ifPresent(why -> System.err.println("step " + result.step() + " failed: " + why));
 * for (StepFrame frame : result.track("main_video").frames()) {
 *     show(frame.data(), frame.shape());      // (height, width, 3) uint8 RGB
 * }
 * }</pre>
 *
 * <p>Copied out of the native library once, when the step completed; nothing here points into
 * native memory, so it can be kept and shared freely.
 *
 * @param step the step's number in the session, counted from 1: the {@code step} of the session's
 *     step routes ({@code GET /sessions/{id}/steps/{step}})
 * @param tracks the output tracks, in the order the runtime sent them
 * @param elapsed seconds the step's {@code generate()} took, or the time the model reported for the
 *     step
 * @param files the extra files the model kept with the step, in the order the runtime listed them;
 *     empty when it kept none or the result was not saved. Only named here: see {@link StepFile}
 *     for fetching their bytes
 * @param error why the step failed, when it ran and the model reported a failure; empty when it
 *     worked. A step that did not run has no result: its future fails with {@link
 *     StepRefusedException}, {@link StepNotReadyException} or {@link StepCommandFailedException}
 */
public record StepResult(
        long step, List<StepTrackOutput> tracks, double elapsed, List<StepFile> files, Optional<String> error) {

    /** Copies {@code tracks} and {@code files}, so the record is as immutable as it looks. */
    public StepResult {
        tracks = List.copyOf(tracks);
        files = List.copyOf(files);
        java.util.Objects.requireNonNull(error, "error");
    }

    /**
     * A result that kept no files and did not fail.
     *
     * @param step the step's number in the session, counted from 1
     * @param tracks the output tracks, in the order the runtime sent them
     * @param elapsed seconds the step took
     */
    public StepResult(long step, List<StepTrackOutput> tracks, double elapsed) {
        this(step, tracks, elapsed, List.of(), Optional.empty());
    }

    /**
     * A result with an {@code elapsed} of zero, as the other SDKs default it, that kept no files and
     * did not fail.
     *
     * @param step the step's number in the session, counted from 1
     * @param tracks the output tracks, in the order the runtime sent them
     */
    public StepResult(long step, List<StepTrackOutput> tracks) {
        this(step, tracks, 0.0);
    }

    /**
     * The output of one track, by name.
     *
     * @param name the track's declared name
     * @return its output
     * @throws ReactorException ({@code NOT_FOUND}) naming the tracks that did produce output, when
     *     this one produced none: a model can legitimately leave a track out of a step, and that is
     *     worth telling apart from a typo
     */
    public StepTrackOutput track(String name) {
        return findTrack(name)
                .orElseThrow(() -> ReactorException.of(
                        ErrorCode.NOT_FOUND.code(),
                        "step " + step + " produced no output on \"" + name + "\". It produced: "
                                + tracks.stream().map(StepTrackOutput::name).toList() + ".",
                        null,
                        "step",
                        null));
    }

    /**
     * @param name the track's declared name
     * @return its output, or empty when the step produced none on it
     */
    public Optional<StepTrackOutput> findTrack(String name) {
        return tracks.stream().filter(track -> track.name().equals(name)).findFirst();
    }
}
