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
    ): Long

    external fun destroy(handle: Long): Int

    external fun start(
        handle: Long,
        operation: Int,
        session: ByteArray?,
        connection: Long,
        receiver: Any,
    )

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
