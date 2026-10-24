import Foundation

/// How a session generates.
///
/// Chosen when the client is created — see
/// ``Reactor/init(model:jwt:apiURL:local:autoResumeTracks:generation:stepTimeout:extraHeaders:eventQueue:)``
/// — and fixed for its lifetime.
public enum Generation: String, Sendable, Hashable, CaseIterable {

    /// Continuously, over the model's media tracks. What every session did
    /// before the choice existed, and the default.
    case streamed

    /// One ``Reactor/step(state:commands:)`` at a time. Input tracks buffer
    /// what is pushed into them for the next step, and each step answers with
    /// the frames the model produced rather than streaming them.
    case stepped
}

/// One command of a step, run after the step's state, in order.
///
/// ```swift
/// StepCommand("advance", ["frames": 4])
/// StepCommand("reset")
/// ```
///
/// `data` is an object or nothing, by construction: the runtime runs a command
/// with an object of arguments, and a misshapen one would only be refused once
/// the step had already been sent.
public struct StepCommand: Sendable, Hashable, Encodable {

    /// The command, as the model declares it.
    public let command: String

    /// The command's arguments, or `nil` for a command that takes none.
    public let data: [String: JSONValue]?

    /// A command with ad-hoc arguments.
    public init(_ command: String, _ data: [String: JSONValue]? = nil) {
        self.command = command
        self.data = data
    }

    /// A command whose arguments the caller has modelled.
    ///
    /// A ``FileRef`` inside `arguments` encodes as the upload reference the
    /// platform reads, as it does in ``Reactor/sendCommand(_:arguments:uploads:)``.
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/badRequest`` when
    ///   `arguments` does not encode as a JSON object.
    public init(_ command: String, arguments: some Encodable & Sendable) throws {
        let value: JSONValue
        do {
            let data = try JSON.encoder().encode(arguments)
            value = try JSON.decoder().decode(JSONValue.self, from: data)
        } catch {
            throw ReactorError(
                .badRequest,
                "the arguments of step command '\(command)' cannot be encoded as JSON: \(error)",
                operation: "step")
        }
        guard let object = value.objectValue else {
            throw ReactorError(
                .badRequest,
                "the arguments of step command '\(command)' must encode as a JSON object, "
                    + "not \(value)",
                operation: "step")
        }
        self.init(command, object)
    }

    /// The wire spelling: `{"command": …, "data": … | null}`.
    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(command, forKey: .command)
        if let data {
            try container.encode(data, forKey: .data)
        } else {
            try container.encodeNil(forKey: .data)
        }
    }

    private enum CodingKeys: String, CodingKey {
        case command
        case data
    }
}

/// What one step produced: its index in the session, per output track the
/// frames it generated, the files the model kept with it, and — when the model
/// reported the step failed — why.
///
/// ```swift
/// let result = try await reactor.step(state: ["prompt": "a red fox"])
/// for frame in try result.track("main_video").frames {
///     show(frame.data, shape: frame.shape)    // (height, width, 3) uint8 RGB
/// }
/// ```
///
/// Copied out of the library once, when the step completes, and the library's
/// result freed straight away: nothing here borrows from it, so a result can be
/// kept, sent across tasks, and outlive the client.
public struct StepResult: Sendable {

    /// The step's number in the session, counted from 1: the step of the
    /// session's step routes, `GET /sessions/{id}/steps/{step}`.
    public let step: UInt64

    /// Every output track that produced something, in the order the runtime
    /// sent them.
    public let tracks: [StepTrackOutput]

    /// How long the step's `generate()` took, or the time the model reported
    /// for the step. `.zero` if the runtime sent a value a `Duration` cannot
    /// hold (a NaN or an infinity).
    public let elapsed: Duration

    /// The extra files the model kept with the step, in the order the runtime
    /// listed them. Empty when it kept none or the result was not saved.
    ///
    /// The result names them; it does not carry them. Fetch a file's bytes over
    /// HTTP from the session's step results, `GET /sessions/{id}/steps/{step}`,
    /// which lists each file with a download URL (and answers 202 while the
    /// step is not stored yet).
    public let files: [StepFile]

    /// Why the step failed, when it ran and the model reported a failure;
    /// `nil` when it worked.
    ///
    /// A failed step is still a result — ``Reactor/step(state:commands:)``
    /// returns it rather than throwing. Only a step that never ran throws.
    public let error: String?

    /// Assemble a result — for tests and previews; ``Reactor/step(state:commands:)``
    /// is where real ones come from.
    public init(
        step: UInt64,
        tracks: [StepTrackOutput],
        elapsed: Duration = .zero,
        files: [StepFile] = [],
        error: String? = nil
    ) {
        self.step = step
        self.tracks = tracks
        self.elapsed = elapsed
        self.files = files
        self.error = error
    }

