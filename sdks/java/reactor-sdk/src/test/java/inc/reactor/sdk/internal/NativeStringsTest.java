package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three string contracts, driven through the real downcall path.
 *
 * <p>Each case is one of the three ways to get this wrong: freeing the static one corrupts the
 * heap, freeing a borrowed one is a double free, and not freeing the owned ones leaks on every
 * property read.
 */
final class NativeStringsTest {

    @Test
    @DisplayName("an owned string is read and freed exactly once")
    void ownedStringIsFreedExactlyOnce() throws Throwable {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            Ffi ffi = Ffi.open(fake.lookup());
            fake.nextOwnedString = "session-abc123";

            MethodHandle sessionId = ffi.handle(Ffi.Symbol.SESSION_ID);
            MethodHandle freeString = ffi.handle(Ffi.Symbol.FREE_STRING);
            MemorySegment returned = (MemorySegment) sessionId.invokeExact(MemorySegment.NULL);

            String value = NativeStrings.takeOwned(returned, freeString);

            assertEquals("session-abc123", value);
            assertEquals(1, fake.freeCallCount(), "an owned string must be freed once, not zero or twice");
            assertTrue(fake.ownedStringsFreedExactlyOnce());
        }
    }

    @Test
    @DisplayName("a static string is read and never freed")
    void staticStringIsNeverFreed() throws Throwable {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            Ffi ffi = Ffi.open(fake.lookup());

            MethodHandle status = ffi.handle(Ffi.Symbol.STATUS);
            MemorySegment returned = (MemorySegment) status.invokeExact(MemorySegment.NULL);

            assertEquals("ready", NativeStrings.staticRef(returned));
            assertEquals(0, fake.freeCallCount(), "a static string must never reach reactor_free_string");
            assertFalse(fake.aStaticStringWasFreed());
        }
    }

    @Test
    @DisplayName("a borrowed string is read and never freed, and NULL reads as null")
    void borrowedStringIsNeverFreed() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            Ffi ffi = Ffi.open(fake.lookup());
            assertEquals(0, fake.freeCallCount());
            assertNull(NativeStrings.borrow(MemorySegment.NULL), "a NULL borrowed string reads as null");
            assertNull(NativeStrings.borrow(null));
            assertEquals(0, fake.freeCallCount(), "borrow must never free");
            assertNotNullHandle(ffi);
        }
    }

    @Test
    @DisplayName("an owned NULL is null, and nothing is freed")
    void ownedNullIsNull() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            Ffi ffi = Ffi.open(fake.lookup());
            assertNull(NativeStrings.takeOwned(MemorySegment.NULL, ffi.handle(Ffi.Symbol.FREE_STRING)));
            assertEquals(0, fake.freeCallCount(), "there is nothing to free about a NULL");
        }
    }

    @Test
    @DisplayName("non-ASCII survives the round trip")
    void nonAsciiRoundTrips() throws Throwable {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            Ffi ffi = Ffi.open(fake.lookup());
            // A model name, an emoji and a combining accent: three different ways a byte count and
            // a character count disagree.
            fake.nextOwnedString = "modelo-ação 🎥 café";

            MemorySegment returned =
                    (MemorySegment) ffi.handle(Ffi.Symbol.SESSION_ID).invokeExact(MemorySegment.NULL);

            assertEquals("modelo-ação 🎥 café", NativeStrings.takeOwned(returned, ffi.handle(Ffi.Symbol.FREE_STRING)));
        }
    }

    private static void assertNotNullHandle(Ffi ffi) {
        assertTrue(ffi.handle(Ffi.Symbol.STATUS) != null);
    }
}
