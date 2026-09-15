package inc.reactor.sdk.internal

/** The caller holds a handle lease across every invocation except detached authentication. */
internal object NativeClient {
    external fun create(
        apiUrl: ByteArray,
        model: ByteArray,
        token: ByteArray?,
        local: Boolean,
        version: ByteArray,
        events: Any,
        media: Any?,
    ): Long

    external fun destroy(handle: Long): Int

    external fun start(
        handle: Long,
        operation: Int,
        session: ByteArray?,
        connection: Long,
        receiver: Any,
    )

    external fun unpublish(
        handle: Long,
        name: ByteArray,
    ): ByteArray?

    external fun bitrate(
        handle: Long,
        name: ByteArray?,
        min: Int,
        start: Int,
        max: Int,
        receiver: Any,
    )

    external fun pushVideo(
        handle: Long,
        name: ByteArray,
        pixels: ByteArray,
        width: Int,
        height: Int,
        metadata: ByteArray?,
        captureTime: Long,
    )

    external fun pushAudio(
        handle: Long,
        name: ByteArray,
        samples: ShortArray,
        rate: Int,
        channels: Int,
    )

    external fun tracks(handle: Long): ByteArray

    external fun paused(handle: Long): ByteArray

    external fun status(handle: Long): ByteArray

    external fun session(handle: Long): ByteArray?

    external fun authenticate(
        apiUrl: ByteArray,
        key: ByteArray,
        options: ByteArray,
        local: Boolean,
        receiver: Any,
    )
}
