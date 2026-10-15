import Foundation

/// A file the platform has already taken, ready to be named in a command.
///
/// Uploading is a separate step from using: the bytes go up once, and the
/// reference is what a command carries.
///
/// ```swift
/// let photo = try await reactor.uploadFile(at: url)
/// try await reactor.sendCommand("set_image", uploads: ["image": photo])
/// ```
///
/// `Codable`, so it also drops into a typed command's arguments — the way a
/// parameter that takes several files, a list of references, is filled. It
/// encodes as the upload reference the platform reads, whichever way it travels.
public struct FileRef: Sendable, Hashable, Codable {

    /// The platform's id for the uploaded bytes.
    public let uploadID: String

    /// The file's name, as it was uploaded.
    public let name: String

    /// The MIME type the platform recorded.
    public let mimeType: String

    /// The size in bytes.
    public let size: Int

    /// The wire spelling, which is the platform's rather than Swift's.
    private enum CodingKeys: String, CodingKey {
        case uploadID = "upload_id"
        case name
        case mimeType = "mime_type"
        case size
    }

    /// A reference the caller already has — from a previous run, say.
    public init(uploadID: String, name: String, mimeType: String, size: Int) {
        self.uploadID = uploadID
        self.name = name
        self.mimeType = mimeType
        self.size = size
    }
}

extension JSONValue {

    /// The upload reference the platform reads, as a value — for a step's
    /// `state`, whose fields are ``JSONValue``s:
    ///
    /// ```swift
    /// let photo = try await reactor.uploadFile(at: url)
    /// try await reactor.step(state: ["image": JSONValue(photo)])
    /// ```
    ///
    /// The same object a ``FileRef`` encodes as inside a command's arguments.
    public init(_ file: FileRef) {
        self = .object([
            "upload_id": .string(file.uploadID),
            "name": .string(file.name),
            "mime_type": .string(file.mimeType),
            "size": .number(Double(file.size)),
        ])
    }
}
