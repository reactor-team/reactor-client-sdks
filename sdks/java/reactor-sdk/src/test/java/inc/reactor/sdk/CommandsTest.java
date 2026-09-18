package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Correlated commands, schemas and stats. */
final class CommandsTest extends SendingFixture {

    @Test
    @DisplayName("the reply comes from the command's own completion, not from a later event")
    void theReplyComesFromTheCompletion() throws Exception {
        CompletableFuture<Optional<CommandReply>> pending = reactor.sendCommand(
                "describe", JsonValue.object().put("detail", "high").build());

        fake.settleLastCall(true, "{\"type\":\"caption\",\"data\":{\"text\":\"a cat\"}}", null);

        CommandReply reply = pending.get().orElseThrow();
        assertEquals("caption", reply.type().orElseThrow());
        assertEquals(
                "a cat",
                assertInstanceOf(JsonValue.JsonObject.class, reply.dataOrNull())
                        .getString("text")
                        .orElseThrow());
    }

    @Test
    @DisplayName("a command acknowledged without a message answers with nothing, not an empty reply")
    void anAcknowledgementIsEmpty() throws Exception {
        CompletableFuture<Optional<CommandReply>> pending = reactor.sendCommand("set_brightness");

        fake.settleLastCall(true, null, null);

        assertTrue(pending.get().isEmpty(), "an acknowledgement is absent, not an empty CommandReply");
    }

    @Test
    @DisplayName("a schema that will not parse is a decode failure, not an empty schema")
    void anUnparseableSchemaIsADecodeFailure() {
        CompletableFuture<JsonValue> pending = reactor.requestSchema();

        fake.settleLastCall(true, "{not json", null);

        ExecutionException thrown = assertThrows(ExecutionException.class, pending::get);
        // Answering with an empty object here would make a model that declares nothing
        // indistinguishable from a reply nobody could read.
        assertEquals(
                "DECODE_FAILED",
                assertInstanceOf(ReactorException.class, thrown.getCause()).code());
    }

    @Test
    @DisplayName("an absent schema is an empty one, which is a different answer")
    void anAbsentSchemaIsEmpty() throws Exception {
        CompletableFuture<JsonValue> pending = reactor.requestSchema();

        fake.settleLastCall(true, null, null);

        assertEquals(
                0,
                assertInstanceOf(JsonValue.JsonObject.class, pending.get())
                        .fields()
                        .size());
    }

    @Test
    @DisplayName("a command that fails carries the model's own code through")
    void aFailedCommandCarriesItsCode() {
        CompletableFuture<Optional<CommandReply>> pending = reactor.sendCommand("rewind");

        // A code the platform sent that this SDK has never heard of. It is a failure that could
        // not be classified, not a payload that failed to parse.
        fake.settleLastCall(
                false, null, "{\"code\":\"MODEL_REFUSED\",\"message\":\"no snapshot\",\"recoverable\":false}");

        ExecutionException thrown = assertThrows(ExecutionException.class, pending::get);
        ReactorException cause = assertInstanceOf(ReactorException.class, thrown.getCause());
        assertEquals("MODEL_REFUSED", cause.code());
        assertEquals(ReactorException.class, cause.getClass());
    }

    @Test
    @DisplayName("stats keep everything the platform reported, including what this SDK does not name")
    void statsKeepEverything() throws Exception {
        CompletableFuture<Stats> pending = reactor.getStats();

        fake.settleLastCall(true, "{\"rtt_ms\":42,\"a_measurement_added_later\":7,\"state\":\"live\"}", null);

        Stats stats = pending.get();
        assertEquals(42.0, stats.number("rtt_ms").orElseThrow());
        assertEquals("live", stats.text("state").orElseThrow());
        assertEquals(
                7.0,
                stats.number("a_measurement_added_later").orElseThrow(),
                "a measurement this SDK does not name must survive rather than be dropped");
    }

    @Test
    @DisplayName("arguments reach the FFI as JSON text")
    void argumentsAreSerialised() throws Exception {
        CompletableFuture<Optional<CommandReply>> pending = reactor.sendCommand(
                "configure", JsonValue.object().put("fps", 30).put("hdr", true).build());

        // What the FFI was actually handed, rather than only what came back. Whole numbers go out
        // without a trailing decimal, because a model reading an fps expects 30 and not 30.0.
        assertEquals("{\"fps\":30,\"hdr\":true}", fake.lastCommandArgs);

        fake.settleLastCall(true, "{\"type\":\"ok\"}", null);
        assertEquals("ok", pending.get().orElseThrow().type().orElseThrow());
    }
}
