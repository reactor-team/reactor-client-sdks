// 01 · Connect, prompt, receive — the baseline the other examples build on.
//
//     export REACTOR_API_KEY=rk_...
//     scripts/swift.sh run 01_connect_and_receive
//
//     REACTOR_SHOW=1     write PNG snapshots of the frames
//     REACTOR_SECONDS=30 watch for longer
//     REACTOR_LOCAL=1    use a local runtime instead of the cloud
//
// Docs: https://docs.reactor.inc/sdk-reference/using-the-sdk
//       https://docs.reactor.inc/concepts/sessions
//       https://docs.reactor.inc/model-api-reference/helios/schema

import ExampleSupport
import Foundation
import Reactor

await runExample {
    // A model name is `owner/name`. A bare name resolves under `reactor/`, so it
    // works by luck of ownership and answers 403 for anyone else's model.
    let model = Env.model(default: "reactor/helios")

    // Helios emits nothing until `start`, and `start` refuses without a prompt.
    // That minimum is per model: nothing arrives until the model's own is met.
    let prompt = "a forest at dawn, sunbeams through the canopy"
    let outputTrack = "main_video"

    let reactor = try await connectedClient(model: model)
    // Teardown in a defer, always: a creator that goes away without disconnecting
    // orphans the session, and the next run cannot start until it clears.
    defer { reactor.close() }
    let subscriptions = watch(reactor)

    try await reactor.connect()
    print("session: \(reactor.sessionID ?? "none")")

    print(
        "set_prompt -> \(String(describing: try await reactor.sendCommand("set_prompt", ["prompt": .string(prompt)])))"
    )
    print("start -> \(String(describing: try await reactor.sendCommand("start")))")

    // By name, as the model's schema declares it. `reactor.tracks` lists them, and
    // that is for discovery — an app that knows its model asks by name.
    let output = try reactor.track(outputTrack)
    let counter = FrameCounter(label: "01")

    // Runs inline on the library's delivery thread: while it runs, the FFI keeps
    // only the newest frame. Blocking here is the backpressure.
    let frames = try output.onFrame { counter.submit($0) }

    try await hold(Env.seconds(default: 15), "\(model) · \(outputTrack)")
    counter.report()

    _ = subscriptions
    _ = frames

    // Ends the session server-side, which is the polite thing to do and what keeps
    // the next run from waiting on this one.
    try await reactor.disconnect()
}
