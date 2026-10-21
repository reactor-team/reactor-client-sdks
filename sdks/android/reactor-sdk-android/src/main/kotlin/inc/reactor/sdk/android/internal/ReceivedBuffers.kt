package inc.reactor.sdk.android.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * What a frame handler sees of a received frame's memory: read-only views.
 *
 * The native layer hands over its own decode storage through `NewDirectByteBuffer`, which knows
 * nothing of `const` and makes a writable buffer. A handler that wrote into it — an accidental
 * `put` while reusing the buffer — would corrupt memory the FFI still owns. Read-only, the same
 * mistake is a `ReadOnlyBufferException` at the line that made it.
 */
internal object ReceivedBuffers {
    /** BGRA pixels, as given. */
    fun pixels(native: ByteBuffer): ByteBuffer = native.asReadOnlyBuffer()

    /**
     * Interleaved 16-bit PCM, in the platform's byte order.
     *
     * Viewed as shorts before being made read-only: `ByteBuffer.asReadOnlyBuffer` resets the
     * order to big-endian, while a `ShortBuffer`'s read-only view keeps the one it was built with.
     */
    fun samples(native: ByteBuffer): ShortBuffer = native.order(ByteOrder.nativeOrder()).asShortBuffer().asReadOnlyBuffer()
}
