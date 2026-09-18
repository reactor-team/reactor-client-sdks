package inc.reactor.sdk.audio;

import inc.reactor.sdk.AudioFrame;
import inc.reactor.sdk.Media;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Frames for tests, built the way the SDK builds them.
 *
 * <p>{@link AudioFrame}'s constructor is package-private so nothing outside the core can invent
 * one; {@link Media} is the way in that the internals use, and using the same way here keeps these
 * frames honest rather than a different shape that happens to compile.
 */
final class TestFrames {

    /**
     * An automatic arena, so a frame built here survives as long as the test holds it. Real frames
     * are scoped to their callback on purpose — that is what makes keeping one throw — and a test
     * asserting on a speaker's behaviour should not have to reproduce that too.
     */
    private static final Arena ARENA = Arena.ofAuto();

    private TestFrames() {}

    static AudioFrame audio(short[] pcm, int sampleRate, int channels) {
        MemorySegment samples = ARENA.allocateFrom(ValueLayout.JAVA_SHORT, pcm);
        return Media.audioFrame(samples, pcm.length, sampleRate, channels);
    }
}
