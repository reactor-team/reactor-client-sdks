package inc.reactor.sdk;

import java.util.List;

/**
 * The frames one output track produced in a {@link Reactor#step step}, in production order.
 *
 * @param name the output track's declared name
 * @param kind whether it carries video or audio
 * @param rate the rate the track's frames play at, as the runtime reported it: frames per second
 *     for video, samples per second for audio; {@code 0} when the runtime did not say
 * @param frames what it produced; may be empty, since a model can legitimately produce nothing on a
 *     track in a step
 */
public record StepTrackOutput(String name, TrackKind kind, double rate, List<StepFrame> frames) {

    /** Copies {@code frames}, so the record is as immutable as it looks. */
    public StepTrackOutput {
        frames = List.copyOf(frames);
    }

    /**
     * A track whose rate the runtime did not report.
     *
     * @param name the output track's declared name
     * @param kind whether it carries video or audio
     * @param frames what it produced, in production order
     */
    public StepTrackOutput(String name, TrackKind kind, List<StepFrame> frames) {
        this(name, kind, 0.0, frames);
    }
}
