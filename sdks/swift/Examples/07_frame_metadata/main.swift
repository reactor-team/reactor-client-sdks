// 07 · Read the per-frame trailer: frame id, sender timestamp, user data.
//
//     export REACTOR_API_KEY=rk_...
//     scripts/swift.sh run 07_frame_metadata
//
// **Expect zeros.** No published model attaches a trailer today, so this prints
// `frameID: 0, captureTimeUs: 0, userData: none` against production — and that
// is the example working, not failing. It is here so the field is read by
// something, and so the day a model starts tagging frames, one run shows it.
//
// The capture time is on the *sender's* clock. Differences between stamps from
// one sender are what it supports; comparing it with a local clock is not.

import ExampleSupport
import Foundation
import Reactor

await runExample {
    let model = Env.model(default: "reactor/helios")
    let reactor = try await connectedClient(model: model)
    defer { reactor.close() }
    let subscriptions = watch(reactor)

    try await reactor.connect()
    try await reactor.sendCommand("set_prompt", ["prompt": "a lighthouse in fog"])
    try await reactor.sendCommand("start")

    let seen = TrailerLog()
    let frames = try reactor.track("main_video").onFrame { frame in seen.submit(frame) }

    try await hold(Env.seconds(default: 10), "trailers on main_video")
    seen.report()

    _ = subscriptions
    _ = frames
    try await reactor.disconnect()
}
