package inc.reactor.sdk;

import java.nio.file.Path;

/**
 * A clip, assembled into one playable file.
 *
 * <p>The assembly happens in the native layer rather than here, because it has three rules that
 * each cost a shipped bug to learn: the init segment arrives as a comment line in the playlist and
 * carries the header every fragment is parsed against, a segment can be presigned on another host
 * which rejects an Authorization header rather than ignoring it, and a "not ready yet" is not an
 * error.
 *
 * @param path the file that was written
 * @param bytes how large it is
 * @param segments how many segments went into it
 */
public record DownloadedClip(Path path, long bytes, long segments) {

    /**
     * Reads one from what a download answered.
     *
     * @param value the completion's payload
     * @return the result
     * @throws ReactorException with {@link ErrorCode#DECODE_FAILED} when the payload is not one
     */
    public static DownloadedClip from(JsonValue value) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw ReactorException.of(
                    ErrorCode.DECODE_FAILED.code(),
                    "a download answered with something that is not an object",
                    null,
                    "download_clip",
                    null);
        }
        return new DownloadedClip(
                Path.of(object.getString("path")
                        .orElseThrow(() -> ReactorException.of(
                                ErrorCode.DECODE_FAILED.code(),
                                "a download answered without a \"path\"",
                                null,
                                "download_clip",
                                null))),
                object.getNumber("bytes").map(Double::longValue).orElse(0L),
                object.getNumber("segments").map(Double::longValue).orElse(0L));
    }
}
