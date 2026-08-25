import CReactorFFI
import Foundation

/// What a model answered a command with: `{ type, data }`.
///
/// `nil` where a reply would be is not a failure — a handler that ran and
/// acknowledged the command without returning a message, such as an
/// auto-generated `set_<field>` setter, answers with nothing at all.
public struct CommandReply: Sendable, Hashable {

    /// The message type the model named.
    public let type: String?

    /// The payload, as the model sent it.
    public let data: JSONValue?

    /// Decode the payload into a type the caller has modelled.
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/decodeFailed`` when
    ///   the payload is not that shape — never a trap, and never a silently
    ///   empty value.
    public func decode<T: Decodable>(_ type: T.Type) throws -> T {
        guard let data else {
            throw ReactorError(
                .decodeFailed,
                "the model acknowledged the command without returning a message, "
                    + "so there is nothing to decode as \(T.self).",
                operation: "send_command")
        }
        do {
            return try JSONDecoder().decode(T.self, from: try JSONEncoder().encode(data))
        } catch {
            throw ReactorError(
                .decodeFailed,
                "the model's reply does not decode as \(T.self): \(error)",
                operation: "send_command")
        }
    }
}

extension Reactor {

    // MARK: - Commands

    /// Send a command and wait for its correlated reply.
    ///
    /// ```swift
    /// try await reactor.sendCommand("set_prompt", ["prompt": "a red bicycle"])
    /// try await reactor.sendCommand("start")
    /// ```
    ///
    /// - Parameters:
    ///   - name: the command, as the model declares it. ``requestSchema()`` is
    ///     what lists them.
    ///   - args: the command's arguments.
    ///   - uploads: files this command refers to, by parameter name. They travel
    ///     as references rather than inside the JSON payload — see
    ///     ``uploadFile(at:)``.
    @discardableResult
    public func sendCommand(
        _ name: String,
        _ args: JSONValue? = nil,
        uploads: [String: FileRef] = [:]
    ) async throws -> CommandReply? {
        let argsJSON = try args.map { try encodeJSON($0, operation: "send_command") }
        let uploadsJSON =
            uploads.isEmpty ? nil : try encodeJSON(uploads, operation: "send_command")

        let payload = try await perform("send_command") { handle, completion, userdata in
            name.withCString { namePointer in
                withOptionalCString(argsJSON) { argsPointer in
                    withOptionalCString(uploadsJSON) { uploadsPointer in
                        self.ffi.sendCommand(
                            handle, namePointer, argsPointer, uploadsPointer, completion, userdata)
                    }
                }
            }
        }

        // Absent is an answer: the handler ran and returned no message.
        guard let payload, !payload.isEmpty else { return nil }

        // Decoded here, before anything is handed back. A payload that will not
        // parse is a decode failure — substituting an empty reply would leave a
        // caller unable to tell it from a model that answered with nothing.
        let value = try decodeJSON(payload, operation: "send_command")
        return CommandReply(type: value["type"]?.stringValue, data: value["data"])
    }

    /// Send a command whose arguments the caller has modelled.
    ///
    /// The typed twin of ``sendCommand(_:_:uploads:)``, for a caller who wrote
    /// the struct rather than the dictionary.
    ///
    /// The `arguments:` label is not decoration. ``JSONValue`` is itself
    /// `Encodable`, so an unlabelled overload here is ambiguous with the one
    /// above — and the way that ambiguity resolves is *this* method calling
    /// itself forever. The label makes the two impossible to confuse, for the
    /// compiler and for a reader.
    @discardableResult
    public func sendCommand(
        _ name: String,
        arguments: some Encodable & Sendable,
        uploads: [String: FileRef] = [:]
    ) async throws -> CommandReply? {
        let value = try encodeValue(arguments, operation: "send_command")
        return try await sendCommand(name, value, uploads: uploads)
    }

    /// The model's command schema, as an OpenAPI document.
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/decodeFailed`` when
    ///   the document will not parse. It is deliberately **not** reported as an
    ///   empty schema: a schema declaring nothing is indistinguishable from a
    ///   model that declares nothing, and the Python SDK shipped exactly that
    ///   confusion once.
    public func requestSchema() async throws -> JSONValue {
        let payload = try await perform("request_schema") { handle, completion, userdata in
            self.ffi.requestSchema(handle, completion, userdata)
        }

        guard let payload, !payload.isEmpty else {
            throw ReactorError(
                .decodeFailed,
                "the model answered request_schema with no document",
                operation: "request_schema")
        }
        return try decodeJSON(payload, operation: "request_schema")
    }

    // MARK: - Uploads

    /// Upload a file from disk.
    ///
    /// The bytes go up once; the returned ``FileRef`` is what a command carries.
    public func uploadFile(at url: URL) async throws -> FileRef {
        let path = url.isFileURL ? url.path : url.absoluteString
        let payload = try await perform("upload_file") { handle, completion, userdata in
            path.withCString { pathPointer in
                self.ffi.uploadFile(handle, pathPointer, completion, userdata)
            }
        }
        return try decodeFileRef(payload, operation: "upload_file")
    }

