package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.DisconnectedException
import inc.reactor.sdk.android.ErrorCode
import inc.reactor.sdk.android.PublishState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Publish [track] through [publish], and record in [states] what the publish actually did.
 *
 * Runs to the native answer even when the caller is cancelled. Nothing in this ABI cancels a
 * publish: a cancelled [Completions.await] only drops the ticket, while the operation runs on and
 * may still attach a sender. Treating the cancellation as a failure would forget that sender and
 * leave a live track reported as unpublished, refusing every push. So the state follows the
 * answer, and the cancellation reaches the caller once the publish has been answered.
 *
 * [isReady] is asked after the answer: a status change during the publish means the completion
 * is about a connection that has since gone, so the publish fails with [DisconnectedException]
 * rather than recording "published" for a sender that no longer exists.
 *
 * Publishes of one track are serialised through [lock], the same lock for every call on that
 * track. Two in flight at once both claim the slot, and the one that fails would erase the state
 * the one that succeeded recorded, leaving a live sender reported as unpublished. Serialised, the
 * second call finds the track already published and returns without asking again.
 */
internal suspend fun recordPublish(
    states: MutableMap<String, PublishState>,
    track: String,
    lock: Mutex,
    isReady: () -> Boolean,
    publish: suspend () -> Unit,
) {
    withContext(NonCancellable) {
        lock.withLock { publishLocked(states, track, isReady, publish) }
    }
    currentCoroutineContext().ensureActive()
}

private suspend fun publishLocked(
    states: MutableMap<String, PublishState>,
    track: String,
    isReady: () -> Boolean,
    publish: suspend () -> Unit,
) {
    if (states[track] == PublishState.PUBLISHED) return
    states[track] = PublishState.PUBLISHING
    try {
        publish()
    } catch (t: Throwable) {
        // A failed publish leaves no sender behind the slot, so it must not read as
        // published — and must stay retryable.
        states.remove(track)
        throw t
    }
    if (!isReady()) {
        states.remove(track)
        // Not a success: the session that came back has no sender behind this slot, and a
        // publish that returned normally would hand the caller a track that refuses every push.
        throw ErrorCode.toException(
            wire = "DISCONNECTED",
            message =
                "publish of '$track' was answered, but the session left ready before it " +
                    "completed, so the track is not published. Publish again — a reconnect " +
                    "resumes recvonly tracks and nothing else.",
            operation = "publish",
        )
    }
    states[track] = PublishState.PUBLISHED
}
