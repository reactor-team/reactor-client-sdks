package inc.reactor.sdk.kotlin

import inc.reactor.sdk.AudioFrameHandler
import inc.reactor.sdk.ConnectionStatus
import inc.reactor.sdk.JsonValue
import inc.reactor.sdk.ReactorException
import inc.reactor.sdk.Subscription
import inc.reactor.sdk.VideoFrameHandler
import java.util.Optional
import java.util.function.Consumer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/**
 * Control events as flows, and media frames as the one flow that can be built honestly.
 *
 * Every flow here unregisters its handler when its collector goes away — that is what [awaitClose]
 * is for, and a collector that is cancelled stops the SDK calling into a coroutine that no longer
 * exists.
 */

/** Control events are rare and losing one is worse than holding it. */
private const val CONTROL_BUFFER = Channel.UNLIMITED

private fun <T> subscriptionFlow(subscribe: (Consumer<T>) -> Subscription): Flow<T> =
    callbackFlow {
            val subscription = subscribe(Consumer { trySend(it) })
            awaitClose { subscription.close() }
        }
        .buffer(CONTROL_BUFFER)

/** Every connection status change, from the moment collection starts. */
public fun ReactorClient.statusFlow(): Flow<ConnectionStatus> = subscriptionFlow(java::onStatus)

/** Every error the session reports, as the same typed exception a failed call throws. */
public fun ReactorClient.errorFlow(): Flow<ReactorException> = subscriptionFlow(java::onError)

/** Every message from the model. */
public fun ReactorClient.messageFlow(): Flow<JsonValue> = subscriptionFlow(java::onMessage)

/** Every runtime message. */
public fun ReactorClient.runtimeMessageFlow(): Flow<JsonValue> =
    subscriptionFlow(java::onRuntimeMessage)

/** What the model says it can do, once it says so. */
public fun ReactorClient.capabilitiesFlow(): Flow<JsonValue> =
    subscriptionFlow(java::onCapabilities)

/** The session id, as it is assigned and cleared. Null once there is no session. */
public fun ReactorClient.sessionIdFlow(): Flow<String?> =
    subscriptionFlow<Optional<String>>(java::onSessionId).map { it.orElse(null) }

// ── Media ───────────────────────────────────────────────────────────────────

/**
 * One video frame, copied out of the callback that delivered it.
 *
 * [inc.reactor.sdk.VideoFrame] is a view over memory the FFI owns, valid only until the handler
 * returns. A flow outlives its callback by construction, so what it carries has to be a copy.
 *
 * @property width in pixels
 * @property height in pixels
 * @property bgra the pixels, `width * height * 4` bytes of them
 * @property frameId the FFI's own counter
 * @property timestampUs when the frame was generated
 * @property userData whatever the model attached, or null
 */
public class VideoFrameCopy(
    public val width: Int,
    public val height: Int,
    public val bgra: ByteArray,
    public val frameId: Long,
    public val timestampUs: Long,
    public val userData: ByteArray?,
)

/**
 * One block of audio, copied out of the callback that delivered it.
 *
 * @property samples interleaved signed 16-bit samples
 * @property sampleCount how many per channel
 * @property sampleRate in hertz
 * @property channels how many are interleaved
 */
public class AudioFrameCopy(
    public val samples: ShortArray,
    public val sampleCount: Int,
    public val sampleRate: Int,
    public val channels: Int,
)

/**
 * This track's video frames, newest-wins.
 *
 * **Prefer [ReactorTrack.onVideoFrame].** The callback is the documented way to receive frames, and
 * it is the one that keeps the FFI's contract intact: the handler runs inline on the delivery
 * thread, and blocking there is the backpressure — while it runs, the FFI holds only the newest
 * frame and drops the rest, which is a bounded cost paid in dropped frames.
 *
 * A flow cannot do that. It puts a channel between the delivery thread and the collector, and any
 * channel that does not drop turns a bounded frame drop into unbounded latency and memory. This one
 * is [Channel.CONFLATED], so it drops in the same shape the FFI already does — the newest frame
 * wins and a slow collector never accumulates a backlog. It is still one buffer more than the
 * callback has, and every frame is copied whether the collector wants it or not.
 *
 * @return the frames, as copies
 * @throws ReactorException when this track is sendonly, or carries audio
 */
public fun ReactorTrack.videoFrames(): Flow<VideoFrameCopy> =
    callbackFlow {
            val subscription =
                java.onFrame(
                    VideoFrameHandler { frame ->
                        trySend(
                            VideoFrameCopy(
                                frame.width(),
                                frame.height(),
                                frame.toByteArray(),
                                frame.frameId(),
                                frame.timestampUs(),
                                frame.userData().orElse(null),
                            )
                        )
                    }
                )
            awaitClose { subscription.close() }
        }
        .buffer(Channel.CONFLATED)

/**
 * This track's audio blocks, newest-wins.
 *
 * The same trade [videoFrames] describes, and the same advice: prefer [ReactorTrack.onAudioFrame].
 * Audio is worse served by conflation than video — a dropped block is a gap a listener hears, where
 * a dropped frame is one the viewer does not see — so a collector that cannot keep up should be
 * doing less, not buffering more.
 *
 * @return the blocks, as copies
 * @throws ReactorException when this track is sendonly, or carries video
 */
public fun ReactorTrack.audioFrames(): Flow<AudioFrameCopy> =
    callbackFlow {
            val subscription =
                java.onFrame(
                    AudioFrameHandler { frame ->
                        trySend(
                            AudioFrameCopy(
                                frame.toShortArray(),
                                frame.sampleCount(),
                                frame.sampleRate(),
                                frame.channels(),
                            )
                        )
                    }
                )
            awaitClose { subscription.close() }
        }
        .buffer(Channel.CONFLATED)