    /// Upload bytes the caller already holds.
    public func uploadData(_ data: Data, name: String, mimeType: String) async throws -> FileRef {
        let payload = try await perform("upload_bytes") { handle, completion, userdata in
            data.withUnsafeBytes { bytes in
                name.withCString { namePointer in
                    mimeType.withCString { mimePointer in
                        self.ffi.uploadBytes(
                            handle, bytes.bindMemory(to: UInt8.self).baseAddress, data.count,
                            namePointer, mimePointer, completion, userdata)
                    }
                }
            }
        }
        return try decodeFileRef(payload, operation: "upload_bytes")
    }

    // MARK: - Messages

    /// Register a handler for messages the model sends.
    ///
    /// These arrive on their own, unprompted — a command's reply comes back from
    /// ``sendCommand(_:_:uploads:)`` instead.
    public func onMessage(_ handler: @escaping @Sendable (JSONValue) -> Void) -> Subscription {
        let id = UUID()
        state.withLock { $0.messageHandlers[id] = handler }
        return Subscription { [weak self] in
            self?.state.withLock { $0.messageHandlers[id] = nil }
        }
    }

    /// Register a handler for messages the *runtime* sends, as opposed to the
    /// model.
    public func onRuntimeMessage(
        _ handler: @escaping @Sendable (JSONValue) -> Void
    ) -> Subscription {
        let id = UUID()
        state.withLock { $0.runtimeMessageHandlers[id] = handler }
        return Subscription { [weak self] in
            self?.state.withLock { $0.runtimeMessageHandlers[id] = nil }
        }
    }

    /// Model messages, as a stream.
    public var messages: AsyncStream<JSONValue> {
        AsyncStream(bufferingPolicy: .bufferingNewest(64)) { continuation in
            let subscription = onMessage { continuation.yield($0) }
            continuation.onTermination = { _ in subscription.cancel() }
        }
    }

    /// Deliver a message the library reported. Called on an FFI thread.
    func deliver(message payload: String?, runtime: Bool) {
        let handlers = state.withLock {
            Array((runtime ? $0.runtimeMessageHandlers : $0.messageHandlers).values)
        }
        guard !handlers.isEmpty else { return }

        // Decoded on the library's thread, while the payload is still borrowed.
        guard let payload, let value = try? decodeJSON(payload, operation: "message") else {
            Log.client.error("dropping a message this SDK could not decode")
            return
        }
        dispatch { for handler in handlers { handler(value) } }
    }

    // MARK: - JSON, in one place

    /// The encoder every outbound payload goes through.
    ///
    /// `.withoutEscapingSlashes` because Foundation escapes `/` as `\/` by
    /// default: legal JSON, and unreadable the moment anyone looks at a mime
    /// type or a URL in a log.
    private static let jsonEncoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return encoder
    }()

    private func encodeJSON(_ value: some Encodable, operation: String) throws -> String {
        do {
            let data = try Self.jsonEncoder.encode(value)
            guard let text = String(data: data, encoding: .utf8) else {
                throw ReactorError(
                    .badRequest, "arguments are not valid UTF-8", operation: operation)
            }
            return text
        } catch let error as ReactorError {
            throw error
        } catch {
            throw ReactorError(
                .badRequest,
                "these arguments cannot be encoded as JSON: \(error)",
                operation: operation)
        }
    }

    private func encodeValue(_ value: some Encodable, operation: String) throws -> JSONValue {
        let text = try encodeJSON(value, operation: operation)
        return try decodeJSON(text, operation: operation)
    }

    private func decodeJSON(_ payload: String, operation: String) throws -> JSONValue {
        guard let data = payload.data(using: .utf8),
            let value = try? JSONDecoder().decode(JSONValue.self, from: data)
        else {
            throw ReactorError(
                .decodeFailed,
                "the reply to \(operation) is not JSON this SDK can read",
                operation: operation)
        }
        return value
    }

    private func decodeFileRef(_ payload: String?, operation: String) throws -> FileRef {
        guard let payload, let data = payload.data(using: .utf8),
            let ref = try? JSONDecoder().decode(FileRef.self, from: data)
        else {
            throw ReactorError(
                .decodeFailed,
                "the upload succeeded but its reference could not be read",
                operation: operation)
        }
        return ref
    }
}

// MARK: - Trampolines

let messageTrampoline: reactor_on_message_fn = { payload, userdata in
    let text = String(borrowing: payload)
    CallbackContext.from(userdata)?.client?.deliver(message: text, runtime: false)
}

let runtimeMessageTrampoline: reactor_on_runtime_message_fn = { payload, userdata in
    let text = String(borrowing: payload)
    CallbackContext.from(userdata)?.client?.deliver(message: text, runtime: true)
}
