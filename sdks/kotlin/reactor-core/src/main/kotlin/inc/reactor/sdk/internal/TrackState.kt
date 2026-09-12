package inc.reactor.sdk.internal

import inc.reactor.sdk.AudioFrame
import inc.reactor.sdk.ControlEvent
import inc.reactor.sdk.DecodeFailedError
import inc.reactor.sdk.ErrorDetails
import inc.reactor.sdk.MediaFrame
import inc.reactor.sdk.TrackDirection
import inc.reactor.sdk.TrackKind
import inc.reactor.sdk.VideoFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class Declaration(
    val name: String,
    val kind: TrackKind,
    val direction: TrackDirection,
)

internal fun declarations(value: JsonArray): List<Declaration> {
    val result =
        value.map { item ->
            val obj = item as JsonObject

            fun field(name: String): String {
                val field = obj[name] as JsonPrimitive
                require(field.isString) { "$name must be a string" }
                return field.content
            }
            val name = field("name")
            require(name.isNotEmpty() && '\u0000' !in name) { "Invalid track name" }
            Declaration(
                name,
                TrackKind.valueOf(field("kind").uppercase(java.util.Locale.ROOT)),
                TrackDirection.valueOf(field("direction").uppercase(java.util.Locale.ROOT)),
            )
        }
    require(result.map { it.name }.distinct().size == result.size) { "Duplicate track names" }
    return result
}

private val mediaDeliveryDepth = ThreadLocal.withInitial { 0 }

/** External removal waits; removal from a media callback cannot wait on other callbacks. */
private class FrameHandler(
    val declaration: Declaration,
    private val handler: (MediaFrame) -> Unit,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private val threads = mutableMapOf<Thread, Int>()
    private var active = true

    fun deliver(frame: MediaFrame) {
        val thread = Thread.currentThread()
        val entered =
            lock.withLock {
                if (!active) {
                    false
                } else {
                    threads[thread] = (threads[thread] ?: 0) + 1
                    true
                }
            }
        if (!entered) return
        mediaDeliveryDepth.set(requireNotNull(mediaDeliveryDepth.get()) + 1)
        try {
            handler(frame)
        } finally {
            mediaDeliveryDepth.set(requireNotNull(mediaDeliveryDepth.get()) - 1)
            lock.withLock {
                val remaining = requireNotNull(threads[thread]) - 1
                if (remaining == 0) threads.remove(thread) else threads[thread] = remaining
                drained.signalAll()
            }
        }
    }

    override fun close() {
        lock.withLock {
            active = false
            // Media callbacks may remove each other concurrently; never make them join one another.
            if (mediaDeliveryDepth.get() == 0) while (threads.isNotEmpty()) drained.awaitUninterruptibly()
        }
    }
}

