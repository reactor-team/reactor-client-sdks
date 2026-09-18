package inc.reactor.sdk.kotlin

import inc.reactor.sdk.Dispatcher
import inc.reactor.sdk.ReactorOptions
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor

/**
 * Options, without naming the builder twice.
 *
 * ```kotlin
 * reactorOptions(apiUrl, "reactor/echo") {
 *     jwt(token)
 *     dispatcher(Dispatchers.Main.asReactorDispatcher())
 * }
 * ```
 *
 * @param apiUrl the platform
 * @param modelName which model to run
 * @param configure the rest, on the Java builder
 * @return the options
 */
public fun reactorOptions(
    apiUrl: String,
    modelName: String,
    configure: ReactorOptions.Builder.() -> Unit = {},
): ReactorOptions = ReactorOptions.builder(apiUrl, modelName).apply(configure).build()

/**
 * This dispatcher, as the one the SDK delivers control events on.
 *
 * A UI toolkit's dispatcher is the usual reason to want this: `Dispatchers.Main` on a desktop
 * application puts status, error and message handlers on the thread that is allowed to touch the
 * widgets.
 *
 * Media is not affected, deliberately — frame handlers run inline on the FFI's delivery thread
 * whatever this is set to, because that is where the backpressure lives.
 *
 * @return a dispatcher the SDK can use
 */
public fun CoroutineDispatcher.asReactorDispatcher(): Dispatcher {
    val executor: Executor = asExecutor()
    return Dispatcher { event -> executor.execute(event) }
}
