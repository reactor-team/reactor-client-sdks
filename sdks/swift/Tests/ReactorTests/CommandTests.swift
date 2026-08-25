import Foundation
import Testing

@testable import Reactor

@Suite("Commands, messages and uploads")
struct CommandTests {

    private func makeClient(fake: FakeLibrary) throws -> Reactor {
        try Reactor(
            model: "reactor/helios",
            jwt: nil,
            apiURL: Reactor.defaultAPIURL,
            local: false,
            eventQueue: nil,
            ffi: fake.table)
    }

    /// Run `work`, answer the library's completion with `result`, and give back
    /// what the caller got.
    private func answering<T: Sendable>(
        _ fake: FakeLibrary,
        ok: Bool = true,
        result: String?,
        error: String? = nil,
        _ work: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        let task = Task { try await work() }
        let deadline = Date().addingTimeInterval(2)
        while !fake.hasPendingCompletion, Date() < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }
        fake.completeLastCall(ok: ok, result: result, error: error)
        return try await task.value
    }

    // MARK: - Commands

    @Test("a command reaches the library with its name and arguments")
    func commandCarriesItsArguments() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let reply = try await answering(fake, result: #"{"type":"ack","data":{"ok":true}}"#) {
            try await client.sendCommand("set_prompt", ["prompt": "a red bicycle"])
        }

        let call = try #require(fake.commandCalls.first)
        #expect(call.name == "set_prompt")
        #expect(call.argsJSON?.contains("a red bicycle") == true)
        #expect(call.uploadsJSON == nil)
        #expect(reply?.type == "ack")
        #expect(reply?.data?["ok"]?.boolValue == true)
    }

    @Test("a handler that answers with nothing is not a failure")
    func absentReplyIsNil() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        // An auto-generated set_<field> setter acknowledges and returns no
        // message. That is an answer, not an error.
        let reply = try await answering(fake, result: nil) {
            try await client.sendCommand("start")
        }

        #expect(reply == nil)
    }

    @Test("a successful reply that will not parse is a decode failure")
    func unparseableReplyIsDecodeFailure() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        // Not an empty reply: a caller could not tell that from a model that
        // answered with nothing, which is exactly the confusion the Python SDK
        // shipped once with request_schema.
        await #expect(throws: ReactorError.self) {
            try await answering(fake, result: "{not json") {
                try await client.sendCommand("start")
            }
        }
    }

    @Test("arguments the caller has modelled are encoded")
    func typedArguments() async throws {
        struct Prompt: Encodable, Sendable {
            let prompt: String
            let steps: Int
        }

        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        _ = try await answering(fake, result: nil) {
            try await client.sendCommand(
                "set_prompt", arguments: Prompt(prompt: "a bicycle", steps: 4))
        }

        let call = try #require(fake.commandCalls.first)
        #expect(call.argsJSON?.contains("\"prompt\"") == true)
        #expect(call.argsJSON?.contains("\"steps\"") == true)
    }

    @Test("a reply decodes into a type the caller has modelled")
    func replyDecodesIntoAType() async throws {
        struct Status: Decodable, Sendable {
            let frames: Int
        }

        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let reply = try await answering(fake, result: #"{"type":"status","data":{"frames":12}}"#) {
            try await client.sendCommand("status")
        }

        #expect(try reply?.decode(Status.self).frames == 12)
    }

    @Test("a reply of the wrong shape throws rather than trapping")
    func replyOfWrongShapeThrows() async throws {
        struct Status: Decodable, Sendable {
            let frames: Int
        }

        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let reply = try await answering(
            fake, result: #"{"type":"status","data":{"frames":"lots"}}"#
        ) {
            try await client.sendCommand("status")
        }

        // The continuation was claimed long before this decode, so a trap here
        // would take the process with it. It throws instead.
        #expect(throws: ReactorError.self) { _ = try reply?.decode(Status.self) }
    }

    @Test("uploads travel beside the arguments, not inside them")
    func uploadsTravelSeparately() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let ref = FileRef(uploadID: "up_1", name: "photo.jpg", mimeType: "image/jpeg", size: 12)
        _ = try await answering(fake, result: nil) {
            try await client.sendCommand("set_image", ["scale": 2], uploads: ["image": ref])
        }

        let call = try #require(fake.commandCalls.first)
        #expect(call.argsJSON?.contains("scale") == true)
        #expect(call.argsJSON?.contains("up_1") == false)
        // The upload is a reference the platform resolves, not JSON in the
        // payload.
        #expect(call.uploadsJSON?.contains("up_1") == true)
        #expect(call.uploadsJSON?.contains("image/jpeg") == true)
    }

    // MARK: - Schema

    @Test("the schema comes back as the document the model sent")
    func schemaIsReturned() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let schema = try await answering(
            fake, result: #"{"openapi":"3.1.0","paths":{"/set_prompt":{}}}"#
        ) {
            try await client.requestSchema()
        }

        #expect(schema["openapi"]?.stringValue == "3.1.0")
        #expect(fake.schemaCalls == 1)
    }

    @Test("a schema that will not parse is a decode failure, never an empty schema")
    func unparseableSchemaIsDecodeFailure() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        // Substituting {} here produced a schema declaring nothing, which no
        // caller can tell from a model that declares nothing.
        await #expect(throws: ReactorError.self) {
            try await answering(fake, result: "<html>nope</html>") {
                try await client.requestSchema()
            }
        }

        await #expect(throws: ReactorError.self) {
            try await answering(fake, result: nil) {
                try await client.requestSchema()
            }
        }
    }

    // MARK: - Uploads

    @Test("uploading a file returns the platform's reference")
    func uploadFileReturnsARef() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let url = URL(fileURLWithPath: "/tmp/photo.jpg")
        let ref = try await answering(
            fake,
            result: #"{"upload_id":"up_7","name":"photo.jpg","mime_type":"image/jpeg","size":42}"#
        ) {
            try await client.uploadFile(at: url)
        }

        #expect(fake.uploadFileCalls == ["/tmp/photo.jpg"])
        // Snake case on the wire, camel case in Swift.
        #expect(ref.uploadID == "up_7")
        #expect(ref.mimeType == "image/jpeg")
        #expect(ref.size == 42)
    }

    @Test("uploading bytes passes the buffer, the name and the type")
    func uploadDataPassesEverything() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let bytes = Data([0x9A, 0x01, 0x02])
        _ = try await answering(
            fake,
            result:
                #"{"upload_id":"up_8","name":"b.bin","mime_type":"application/octet-stream","size":3}"#
        ) {
            try await client.uploadData(bytes, name: "b.bin", mimeType: "application/octet-stream")
        }

        let call = try #require(fake.uploadBytesCalls.first)
        #expect(call.length == 3)
        #expect(call.firstByte == 0x9A)
        #expect(call.name == "b.bin")
        #expect(call.mimeType == "application/octet-stream")
    }

    @Test("an upload whose reference will not parse is a decode failure")
    func unparseableUploadRefIsDecodeFailure() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        await #expect(throws: ReactorError.self) {
            try await answering(fake, result: #"{"id":"up_9"}"#) {
                try await client.uploadFile(at: URL(fileURLWithPath: "/tmp/x"))
            }
        }
    }

    // MARK: - Messages

    @Test("model and runtime messages arrive on their own channels")
    func messagesAreSeparateChannels() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let model = Locked<JSONValue?>(nil)
        let runtime = Locked<JSONValue?>(nil)
        let delivered = DispatchSemaphore(value: 0)

        let modelSubscription = client.onMessage { value in
            model.withLock { $0 = value }
            delivered.signal()
        }
        let runtimeSubscription = client.onRuntimeMessage { value in
            runtime.withLock { $0 = value }
            delivered.signal()
        }
        defer {
            modelSubscription.cancel()
            runtimeSubscription.cancel()
        }

        fake.fireMessage(#"{"type":"progress","step":3}"#)
        fake.fireMessage(#"{"type":"session_ended"}"#, runtime: true)

        #expect(delivered.wait(timeout: .now() + 2) == .success)
        #expect(delivered.wait(timeout: .now() + 2) == .success)

        #expect(model.withLock { $0 }?["step"]?.intValue == 3)
        #expect(runtime.withLock { $0 }?["type"]?.stringValue == "session_ended")
    }

    @Test("a message that will not decode is dropped rather than delivered wrong")
    func undecodableMessageIsDropped() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let count = Locked(0)
        let subscription = client.onMessage { _ in count.withLock { $0 += 1 } }
        defer { subscription.cancel() }

        fake.fireMessage("{not json")

        let settled = DispatchSemaphore(value: 0)
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { settled.signal() }
        _ = settled.wait(timeout: .now() + 2)

        #expect(count.withLock { $0 } == 0)
    }

    // MARK: - JSON

    @Test("JSONValue round-trips the shapes a model sends")
    func jsonValueRoundTrips() throws {
        let value: JSONValue = [
            "text": "hi", "count": 2, "ratio": 0.5, "on": true, "missing": nil,
            "list": [1, "two"],
        ]

        let data = try JSONEncoder().encode(value)
        let back = try JSONDecoder().decode(JSONValue.self, from: data)

        #expect(back == value)
        #expect(back["text"]?.stringValue == "hi")
        #expect(back["count"]?.intValue == 2)
        #expect(back["ratio"]?.doubleValue == 0.5)
        // A bool that decoded as a number would be a bool nobody can get back.
        #expect(back["on"]?.boolValue == true)
        #expect(back["list"]?.arrayValue?.count == 2)
    }

    @Test("intValue answers nil for numbers an Int cannot hold, rather than trapping")
    func intValueIsTotal() throws {
        // Message and command payloads are written by the model, so `Int(double)`
        // made merely reading this property a fatal error the caller could do
        // nothing about — a crash from a convenience accessor whose contract is
        // to answer nil when the value is not what was asked for.
        let huge = try JSONDecoder().decode(JSONValue.self, from: Data(#"{"n":1e300}"#.utf8))
        #expect(huge["n"]?.intValue == nil)
        #expect(huge["n"]?.doubleValue == 1e300)

        let negative = try JSONDecoder().decode(JSONValue.self, from: Data(#"{"n":-1e300}"#.utf8))
        #expect(negative["n"]?.intValue == nil)

        // Not an Int either, and truncating to 1 silently would be the other way
        // of answering a question that was not asked.
        let fractional = try JSONDecoder().decode(JSONValue.self, from: Data(#"{"n":1.5}"#.utf8))
        #expect(fractional["n"]?.intValue == nil)
        #expect(fractional["n"]?.doubleValue == 1.5)

        // What it is actually for still works, at both ends of the range.
        let ordinary = try JSONDecoder().decode(JSONValue.self, from: Data(#"{"n":-7}"#.utf8))
        #expect(ordinary["n"]?.intValue == -7)
    }

}
