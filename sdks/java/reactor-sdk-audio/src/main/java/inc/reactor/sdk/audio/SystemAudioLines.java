package inc.reactor.sdk.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** {@link AudioLines} over the host's own devices. */
final class SystemAudioLines implements AudioLines {

    @Override
    public CaptureLine openCapture(PcmFormat format) {
        AudioFormat wanted = toAudioFormat(format);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, wanted);
        if (!AudioSystem.isLineSupported(info)) {
            throw new AudioDeviceException("no microphone on this host can capture " + describe(format)
                    + ", which is the only format the Reactor core carries");
        }
        try {
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(wanted);
            return new SystemCapture(line);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            throw new AudioDeviceException("this microphone could not be opened: " + e.getMessage(), e);
        }
    }

    @Override
    public PlaybackLine openPlayback(PcmFormat format) {
        AudioFormat wanted = toAudioFormat(format);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, wanted);
        if (!AudioSystem.isLineSupported(info)) {
            throw new AudioDeviceException("no speaker on this host can play " + describe(format)
                    + ", which is the only format the Reactor core carries");
        }
        try {
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(wanted);
            return new SystemPlayback(line);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            throw new AudioDeviceException("this speaker could not be opened: " + e.getMessage(), e);
        }
    }

    private static AudioFormat toAudioFormat(PcmFormat format) {
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                format.sampleRate(),
                PcmFormat.BITS_PER_SAMPLE,
                format.channels(),
                format.frameBytes(),
                format.sampleRate(),
                false);
    }

    private static String describe(PcmFormat format) {
        return format.sampleRate() + " Hz, " + format.channels() + " channel(s), signed 16-bit little-endian";
    }

    private record SystemCapture(TargetDataLine line) implements CaptureLine {

        @Override
        public void start() {
            line.start();
        }

        @Override
        public int read(byte[] buffer) {
            return line.read(buffer, 0, buffer.length);
        }

        @Override
        public void stop() {
            // stop() then flush(): stop alone leaves whatever is buffered, and a reader blocked in
            // read() returns only once there is nothing more coming.
            line.stop();
            line.flush();
        }

        @Override
        public void close() {
            line.close();
        }
    }

    private record SystemPlayback(SourceDataLine line) implements PlaybackLine {

        @Override
        public void start() {
            line.start();
        }

        @Override
        public int write(byte[] buffer, int length) {
            return line.write(buffer, 0, length);
        }

        @Override
        public void stop() {
            line.stop();
            line.flush();
        }

        @Override
        public void close() {
            line.close();
        }
    }
}
