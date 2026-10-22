package inc.reactor.examples;

import inc.reactor.sdk.Generation;
import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.StepCommand;
import inc.reactor.sdk.StepFile;
import inc.reactor.sdk.StepFrame;
import inc.reactor.sdk.StepResult;
import inc.reactor.sdk.StepTrackOutput;
import inc.reactor.sdk.Track;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 09 · Stepped generation — one step at a time instead of a stream.
 *
 * <pre>
 * export REACTOR_API_KEY=rk_...
 * REACTOR_MODEL=owner/model mise run example:java 09
 * </pre>
 *
 * <p>A client created with {@code generation(Generation.STEPPED)} asks for a session that generates
 * only when told to. Each {@link Reactor#step} sends the model's state, a list of commands and every
 * frame pushed since the previous step, and resolves with what the model produced on each output
 * track — as arrays, not a video stream — plus the names of any files the model kept with the
 * step, and its error when the model reported the step as failed.
 *
 * <p><b>Pushing into an input track sends nothing in a stepped session</b>: the frame waits for the
 * next step. A runtime without stepped generation ignores the request, so {@code connect()} checks
 * and fails with {@code GenerationModeMismatchException} rather than leaving this waiting on a
 * stream.
 *
 * <p>There is no default model: set {@code REACTOR_MODEL} to one with stepped generation. Set
 * {@code REACTOR_INPUT_TRACK} to a sendonly video track it declares to push a frame into every
 * step, and {@code REACTOR_STEP_COMMAND} to a command it takes with no arguments to run it in every
 * step. {@code REACTOR_PROMPT} sets the state's {@code prompt}; {@code REACTOR_STEPS} how many steps
 * to run.
 */
public final class Example09SteppedGeneration {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;

    private Example09SteppedGeneration() {}

    /**
     * @param args ignored
     * @throws Exception when the run fails
     */
    public static void main(String[] args) throws Exception {
        String model = System.getenv("REACTOR_MODEL");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("set REACTOR_MODEL to a model with stepped generation, as owner/name");
        }
        int steps = Integer.parseInt(System.getenv().getOrDefault("REACTOR_STEPS", "3"));
        String prompt = System.getenv().getOrDefault("REACTOR_PROMPT", "a lighthouse in a storm");
        String inputTrack = System.getenv("REACTOR_INPUT_TRACK");
        String command = System.getenv("REACTOR_STEP_COMMAND");

        try (Reactor reactor = Reactor.open(ReactorOptions.builder(Examples.apiUrl(), model)
                .apiKey(Examples.apiKey())
                .generation(Generation.STEPPED)
                .build())) {

            reactor.connect().join();
            // Past connect() the session exists on the server: a failure below still
            // ends it, or it would linger until it expired.
            try {
                System.out.println("session: " + reactor.sessionId().orElse("?"));

                Track source = null;
                if (inputTrack != null && !inputTrack.isBlank()) {
                    source = reactor.track(inputTrack);
                    source.publish().join();
                }

                for (int seq = 0; seq < steps; seq++) {
                    if (source != null) {
                        // Buffered, not sent: the step below carries it.
                        source.pushFrame(solidFrame(seq), WIDTH, HEIGHT);
                    }

                    // State on the first step only: the model keeps it until changed.
                    JsonValue state =
                            seq == 0 ? JsonValue.object().put("prompt", prompt).build() : null;
                    List<StepCommand> commands =
                            command == null || command.isBlank() ? List.of() : List.of(StepCommand.of(command));

                    StepResult result = reactor.step(state, commands).join();
                    System.out.printf(
                            java.util.Locale.ROOT, "step %d: generated in %.3f s%n", result.step(), result.elapsed());
                    // A step the model reported as failed still completes; only a refused one throws.
                    result.error()
                            .ifPresent(why -> System.out.println("step " + result.step() + ": model error: " + why));
                    for (StepFile file : result.files()) {
                        // Named only: fetch the bytes over HTTP from the session's step results.
                        System.out.println("step " + result.step() + ": kept " + file.name() + " (" + file.contentType()
                                + ", " + file.size() + " bytes)");
                    }

                    for (StepTrackOutput track : result.tracks()) {
                        String shapes = track.frames().stream()
                                .map(StepFrame::shape)
                                .map(Arrays::toString)
                                .collect(Collectors.joining(", "));
                        System.out.println("step " + result.step() + ": " + track.name() + " ("
                                + track.kind().name().toLowerCase(java.util.Locale.ROOT) + ") "
                                + track.frames().size() + " frame(s) " + shapes);
                    }
                }

            } catch (RuntimeException | Error failure) {
                try {
                    reactor.disconnect().join();
                } catch (RuntimeException alsoFailed) {
                    failure.addSuppressed(alsoFailed);
                }
                throw failure;
            }
            reactor.disconnect().join();
        }
    }

    /** A solid BGRA frame whose colour follows {@code seq}. */
    private static byte[] solidFrame(int seq) {
        byte[] bgra = new byte[WIDTH * HEIGHT * 4];
        for (int at = 0; at < bgra.length; at += 4) {
            bgra[at] = (byte) (seq * 7);
            bgra[at + 1] = (byte) (seq * 13);
            bgra[at + 2] = (byte) (seq * 29);
            bgra[at + 3] = (byte) 255;
        }
        return bgra;
    }
}
