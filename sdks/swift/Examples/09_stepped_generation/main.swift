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
// Every argument of `step` is optional: `step()` with nothing pushed runs on
// the model's current state. A step the model ran but reported as failed still
// returns a result, with `error` set; only a step that never ran throws.
// `files` names the files the model kept with the step; their bytes are
// fetched over HTTP from the session's step results.
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

    try await reactor.connect()
    // Past connect() the session exists on the server: a failure below still
    // ends it, or it would linger until it expired.
    do {
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
                    UInt8(sequence * 7 % 256), UInt8(sequence * 13 % 256),
                    UInt8(sequence * 29 % 256),
                    255,
                ]
                let pixels = Data(
                    Array([[UInt8]](repeating: pixel, count: Int(width * height)).joined()))
                try source.pushFrame(pixels, width: width, height: height)
            }

            // State on the first step only: the model keeps it until changed. With
            // nothing else to send, a later step is just `step()`.
            let state: [String: JSONValue]? = sequence == 0 ? ["prompt": .string(prompt)] : nil
            let commands = command.map { [StepCommand($0)] } ?? []
            let result =
                state == nil && commands.isEmpty
                ? try await reactor.step()
                : try await reactor.step(state: state, commands: commands)

            print("step \(result.step): ran in \(result.elapsed)")
            if let error = result.error {
                print("step \(result.step): the model reported a failure: \(error)")
            }
            for track in result.tracks {
                let shapes = track.frames.map { "\($0.shape)" }.joined(separator: ", ")
                print(
                    "step \(result.step): \(track.name) (\(track.kind.rawValue)) "
                        + "\(track.frames.count) frame(s) \(shapes)")
            }
            for file in result.files {
                print(
                    "step \(result.step): kept \(file.name) (\(file.contentType), "
                        + "\(file.size) bytes)")
            }
        }
        try await reactor.disconnect()
    } catch {
        try? await reactor.disconnect()
        throw error
    }
    _ = subscriptions
}
