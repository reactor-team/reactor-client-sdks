package inc.reactor.sdk;

/**
 * A clip or recording the platform is assembling.
 *
 * <p>Requesting one does not mean it is ready. Readiness is in media time, not wall clock: the
 * manifest appears once the recording passes the end of the chunk holding the window, and that
 * chunk closes because the model keeps generating. A model producing at a tenth of real time
 * reaches it ten times later.
 *
 * <p>{@link #predictedReadyAtMs()} is the runtime's own guess — a wall clock plus media seconds —
 * so it is only right for a model generating at real time. {@link Reactor#downloadClip} waits from
 * there, and bounds the wait on the session still being alive rather than on a number: once the
 * session is gone, a "not yet" is a "not yet" forever.
 *
 * @param sessionId the session this came from
 * @param kind what was recorded
 * @param startMarker where the window begins, in media time
 * @param endMarker where it ends, in media time
 * @param nowMarker where the recording had reached when this was asked for
 * @param predictedReadyAtMs the runtime's own prediction, in Unix milliseconds, or 0 when it
 *     offered none
 * @param playlistUrl the HLS playlist naming the segments
 */
public record Clip(
        String sessionId,
        String kind,
        double startMarker,
        double endMarker,
        double nowMarker,
        double predictedReadyAtMs,
        String playlistUrl) {

    /**
     * Reads one from what a clip request answered.
     *
     * @param value the completion's payload
     * @return the clip
     * @throws ReactorException with {@link ErrorCode#DECODE_FAILED} when the payload is not one
     */
    public static Clip from(JsonValue value) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw ReactorException.of(
                    ErrorCode.DECODE_FAILED.code(),
                    "a clip request answered with something that is not an object",
                    null,
                    "request_clip",
                    null);
        }
        return new Clip(
                object.getString("session_id").orElse(""),
                object.getString("kind").orElse(""),
                object.getNumber("start_marker").orElse(0.0),
                object.getNumber("end_marker").orElse(0.0),
                object.getNumber("now_marker").orElse(0.0),
                object.getNumber("predicted_ready_at_ms").orElse(0.0),
                object.getString("playlist_url")
                        .orElseThrow(() -> ReactorException.of(
                                ErrorCode.DECODE_FAILED.code(),
                                "a clip request answered without a \"playlist_url\", so there is nothing"
                                        + " to download",
                                null,
                                "request_clip",
                                null)));
    }
}
