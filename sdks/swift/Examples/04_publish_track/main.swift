// 04 · Publish a track and push tagged frames into it.
//
//     export REACTOR_API_KEY=rk_...
//     scripts/swift.sh run 04_publish_track
//
// This is the only example that *sends*, so it needs a model with an input
// track. `xmax/x2` declares `source` (video, sendonly) alongside `main_video`,
// and it needs a prompt and **no** `start` — the model's minimum is per model.
//
// Three things this teaches, each of which the SDK refuses rather than drops:
//   * pushing before publish() goes nowhere — the FFI takes the frame and finds
//     no sender behind the slot;
//   * a publish does not survive the session leaving `ready`;
//   * a capture time is read once per moment and shared by every track.

import ExampleSupport
import Foundation
import Reactor

await runExample {
    let model = Env.model(default: "xmax/x2")
    let inputTrack = "source"
    let outputTrack = "main_video"

    let reactor = try await connectedClient(model: model)
    defer { reactor.close() }
    let subscriptions = watch(reactor)

    try await reactor.connect()
    print("session: \(reactor.sessionID ?? "none")")
    print(
        "tracks: \(reactor.tracks.map { "\($0.name) (\($0.kind?.rawValue ?? "?"), \($0.direction?.rawValue ?? "?"))" }.joined(separator: ", "))"
    )

    try await reactor.sendCommand("set_prompt", ["prompt": "a bicycle made of glass"])

    let source = try reactor.track(inputTrack)

    // Pushing before publishing is refused, and that refusal is the point of the
    // SDK: the library would accept this frame and drop it.
    do {
        try source.pushFrame(Data(repeating: 0, count: 64 * 64 * 4), width: 64, height: 64)
        print("unexpected: a push before publish() was accepted")
    } catch let error as ReactorError {
        print("refused, as it should be: \(error.message)")
    }

    try await source.publish()
    print("published: \(source.published)")

    let counter = FrameCounter(label: "04")
    let frames = try reactor.track(outputTrack).onFrame { counter.submit($0) }

    // Push a moving pattern so the far end has something that visibly changes.
    let width: UInt32 = 256
    let height: UInt32 = 256
    let pushes = Int(Env.seconds(default: 10) * 10)
    for step in 0..<pushes {
        var pixels = [UInt8](repeating: 0, count: Int(width * height) * 4)
        for y in 0..<Int(height) {
            for x in 0..<Int(width) {
                let offset = (y * Int(width) + x) * 4
                pixels[offset] = UInt8((x + step * 4) % 256)  // blue
                pixels[offset + 1] = UInt8((y + step * 2) % 256)  // green
                pixels[offset + 2] = UInt8(step % 256)  // red
                pixels[offset + 3] = 255
            }
        }

        // Read the clock once per unit of produced media and stamp every track with
        // that one value: tracks are synchronised by sharing a capture time, not by
        // reaching the encoder at the same moment. This is not the UNIX epoch.
        let captureTime = Reactor.timeMicros()

        // The tag reaches the far end as this frame's metadata. It is dropped unless
        // the peer declared that it reads tags, so tagging is always safe.
        try source.pushFrame(
            Data(pixels), width: width, height: height,
            userData: Data("step=\(step)".utf8), captureTimeUs: captureTime)

        try await Task.sleep(for: .milliseconds(100))
    }

    print("pushed: \(pushes) frames into '\(inputTrack)'")
    counter.report()

    try source.unpublish()
    print("unpublished: published = \(source.published)")

    _ = subscriptions
    _ = frames
    try await reactor.disconnect()
}
