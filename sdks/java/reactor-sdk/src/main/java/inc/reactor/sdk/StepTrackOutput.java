package inc.reactor.sdk;

import java.util.List;

/**
 * The frames one output track produced in a {@link Reactor#step step}, in production order.
 *
 * @param name the output track's declared name
 * @param kind whether it carries video or audio
 * @param frames what it produced; may be empty, since a model can legitimately produce nothing on a
 *     track in a step
 */
public record StepTrackOutput(String name, TrackKind kind, List<StepFrame> frames) {

    /** Copies {@code frames}, so the record is as immutable as it looks. */
    public StepTrackOutput {
        frames = List.copyOf(frames);
    }
}
