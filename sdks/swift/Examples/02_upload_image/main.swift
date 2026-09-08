// 02 · Upload a file, pass the reference into a command.
//
//     export REACTOR_API_KEY=rk_...
//     scripts/swift.sh run 02_upload_image [path/to/image.png]
//
// `uploadFile` returns a `FileRef`; pass it in a command's `uploads` and the
// platform resolves it. The bytes go up once, separately from the command — they
// never travel inside the JSON payload.

import ExampleSupport
import Foundation
import Reactor

await runExample {
    let model = Env.model(default: "reactor/helios")
    let prompt = "in the style of the reference image"

    // An image to send. Given none, this writes a small gradient so the example runs
    // anywhere — the point is the upload, not the picture.
    let imageURL: URL
    if CommandLine.arguments.count > 1 {
        imageURL = URL(fileURLWithPath: CommandLine.arguments[1])
    } else {
        imageURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("reactor-example-reference.png")
        try makeGradientPNG(at: imageURL, width: 256, height: 256)
        print("no image given, wrote one: \(imageURL.path)")
    }

    let reactor = try await connectedClient(model: model)
    defer { reactor.close() }
    let subscriptions = watch(reactor)

    try await reactor.connect()

    let uploaded = try await reactor.uploadFile(at: imageURL)
    print(
        "uploaded: \(uploaded.name) \(uploaded.mimeType) (\(uploaded.size) bytes) -> \(uploaded.uploadID)"
    )

    // The reference goes in `uploads`, keyed by the parameter the model declares.
    // A refused upload comes back as a failed command rather than a failed upload.
    print(
        "set_conditioning -> "
            + String(
                describing: try await reactor.sendCommand(
                    "set_conditioning", ["prompt": .string(prompt)], uploads: ["image": uploaded])))
    try await reactor.sendCommand("start")

    let counter = FrameCounter(label: "02")
    let frames = try reactor.track("main_video").onFrame { counter.submit($0) }

    try await hold(Env.seconds(default: 15), "\(model) conditioned on \(uploaded.name)")
    counter.report()

    _ = subscriptions
    _ = frames
    try await reactor.disconnect()
}
