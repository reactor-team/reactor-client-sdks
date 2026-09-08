// 06 · Request a clip and download it.
//
//     export REACTOR_API_KEY=rk_...
//     scripts/swift.sh run 06_record_clip
//
// Three separate shipped bugs live behind this one call, and all three are in the
// library rather than here: the init segment rides on the `#EXT-X-MAP` *comment*
// line, a segment can be presigned on another host where an Authorization header
// is rejected rather than ignored, and a 202 means "not ready yet".
//
// What to expect against production: **the clip is clamped to the media the
// model has actually generated.** These models run slower than real time, so a
// ten-second clip of a five-second-old session is five seconds of video. That is
// the platform working.
//
// Readiness is in media time, not wall clock. The runtime's own prediction is a
// wall clock plus media seconds, so it is only right for a model generating at
// real time — which is why `readyTimeout: nil` (wait as long as the session can
// still produce it) is the sane default here.

import ExampleSupport
import Foundation
import Reactor

await runExample {
    let model = Env.model(default: "reactor/helios")
    let reactor = try await connectedClient(model: model)
    defer { reactor.close() }
    let subscriptions = watch(reactor)

    try await reactor.connect()
    try await reactor.sendCommand("set_prompt", ["prompt": "a train crossing a bridge"])
    try await reactor.sendCommand("start")

    // Generate something worth clipping first. A clip of a session that has produced
    // nothing is a clip of nothing.
    let counter = FrameCounter(label: "06")
    let frames = try reactor.track("main_video").onFrame { counter.submit($0) }
    try await hold(Env.seconds(default: 20), "media to clip")
    counter.report()

    let clip = try await reactor.requestClip(.seconds(10))
    print("clip: \(clip.playlistURL)")
    print("kind: \(clip.kind ?? "?"), session: \(clip.sessionID ?? "?")")
    if let predicted = clip.predictedReadyAtMS {
        let seconds = (predicted - Date().timeIntervalSince1970 * 1000) / 1000
        print("runtime predicts ready in \(String(format: "%.1f", seconds))s")
    }

    let destination = FileManager.default.temporaryDirectory
        .appendingPathComponent("reactor-clip-\(Int(Date().timeIntervalSince1970)).mp4")

    let result = try await reactor.download(clip, to: destination) { progress in
        print("segments: \(progress.done)/\(progress.total)")
    }

    print("wrote \(result.bytes) bytes from \(result.segments) segments")
    print("clip: \(result.path.path)")
    print("open it: open \(result.path.path)")

    _ = subscriptions
    _ = frames
    try await reactor.disconnect()
}
