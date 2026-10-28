import Foundation
import Testing

@testable import Reactor

@Suite("Auth")
struct AuthTests {

    /// Ask for a token and answer the library's completion as it would.
    private func fetching(
        _ fake: FakeLibrary,
        ok: Bool = true,
        result: String?,
        error: String? = nil,
        options: Reactor.TokenOptions? = nil,
        local: Bool = false
    ) async throws -> String {
        let task = Task {
            try await Reactor.fetchJWT(
                apiKey: "rk_test", apiURL: Reactor.defaultAPIURL, options: options, local: local,
                ffi: fake.table)
        }
        #expect(await waitUntil { fake.hasPendingCompletion }, "the SDK never called the library")
        fake.completeLastCall(ok: ok, result: result, error: error)
        return try await task.value
    }

    @Test("a key is exchanged for the token the coordinator returns")
    func tokenComesBack() async throws {
        let fake = FakeLibrary()

        let token = try await fetching(fake, result: #"{"jwt":"eyJhbGciOi.test"}"#)

        #expect(token == "eyJhbGciOi.test")
        let call = try #require(fake.jwtCalls.first)
        #expect(call.apiKey == "rk_test")
        #expect(call.apiURL == Reactor.defaultAPIURL)
        #expect(call.local == 0)
        // No options means no object: null mints whatever the key's roles allow,
        // which is what a caller who said nothing asked for.
        #expect(call.optionsJSON == nil)
    }

    @Test("options carry the keys the platform accepts, in its spelling")
    func optionsCarryOnlyDocumentedKeys() async throws {
        let fake = FakeLibrary()

        _ = try await fetching(
            fake, result: #"{"jwt":"t"}"#,
            options: .init(models: ["reactor/helios"], maxSessions: 2, expiresAfter: 900))

        let json = try #require(fake.jwtCalls.first?.optionsJSON)
        #expect(json.contains("\"models\""))
        #expect(json.contains("reactor/helios"))
        // Snake case, because the platform's spelling wins over Swift's.
        #expect(json.contains("\"max_sessions\":2"))
        #expect(json.contains("\"expires_after\":900"))
        // An unrecognised key in this object is an error on the platform's side,
        // deliberately — so that a misspelt `models` cannot be dropped in silence
        // and mint the unscoped token the caller was avoiding. Nothing this SDK
        // sends can be misspelt, because every key is a struct field rather than a
        // dictionary key.
        #expect(!json.contains("maxSessions"))
        #expect(!json.contains("expiresAfter"))
    }

    @Test("sessions bind the token to existing sessions, with no model scope or budget")
    func sessionsBindTheToken() async throws {
        let fake = FakeLibrary()

        _ = try await fetching(
            fake, result: #"{"jwt":"t"}"#, options: .init(sessions: ["session-1"]))

        // The native library adds the model scope that a bind needs. With no
        // `max_sessions`, the token cannot create sessions.
        let json = try #require(fake.jwtCalls.first?.optionsJSON)
        #expect(json == #"{"sessions":["session-1"]}"#)
    }

    @Test("an omitted option is omitted rather than sent as null")
    func omittedOptionsAreAbsent() async throws {
        let fake = FakeLibrary()

        _ = try await fetching(
            fake, result: #"{"jwt":"t"}"#, options: .init(models: ["reactor/helios"]))

        let json = try #require(fake.jwtCalls.first?.optionsJSON)
        #expect(!json.contains("max_sessions"))
        #expect(!json.contains("expires_after"))
    }

    @Test("extra headers travel under the platform's key, as an object of strings")
    func extraHeadersCarryTheKey() async throws {
        let fake = FakeLibrary()

        _ = try await fetching(
            fake, result: #"{"jwt":"t"}"#,
            options: .init(extraHeaders: ["X-Trace": "abc"]))

        let json = try #require(fake.jwtCalls.first?.optionsJSON)
        #expect(json == #"{"extra_headers":{"X-Trace":"abc"}}"#)
        #expect(!json.contains("extraHeaders"))
    }

    @Test("empty extra headers are omitted rather than sent as an empty object")
    func emptyExtraHeadersAreAbsent() async throws {
        let fake = FakeLibrary()

        _ = try await fetching(
            fake, result: #"{"jwt":"t"}"#,
            options: .init(models: ["reactor/helios"], extraHeaders: [:]))

        let json = try #require(fake.jwtCalls.first?.optionsJSON)
        #expect(!json.contains("extra_headers"))
    }

    @Test("local dev exchanges the key at the local coordinator, not the production one")
    func localIsPassedThrough() async throws {
        let fake = FakeLibrary()

        _ = try await fetching(fake, result: #"{"jwt":"t"}"#, local: true)

        #expect(fake.jwtCalls.first?.local == 1)
        // The client initialiser makes the same substitution, so leaving it out
        // here minted the token at https://api.reactor.inc for a client pointed
        // at http://localhost:8080 — two different coordinators for one session.
        #expect(fake.jwtCalls.first?.apiURL == Reactor.localAPIURL)
    }

    @Test("an explicit URL survives local dev, here as in the client initialiser")
    func localKeepsAnExplicitURL() async throws {
        let fake = FakeLibrary()

        let task = Task {
            try await Reactor.fetchJWT(
                apiKey: "rk_test", apiURL: "http://192.168.1.10:9000", options: nil, local: true,
                ffi: fake.table)
        }
        #expect(await waitUntil { fake.hasPendingCompletion })
        fake.completeLastCall(ok: true, result: #"{"jwt":"t"}"#, error: nil)
        _ = try await task.value

        #expect(fake.jwtCalls.first?.apiURL == "http://192.168.1.10:9000")
    }

    @Test("a rejected key surfaces as the typed error the platform sent")
    func rejectedKeyIsUnauthorized() async throws {
        let fake = FakeLibrary()

        do {
            _ = try await fetching(
                fake, ok: false, result: nil,
                error: #"{"code":"UNAUTHORIZED","message":"unknown key","status":401}"#)
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .unauthorized)
            #expect(error.status == 401)
        }
    }

    @Test("a reply with no token is a decode failure, not an empty token")
    func replyWithoutTokenIsRefused() async throws {
        let fake = FakeLibrary()

        // An empty string would be accepted by every call downstream and rejected
        // by the coordinator on the first request, which is a long way from here.
        await #expect(throws: ReactorError.self) {
            try await fetching(fake, result: #"{"token":"t"}"#)
        }
        await #expect(throws: ReactorError.self) {
            try await fetching(fake, result: #"{"jwt":""}"#)
        }
        await #expect(throws: ReactorError.self) {
            try await fetching(fake, result: nil)
        }
        await #expect(throws: ReactorError.self) {
            try await fetching(fake, result: "not json")
        }
    }
}
