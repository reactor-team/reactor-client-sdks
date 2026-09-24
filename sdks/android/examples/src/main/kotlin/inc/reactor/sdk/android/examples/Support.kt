package inc.reactor.sdk.android.examples

import inc.reactor.sdk.android.Reactor
import inc.reactor.sdk.android.ReactorOptions
import inc.reactor.sdk.android.VideoFrame

/*
 * The spine every example shares, and nothing else.
 *
 * Kept to one file on purpose: a reader who has to open two files to understand one example is
 * reading one file too many. What lives here is the connect-and-tear-down boilerplate that is
 * identical in all seven; everything a given example *teaches* stays in that example.
 */

/** Where a scenario reports what it saw. */
public typealias Log = (String) -> Unit

/**
 * Connect, run [body], and always disconnect.
 *
 * The `finally` is not tidiness. A creator that goes away without disconnecting orphans the
 * session server-side, and the next run cannot start until that clears — so a crashed example
 * breaks the *next* one, which is a confusing way to learn this SDK.
 */
public suspend fun withReactor(
    model: String,
    apiKey: String,
    log: Log,
    body: suspend (Reactor) -> Unit,
) {
    // A real app mints a short-lived token on its own backend and passes it as `jwt`. An API key
    // in an APK is a key anyone can extract; it is used here only because an example has no
    // backend, and it is passed in at install time rather than compiled in.
    val reactor = Reactor(ReactorOptions(apiKey = apiKey))
    try {
        log("connecting to $model…")
        reactor.connect(model)
        log("connected")
        body(reactor)
    } finally {
        runCatching { reactor.disconnect() }
        reactor.close()
        log("disconnected")
    }
}

/**
 * Count frames on a track, reporting the first one's shape.
 *
 * Counting proves something arrived, **not** that it was the right something — which is why the
 * examples that can show a frame do, behind [FrameSink]. A count alone has let a solid green
 * stream pass for working video more than once.
 */
public fun countFrames(
    log: Log,
    sink: FrameSink?,
): (VideoFrame) -> Unit {
    var seen = 0
    return { frame ->
        seen += 1
        if (seen == 1) log("first frame: ${frame.width}x${frame.height}")
        if (seen % 30 == 0) log("$seen frames")
        sink?.render(frame)
    }
}
