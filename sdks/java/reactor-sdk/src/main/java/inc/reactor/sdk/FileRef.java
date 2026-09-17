package inc.reactor.sdk;

import java.util.Map;

/**
 * A file the platform is holding, ready to be passed into a command.
 *
 * <p>Returned by {@link Reactor#uploadFile} and {@link Reactor#uploadBytes}. Uploads are named
 * separately from a command's arguments rather than embedded in them:
 *
 * <pre>{@code
 * FileRef photo = reactor.uploadFile(Path.of("photo.jpg")).join();
 * reactor.sendCommand("set_image", JsonValue.object().build(), Map.of("image", photo)).join();
 * }</pre>
 *
 * <p>The Python SDK can find one of these sitting inside a command's arguments and lift it out,
 * because a Python value carries its type at run time. Java's arguments are a {@link JsonValue}
 * tree by then, where a reference is indistinguishable from any other object, so naming the
 * parameter is explicit here for the same reason it is in the C++ SDK: a few more characters, and
 * it cannot silently miss one.
 *
 * <p>Where a parameter takes several files — a list, or a field of an object — there is no named
 * slot for them, so {@link #toJsonValue()} puts the reference into the arguments instead.
 *
 * @param uploadId what the platform calls this upload
 * @param name the file's name
 * @param mimeType what the platform decided this is
 * @param size how many bytes it holds
 */
public record FileRef(String uploadId, String name, String mimeType, long size) {

    /**
     * This reference as the platform reads it.
     *
     * @return the JSON object to put inside a command's arguments, where no named slot exists
     */
    public JsonValue toJsonValue() {
        return new JsonValue.JsonObject(Map.of(
                "upload_id", JsonValue.of(uploadId),
                "name", JsonValue.of(name),
                "mime_type", JsonValue.of(mimeType),
                "size", JsonValue.of(size)));
    }

    /**
     * Reads one from what an upload answered.
     *
     * @param value the completion's payload
     * @return the reference
     * @throws ReactorException with {@link ErrorCode#DECODE_FAILED} when the payload is not one
     */
    public static FileRef from(JsonValue value) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw ReactorException.of(
                    ErrorCode.DECODE_FAILED.code(),
                    "an upload answered with something that is not an object",
                    null,
                    "upload",
                    null);
        }
        return new FileRef(
                required(object, "upload_id"),
                required(object, "name"),
                object.getString("mime_type").orElse("application/octet-stream"),
                object.getNumber("size").map(Double::longValue).orElse(0L));
    }

    private static String required(JsonValue.JsonObject object, String field) {
        return object.getString(field)
                .orElseThrow(() -> ReactorException.of(
                        ErrorCode.DECODE_FAILED.code(),
                        "an upload answered without a \"" + field + "\"",
                        null,
                        "upload",
                        null));
    }
}
