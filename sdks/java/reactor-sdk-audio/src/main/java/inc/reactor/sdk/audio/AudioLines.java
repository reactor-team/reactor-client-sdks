package inc.reactor.sdk.audio;

/**
 * Where audio hardware comes from.
 *
 * <p>A seam, so the tests can drive {@link Microphone} and {@link Speaker} against lines that do
 * what a test needs — block, fail, disappear mid-read — without a sound card. The real
 * implementation is {@link #system()}, over {@code javax.sound.sampled}.
 *
 * <p>The interfaces below are deliberately smaller than {@code TargetDataLine} and {@code
 * SourceDataLine}: they are what this module actually uses, which is what a fake has to be able to
 * imitate honestly.
 */
public interface AudioLines {

    /** A line being read from — a microphone. */
    interface CaptureLine extends AutoCloseable {

        /** Starts capturing. */
        void start();

        /**
         * Reads the next bytes available.
         *
         * <p>Blocks until there are some. {@link #stop()} is what unblocks it.
         *
         * @param buffer where to put them
         * @return how many bytes were read, or -1 when the line has stopped
         */
        int read(byte[] buffer);

        /**
         * Stops capturing, and unblocks a read that is waiting.
         *
         * <p>Separate from {@link #close()} on purpose: closing a line that a thread is blocked
         * reading is how this deadlocks.
         */
        void stop();

        @Override
        void close();
    }

    /** A line being written to — a speaker. */
    interface PlaybackLine extends AutoCloseable {

        /** Starts playing. */
        void start();

        /**
         * Writes bytes to play.
         *
         * <p>Blocks when the line's buffer is full. {@link #stop()} is what unblocks it.
         *
         * @param buffer the bytes
         * @param length how many of them
         * @return how many were written
         */
        int write(byte[] buffer, int length);

        /** Stops playing, discards what is buffered, and unblocks a write that is waiting. */
        void stop();

        @Override
        void close();
    }

    /**
     * Opens a microphone.
     *
     * @param format what to capture
     * @return the line
     * @throws AudioDeviceException when no device can provide it
     */
    CaptureLine openCapture(PcmFormat format);

    /**
     * Opens a speaker.
     *
     * @param format what to play
     * @return the line
     * @throws AudioDeviceException when no device can provide it
     */
    PlaybackLine openPlayback(PcmFormat format);

    /**
     * The host's own audio devices.
     *
     * @return lines over {@code javax.sound.sampled}
     */
    static AudioLines system() {
        return new SystemAudioLines();
    }
}