    /// The output of the track called `name`.
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/notFound``, naming the
    ///   tracks that did produce output. A model can legitimately produce
    ///   nothing on a track in a step, and that is worth telling apart from a
    ///   typo.
    public func track(_ name: String) throws -> StepTrackOutput {
        if let output = tracks.first(where: { $0.name == name }) { return output }
        let produced =
            tracks.isEmpty ? "none" : tracks.map { "'\($0.name)'" }.joined(separator: ", ")
        throw ReactorError(
            .notFound,
            "step \(index) produced no output on '\(name)' (tracks: \(produced))",
            operation: "step")
    }
}

/// A file the model kept with a step: named, not carried. See
/// ``StepResult/files`` for where its bytes are.
public struct StepFile: Sendable, Hashable {

    /// The file's name, one path segment.
    public let name: String

    /// The file's media type, guessed from its name.
    public let contentType: String

    /// The file's size in bytes.
    public let size: UInt64

    /// Assemble a file entry — for tests and previews.
    public init(name: String, contentType: String, size: UInt64) {
        self.name = name
        self.contentType = contentType
        self.size = size
    }
}

/// The frames one output track produced in a step, in production order.
public struct StepTrackOutput: Sendable {

    /// The output track's declared name.
    public let name: String

    /// `video` or `audio`.
    public let kind: TrackKind

    /// The rate the track's frames play at, as the runtime reported it: frames
    /// per second for video, samples per second for audio. `0` when the runtime
    /// did not say.
    public let rate: Double

    /// What the track produced, oldest first.
    public let frames: [StepFrame]

    /// Assemble an output — for tests and previews.
    public init(name: String, kind: TrackKind, rate: Double = 0, frames: [StepFrame]) {
        self.name = name
        self.kind = kind
        self.rate = rate
        self.frames = frames
    }
}

/// One frame a step produced on an output track: an n-dimensional array, as its
/// bytes plus their element type and shape.
///
/// - Video: ``dtype`` `"uint8"`, ``shape`` `[height, width, 3]` — **RGB**, not
///   the BGRA a streamed ``VideoFrame`` carries.
/// - Audio: ``dtype`` `"int16"`, ``shape`` `[channels, samples]` — **planar**,
///   not the interleaved PCM a streamed ``AudioFrame`` carries.
///
/// ``data`` is row-major, little-endian for multi-byte elements, and exactly
/// `shape.product * size(dtype)` bytes: a frame whose bytes disagree with its
/// shape is refused when the result arrives, rather than when someone first
/// reads it.
public struct StepFrame: Sendable, Hashable {

    /// The array's bytes, already decoded whatever ``encoding`` says.
    public let data: Data

    /// The element type, in NumPy notation: `"uint8"` for video, `"int16"` for
    /// audio.
    public let dtype: String

    /// The array's dimensions, outermost first.
    public let shape: [Int]

    /// How the frame travelled on the wire — `"raw"` today. Informational:
    /// ``data`` is decoded either way.
    public let encoding: String

    /// What the sender attached to the frame, or `nil` when it attached nothing.
    /// An attached but empty tag is empty `Data`, not `nil`.
    public let metadata: Data?

    /// When the sender says the frame was captured, in microseconds, if it said.
    public let captureTimeUs: Int64?

    /// Assemble a frame — for tests and previews.
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/decodeFailed`` when
    ///   `data` is not the size `shape` and `dtype` say it is.
    public init(
        data: Data,
        dtype: String,
        shape: [Int],
        encoding: String = "raw",
        metadata: Data? = nil,
        captureTimeUs: Int64? = nil
    ) throws {
        if let expected = StepFrame.byteCount(dtype: dtype, shape: shape), expected != data.count {
            throw ReactorError(
                .decodeFailed,
                "a step frame of shape \(shape) and dtype \(dtype) needs \(expected) bytes, "
                    + "got \(data.count)",
                operation: "step")
        }
        self.data = data
        self.dtype = dtype
        self.shape = shape
        self.encoding = encoding
        self.metadata = metadata
        self.captureTimeUs = captureTimeUs
    }

    /// Element sizes of the dtypes a raw frame can declare.
    private static let elementSizes: [String: Int] = [
        "uint8": 1, "int8": 1, "uint16": 2, "int16": 2, "uint32": 4, "int32": 4,
        "float16": 2, "float32": 4, "float64": 8,
    ]

    /// The byte count `shape` and `dtype` call for, or `nil` for a dtype this
    /// SDK does not know and so cannot check. `-1` — which no buffer matches —
    /// for a negative dimension or a product that does not fit in an `Int`.
    private static func byteCount(dtype: String, shape: [Int]) -> Int? {
        guard let size = elementSizes[dtype] else { return nil }
        var total = size
        for dimension in shape {
            let (product, overflow) = total.multipliedReportingOverflow(by: dimension)
            guard !overflow, dimension >= 0 else { return -1 }
            total = product
        }
        return total
    }
}
