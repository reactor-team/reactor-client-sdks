// 09 · Stepped generation — one step at a time instead of a stream.
//
//     export REACTOR_API_KEY=rk_...
//     REACTOR_MODEL=owner/model scripts/swift.sh run 09_stepped_generation
//
// A client created with `generation: .stepped` asks for a session that
// generates only when told to. Each `step(state:commands:)` sends the model's
// state, a list of commands and every frame pushed since the previous step, and
// returns what the model produced on each output track — as arrays of bytes
// with a dtype and a shape, not a video stream.
//
// Pushing into an input track sends nothing in a stepped session: the frame
// waits for the next step. A runtime without stepped generation ignores the
// request, so `connect()` checks and throws `generationModeMismatch` rather
// than leaving this waiting on a stream.
//
// Set REACTOR_INPUT_TRACK to a sendonly video track the model declares to push a
// frame into every step, and REACTOR_STEP_COMMAND to a command the model takes
// with no arguments to run it in every step. REACTOR_PROMPT sets the state's
// `prompt`; REACTOR_STEPS how many steps to run.
//
// Numbered 09 to match sdks/python/examples/: 08 (snapshot and rewind) has no
// Swift counterpart yet.

import ExampleSupport
import Foundation
import Reactor

await runExample {
    guard let model = ProcessInfo.processInfo.environment["REACTOR_MODEL"], !model.isEmpty
    else { fail("set REACTOR_MODEL to a model with stepped generation, as owner/name") }
    let environment = ProcessInfo.processInfo.environment
    let steps = environment["REACTOR_STEPS"].flatMap(Int.init) ?? 3
    let prompt = environment["REACTOR_PROMPT"] ?? "a lighthouse in a storm"
    let inputTrack = environment["REACTOR_INPUT_TRACK"].flatMap { $0.isEmpty ? nil : $0 }
    let command = environment["REACTOR_STEP_COMMAND"].flatMap { $0.isEmpty ? nil : $0 }

    let reactor = try await connectedClient(model: model, generation: .stepped)
    defer { reactor.close() }
    let subscriptions = watch(reactor)
    let accepted = reactor.onStepAccepted { print("step \($0): accepted, generating") }

    try await reactor.connect()
    print("session: \(reactor.sessionID ?? "none")")

    // Publishing still claims the slot on the runtime; only the frames travel
    // differently.
    let source = try inputTrack.map { try reactor.track($0) }
    try await source?.publish()

    let width: UInt32 = 320
    let height: UInt32 = 180
    for sequence in 0..<steps {
        if let source {
            // A solid BGRA frame whose colour follows the step. Buffered, not
            // sent: the step below carries it.
            let pixel: [UInt8] = [
                UInt8(sequence * 7 % 256), UInt8(sequence * 13 % 256), UInt8(sequence * 29 % 256),
                255,
            ]
            let pixels = Data(
                Array([[UInt8]](repeating: pixel, count: Int(width * height)).joined()))
            try source.pushFrame(pixels, width: width, height: height)
        }

        let result = try await reactor.step(
            // State on the first step only: the model keeps it until changed.
            state: sequence == 0 ? ["prompt": .string(prompt)] : nil,
            commands: command.map { [StepCommand($0)] } ?? [])

        for track in result.tracks {
            let shapes = track.frames.map { "\($0.shape)" }.joined(separator: ", ")
            print(
                "step \(result.index): \(track.name) (\(track.kind.rawValue)) "
                    + "\(track.frames.count) frame(s) \(shapes)")
        }
    }

    _ = subscriptions
    _ = accepted
    try await reactor.disconnect()
}
