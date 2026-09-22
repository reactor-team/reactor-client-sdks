/**
 * The listener the sanitizer harness hands to the bridge.
 *
 * Java rather than Kotlin so the harness needs javac and nothing else — pulling the Kotlin
 * compiler into a native test build would make a C++ sanitizer run depend on the Android
 * toolchain. The bridge resolves these by name and signature, so this is exactly as good a
 * stand-in as the real NativeEvents.
 */
public class AsanListener {
    public static volatile int statusCount = 0;
    public static volatile int sessionIdCount = 0;
    public static volatile String lastStatus = null;
    public static volatile boolean lastSessionIdWasNull = false;
    public static volatile boolean throwFromHandlers = false;

    public void onStatus(String status) {
        statusCount++;
        lastStatus = status;
        if (throwFromHandlers) throw new RuntimeException("handler bug");
    }

    public void onError(String errorJson) {
        if (throwFromHandlers) throw new RuntimeException("handler bug");
    }

    public static volatile int videoFrameCount = 0;
    public static volatile int lastWidth = 0;
    public static volatile int lastHeight = 0;
    public static volatile long lastFrameId = 0;
    public static volatile int lastPixelCapacity = 0;
    public static volatile boolean lastBufferWasDirect = false;
    public static volatile int lastTagLength = -1;

    public void onVideoFrame(String trackName, java.nio.ByteBuffer pixels, int width, int height,
                             long frameId, long timestampUs, byte[] userData) {
        videoFrameCount++;
        lastWidth = width;
        lastHeight = height;
        lastFrameId = frameId;
        lastPixelCapacity = pixels == null ? -1 : pixels.capacity();
        lastBufferWasDirect = pixels != null && pixels.isDirect();
        lastTagLength = userData == null ? -1 : userData.length;
    }

    public void onAudioFrame(String trackName, java.nio.ByteBuffer pcm, int sampleCount,
                             int sampleRate, int channels) {
    }

    public void onSessionId(String sessionId) {
        sessionIdCount++;
        lastSessionIdWasNull = sessionId == null;
        if (throwFromHandlers) throw new RuntimeException("handler bug");
    }
}
