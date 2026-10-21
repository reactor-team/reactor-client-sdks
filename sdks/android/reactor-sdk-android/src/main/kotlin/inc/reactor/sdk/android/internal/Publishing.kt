package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.PublishState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * is about a connection that has since gone, and recording "published" would outlive the sender
 * it describes.
 */
internal suspend fun recordPublish(
    states: MutableMap<String, PublishState>,
    track: String,
    isReady: () -> Boolean,
    publish: suspend () -> Unit,
) {
    withContext(NonCancellable) {
        states[track] = PublishState.PUBLISHING
        try {
            publish()
        } catch (t: Throwable) {
            // A failed publish leaves no sender behind the slot, so it must not read as
            // published — and must stay retryable.
            states.remove(track)
            throw t
        }
        if (isReady()) {
            states[track] = PublishState.PUBLISHED
        } else {
            states.remove(track)
        }
    }
    currentCoroutineContext().ensureActive()
}
