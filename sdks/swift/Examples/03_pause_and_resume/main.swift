// 03 · Pause and resume a track.
//
//     export REACTOR_API_KEY=rk_...
//     scripts/swift.sh run 03_pause_and_resume
//
// What this teaches: nothing is generated while a track is paused. From the
// outside that is a frame counter that stops climbing — not an error, and not a
// black frame. The only way to see it is to count.

import ExampleSupport
import Foundation
import Reactor

await runExample {
    let model = Env.model(default: "reactor/helios")
    let reactor = try await connectedClient(model: model)
    defer { reactor.close() }
    let subscriptions = watch(reactor)

    try await reactor.connect()
    try await reactor.sendCommand("set_prompt", ["prompt": "a city street in the rain"])
    try await reactor.sendCommand("start")

    let output = try reactor.track("main_video")
    let counter = FrameCounter(label: "03")
    let frames = try output.onFrame { counter.submit($0) }

    try await hold(5, "frames before pausing")
    let before = counter.frames

    try await output.pause()
    print("paused: \(output.paused)")
    try await hold(5, "a paused track")
    let during = counter.frames

    try await output.resume()
    print("resumed: paused = \(output.paused)")
    try await hold(5, "frames after resuming")
    let after = counter.frames

    print("frames: \(before) before, +\(during - before) while paused, +\(after - during) after")
    if during - before > 0 {
        print("note: a few frames in flight can still land just after pause()")
    }

    _ = subscriptions
    _ = frames
    try await reactor.disconnect()
}
