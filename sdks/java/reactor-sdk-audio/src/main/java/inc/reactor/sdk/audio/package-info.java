/**
 * Microphone and speaker helpers, over {@code javax.sound.sampled}.
 *
 * <p>Nothing here opens a device on its own. The core pins the FFI's synthetic audio module, so a
 * model declaring a sendonly audio track cannot put a live microphone on the wire; capturing is an
 * explicit call to {@link inc.reactor.sdk.audio.Microphone#open}, from an artifact an application
 * has to ask for.
 */
@NullMarked
package inc.reactor.sdk.audio;

import org.jspecify.annotations.NullMarked;
