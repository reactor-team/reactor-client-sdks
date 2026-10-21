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
 * [readyCheck] starts a readiness check once the lock is held; the check is asked after the
 * answer, under the monitor of [states] — the one a status change clears them under, so the
 * check and the write cannot straddle it. A status change during the publish means the
 * completion is about a connection that has since gone, so the publish fails with
 * [DisconnectedException] rather than recording "published" for a sender that no longer exists.
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
    readyCheck: () -> () -> Boolean,
    publish: suspend () -> Unit,
) {
    withContext(NonCancellable) {
        // The check is started only once the lock is held: started earlier, a publish queued
        // behind another would measure against the session as it was when it queued, and fail
        // as disconnected after a reconnect it had no part in — with its sender attached.
        lock.withLock { publishLocked(states, track, readyCheck(), publish) }
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
    // Checked and recorded under the states' monitor, which is where a status change clears
    // them: between a check outside it and the write, the session could leave ready, the clear
    // run, and PUBLISHED then land on the far side of it.
    val stillReady =
        synchronized(states) {
            if (isReady()) {
                states[track] = PublishState.PUBLISHED
                true
            } else {
                states.remove(track)
                false
            }
        }
    if (!stillReady) {
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
}

/**
 * Unpublish [track] through [unpublish], which throws on failure, and forget its state.
 *
 * Under the same [lock] as [recordPublish]. Unserialised, an unpublish landing while a publish
 * awaits its answer would clear the state, and the publish's later answer would write PUBLISHED
 * back over it — accepting pushes into a slot the unpublish detached, which drops every one.
 * Serialised, it waits for that answer and then detaches.
 *
 * Only a successful unpublish clears the state: a failed one leaves the sender attached, and
 * forgetting it would make the retry a no-op.
 */
internal suspend fun recordUnpublish(
    states: MutableMap<String, PublishState>,
    track: String,
    lock: Mutex,
    unpublish: () -> Unit,
) {
    lock.withLock {
        unpublish()
        states.remove(track)
    }
}
