package inc.reactor.sdk.internal;

import inc.reactor.sdk.ErrorCode;
import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.ReactorException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Exchanging an API key for a token.
 *
 * <p>{@code reactor_fetch_jwt} takes no handle, so this is not bounded by any client's lifetime —
 * it goes through {@link DetachedCompletion} for the same reason a clip download does. It also
 * means a null argument here <i>completes with an error</i> rather than returning without
 * completing: there is no handle a caller could have been expected to check, so the alternative
 * would be a future nothing ever settles.
 */
public final class Authentication {

    private Authentication() {}

    /**
     * Puts extra headers into a mint's options as {@code extra_headers}.
     *
     * @param options the caller's options, or {@code null} for none
     * @param extraHeaders the headers; empty returns {@code options} untouched
     * @return the options to send; an {@code extra_headers} entry already in {@code options} is
     *     replaced
     * @throws IllegalArgumentException when there are headers and {@code options} is not an object
     */
    public static @Nullable JsonValue withExtraHeaders(@Nullable JsonValue options, Map<String, String> extraHeaders) {
        Objects.requireNonNull(extraHeaders, "extraHeaders");
        if (extraHeaders.isEmpty()) {
            return options;
        }
        Map<String, JsonValue> merged = new LinkedHashMap<>();
        if (options instanceof JsonValue.JsonObject object) {
            merged.putAll(object.fields());
        } else if (options != null) {
            throw new IllegalArgumentException("options must be a JSON object to carry extraHeaders");
        }
        merged.put("extra_headers", extraHeadersObject(extraHeaders));
        return new JsonValue.JsonObject(merged);
    }

    /**
     * Headers as the JSON object the native side reads them from: string values only, and the
     * names left exactly as given, since validating them is the core's job.
     *
     * @param headers the headers
     * @return an object of strings
     */
    public static JsonValue extraHeadersObject(Map<String, String> headers) {
        JsonValue.ObjectBuilder object = JsonValue.object();
        headers.forEach(object::put);
        return object.build();
    }

    /**
     * Exchanges an API key for a JWT.
     *
     * @param ffi the bound ABI
     * @param apiUrl the coordinator's base URL
     * @param apiKey the key to exchange
     * @param optionsJson scoping options as JSON, or {@code null} for a token carrying everything
     *     the key's roles allow — fine server to server, wrong to hand to a client you do not
     *     control
     * @param local whether to accept a dev coordinator's certificate
     * @return the token
     */
    public static CompletableFuture<String> fetchJwt(
            Ffi ffi, String apiUrl, String apiKey, @Nullable String optionsJson, boolean local) {
        return DetachedCompletion.start("fetch_jwt", Authentication::decodeJwt, (callback, userdata) -> {
            try (Arena call = Arena.ofConfined()) {
                ffi.handle(Ffi.Symbol.FETCH_JWT)
                        .invokeWithArguments(
                                call.allocateFrom(apiUrl),
                                call.allocateFrom(apiKey),
                                optionsJson == null ? MemorySegment.NULL : call.allocateFrom(optionsJson),
                                local ? 1 : 0,
                                callback,
                                userdata);
            } catch (Throwable t) {
                throw new IllegalStateException("reactor_fetch_jwt could not be called", t);
            }
        });
    }

    private static String decodeJwt(@Nullable String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            throw ReactorException.of(
                    ErrorCode.DECODE_FAILED.code(),
                    "the coordinator answered the key exchange with nothing",
                    null,
                    "fetch_jwt",
                    null);
        }
        JsonValue parsed = JsonBridge.parse(resultJson);
        if (!(parsed instanceof JsonValue.JsonObject object)) {
            throw ReactorException.of(
                    ErrorCode.DECODE_FAILED.code(),
                    "the key exchange answered with something that is not an object",
                    null,
                    "fetch_jwt",
                    null);
        }
        return object.getString("jwt")
                .orElseThrow(() -> ReactorException.of(
                        ErrorCode.DECODE_FAILED.code(),
                        "the key exchange answered without a \"jwt\"",
                        null,
                        "fetch_jwt",
                        null));
    }
}