/** Callback state contains no reference to Reactor. No native handle calls under this lock. */
internal class TrackState(
    private val report: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var epoch = 0L
    private var revision = 0L
    private var declared = emptyList<Declaration>()
    private val mids = mutableMapOf<String, String?>()
    private val handlers = mutableMapOf<String, MutableList<FrameHandler>>()
    private val reported = mutableSetOf<String>()

    fun beginHandle(): Long =
        synchronized(lock) {
            epoch++
            revision++
            declared = emptyList()
            mids.clear()
            epoch
        }

    fun isCurrent(expected: Long): Boolean = synchronized(lock) { epoch == expected }

    fun stop() {
        beginHandle()
        synchronized(lock) { handlers.clear() }
    }

    fun update(
        expected: Long,
        kind: Int,
        text: ByteArray?,
        data: ByteArray?,
    ) {
        val parsed =
            if (kind == 4) {
                val objectValue = Json.parseToJsonElement(requireNotNull(text).decodeToString(throwOnInvalidSequence = true)) as JsonObject
                declarations(objectValue["tracks"] as JsonArray)
            } else {
                null
            }
        synchronized(lock) {
            if (epoch != expected) return
            when (kind) {
                0 -> {
                    if (text?.decodeToString() != "ready") {
                        revision++
                        declared = emptyList()
                        mids.clear()
                    }
                }
                4 -> {
                    revision++
                    declared = requireNotNull(parsed)
                }
                6 ->
                    mids[requireNotNull(text).decodeToString(throwOnInvalidSequence = true)] =
                        data?.decodeToString(throwOnInvalidSequence = true)
            }
        }
    }

    fun snapshot(read: () -> ByteArray): List<Declaration> {
        val generation = synchronized(lock) { revision }
        val parsed = declarations(Json.parseToJsonElement(read().decodeToString(throwOnInvalidSequence = true)) as JsonArray)
        return synchronized(lock) {
            // A callback may have replaced declarations while the FFI snapshot was copied/decoded.
            if (revision == generation) {
                declared = parsed
                revision++
            }
            declared.toList()
        }
    }

    fun mid(name: String): String? = synchronized(lock) { mids[name] }

    fun subscribe(
        declaration: Declaration,
        handler: (MediaFrame) -> Unit,
    ): AutoCloseable {
        val slot = FrameHandler(declaration, handler)
        synchronized(lock) {
            require(declaration in declared) { "Track '${declaration.name}' is no longer declared; refresh tracks" }
            handlers.getOrPut(declaration.name) { mutableListOf() }.add(slot)
        }
        return AutoCloseable {
            synchronized(lock) { handlers[declaration.name]?.remove(slot) }
            slot.close()
        }
    }

    fun deliver(
        expected: Long,
        name: String,
        kind: TrackKind,
        frame: MediaFrame,
    ) {
        val targets =
            synchronized(lock) {
                if (epoch != expected) return
                if (declared.none { it.name == name && it.kind == kind && it.direction == TrackDirection.RECVONLY }) {
                    null
                } else {
                    handlers[name]?.filter { it.declaration.kind == kind && it.declaration.direction == TrackDirection.RECVONLY }
                        ?: emptyList()
                }
            }
        if (targets == null) {
            diagnostic("Dropping $kind frame for unknown or non-recvonly track '$name'")
            return
        }
        for (target in targets) {
            try {
                target.deliver(frame)
            } catch (failure: Throwable) {
                diagnostic(
                    failure.message ?: failure.javaClass.name,
                )
            }
        }
    }

    fun diagnostic(message: String) {
        val first = synchronized(lock) { reported.add(message) }
        if (first) runCatching { report(IllegalStateException(message)) }
    }
}

/** One instance per native handle. Old/orphaned callbacks cannot change a new handle's state. */
internal class HandleCallbacks(
    private val tracks: TrackState,
    private val events: ControlEvents,
    private val epoch: Long,
) {
    fun accept(
        kind: Int,
        text: ByteArray?,
        data: ByteArray?,
    ) {
        if (!tracks.isCurrent(epoch)) return
        try {
            tracks.update(epoch, kind, text, data)
            events.accept(kind, text, data)
        } catch (failure: Exception) {
            events.emit(ControlEvent.Error(DecodeFailedError(ErrorDetails("DECODE_FAILED", "Invalid track event: ${failure.message}"))))
        }
    }

    fun video(
        name: ByteArray,
        pixels: ByteArray,
        width: Int,
        height: Int,
        id: Long,
        timestamp: Long,
        metadata: ByteArray?,
    ) {
        try {
            tracks.deliver(
                epoch,
                name.decodeToString(throwOnInvalidSequence = true),
                TrackKind.VIDEO,
                VideoFrame(pixels, width, height, id.toULong(), timestamp.toULong(), metadata),
            )
        } catch (failure: Exception) {
            tracks.diagnostic("Invalid video frame: ${failure.message}")
        }
    }

    fun audio(
        name: ByteArray,
        samples: ShortArray,
        rate: Int,
        channels: Int,
    ) {
        try {
            tracks.deliver(epoch, name.decodeToString(throwOnInvalidSequence = true), TrackKind.AUDIO, AudioFrame(samples, rate, channels))
        } catch (
            failure: Exception,
        ) {
            tracks.diagnostic("Invalid audio frame: ${failure.message}")
        }
    }

    fun diagnostic(message: ByteArray) = tracks.diagnostic(message.decodeToString())
}
