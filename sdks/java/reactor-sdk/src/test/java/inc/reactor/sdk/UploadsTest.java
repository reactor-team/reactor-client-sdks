package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uploads, and the references they turn into. */
final class UploadsTest extends SendingFixture {

    private static final String UPLOADED =
            "{\"upload_id\":\"up_123\",\"name\":\"photo.jpg\",\"mime_type\":\"image/jpeg\",\"size\":2048}";

    @Test
    @DisplayName("a file upload sends the path, never the bytes")
    void aFileUploadSendsThePath(@TempDir Path directory) throws Exception {
        Path photo = Files.writeString(directory.resolve("photo.jpg"), "not really a jpeg");

        CompletableFuture<FileRef> pending = reactor.uploadFile(photo);
        fake.settleLastCall(true, UPLOADED, null);

        // The size of the file is the platform's business. Nothing here read it into the heap,
        // which is what makes a multi-gigabyte upload possible at all.
        assertEquals(photo.toAbsolutePath().toString(), fake.lastUploadPath);
        FileRef ref = pending.get();
        assertEquals("up_123", ref.uploadId());
        assertEquals("image/jpeg", ref.mimeType());
        assertEquals(2048L, ref.size());
    }

    @Test
    @DisplayName("a missing file fails by name, before anything reaches the FFI")
    void aMissingFileFailsByName() {
        CompletableFuture<FileRef> pending = reactor.uploadFile(Path.of("/no/such/photo.jpg"));

        ExecutionException thrown = assertThrows(ExecutionException.class, pending::get);
        ReactorException cause = assertInstanceOf(ReactorException.class, thrown.getCause());
        assertEquals("NOT_FOUND", cause.code());
        assertTrue(cause.getMessage().contains("photo.jpg"), cause.getMessage());
        // Handed to the FFI unchecked, this would come back as whatever the platform made of an
        // unreadable upload, which names neither the path nor the reason.
        assertEquals(null, fake.lastUploadPath);
    }

    @Test
    @DisplayName("a directory is refused rather than uploaded")
    void aDirectoryIsRefused(@TempDir Path directory) {
        CompletableFuture<FileRef> pending = reactor.uploadFile(directory);

        ExecutionException thrown = assertThrows(ExecutionException.class, pending::get);
        assertEquals(
                "NOT_FOUND",
                assertInstanceOf(ReactorException.class, thrown.getCause()).code());
    }

    @Test
    @DisplayName("uploaded bytes are readable for the whole call, and the completion may come later")
    void uploadedBytesAreBorrowedForTheCall() throws Exception {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};

        CompletableFuture<FileRef> pending = reactor.uploadBytes(data, "photo.jpg", "image/jpeg");

        // The fake read the buffer inside the call, which is the contract the header states:
        // borrowed for the call only, so a library copies what it keeps before returning.
        assertArrayEquals(data, fake.lastUploadBytes);

        // The arena holding those bytes is closed by now. A completion arriving afterwards must
        // still settle, because it reads nothing from it.
        fake.settleLastCall(true, UPLOADED, null);
        assertEquals("up_123", pending.get().uploadId());
    }

    @Test
    @DisplayName("an upload reaches a command under the parameter it fills")
    void anUploadReachesACommandByParameter() throws Exception {
        CompletableFuture<FileRef> uploading = reactor.uploadBytes(new byte[] {9}, "photo.jpg", "image/jpeg");
        fake.settleLastCall(true, UPLOADED, null);
        FileRef photo = uploading.get();

        CompletableFuture<Optional<CommandReply>> pending =
                reactor.sendCommand("set_image", JsonValue.object().build(), Map.of("image", photo));

        // Named, not embedded. Python can find a reference sitting inside the arguments because a
        // Python value carries its type; here the arguments are already a JSON tree, where a
        // reference looks like any other object.
        assertTrue(fake.lastUploadsJson.contains("\"image\""), fake.lastUploadsJson);
        assertTrue(fake.lastUploadsJson.contains("\"up_123\""), fake.lastUploadsJson);
        fake.settleLastCall(true, "{\"type\":\"ok\"}", null);
        assertEquals("ok", pending.get().orElseThrow().type().orElseThrow());
    }

    @Test
    @DisplayName("a reference can go inside the arguments where a parameter takes several files")
    void aReferenceCanGoInsideTheArguments() {
        FileRef first = new FileRef("up_1", "a.jpg", "image/jpeg", 10);
        FileRef second = new FileRef("up_2", "b.jpg", "image/jpeg", 20);

        JsonValue args = JsonValue.object()
                .putArray("images", java.util.List.of(first.toJsonValue(), second.toJsonValue()))
                .build();

        // A list has no named slot, so the references travel as ordinary values the platform
        // resolves on its side.
        assertTrue(args.toJsonString().contains("\"upload_id\":\"up_1\""), args.toJsonString());
        assertTrue(args.toJsonString().contains("\"upload_id\":\"up_2\""), args.toJsonString());
    }

    @Test
    @DisplayName("an upload that answers without an id is a decode failure, not a blank reference")
    void anIncompleteAnswerIsADecodeFailure() throws IOException {
        CompletableFuture<FileRef> pending = reactor.uploadBytes(new byte[] {1}, "x", "text/plain");

        fake.settleLastCall(true, "{\"name\":\"x\"}", null);

        ExecutionException thrown = assertThrows(ExecutionException.class, pending::get);
        ReactorException cause = assertInstanceOf(ReactorException.class, thrown.getCause());
        assertEquals("DECODE_FAILED", cause.code());
        assertTrue(cause.getMessage().contains("upload_id"), cause.getMessage());
    }

    @Test
    @DisplayName("an upload whose size is not a whole number of bytes is a decode failure")
    void aBadSizeIsRefusedAtTheUpload() {
        // Each of these used to produce a FileRef with size 0, or a truncated one, which was then
        // re-sent into a command as though the platform had said it.
        for (String size : java.util.List.of("\"12\"", "1.5", "-1", "null")) {
            String payload = "{\"upload_id\":\"u\",\"name\":\"n\",\"mime_type\":\"image/png\",\"size\":" + size + "}";
            ReactorException refused =
                    assertThrows(ReactorException.class, () -> FileRef.from(JsonValue.parse(payload)), size);
            assertEquals(ErrorCode.DECODE_FAILED.code(), refused.code(), size);
        }

        // A missing field is the same answer, not a default.
        assertThrows(
                ReactorException.class, () -> FileRef.from(JsonValue.parse("{\"upload_id\":\"u\",\"name\":\"n\"}")));
    }

    @Test
    @DisplayName("a size the platform states exactly comes through exactly")
    void aGoodSizeIsKept() {
        FileRef ref = FileRef.from(JsonValue.parse(
                "{\"upload_id\":\"u\",\"name\":\"n\",\"mime_type\":\"image/png\",\"size\":9007199254740993}"));
        assertEquals(9_007_199_254_740_993L, ref.size());
    }
}
