import Foundation
import Testing

@testable import Reactor

@Suite("Tracks and receiving")
struct TrackTests {

    private func makeClient(fake: FakeLibrary) throws -> Reactor {
        try Reactor(
            model: "reactor/helios",
            jwt: nil,
            apiURL: Reactor.defaultAPIURL,
            local: false,
            eventQueue: nil,
            ffi: fake.table)
    }

    /// A session declaring the shapes the tests need, in an order that is *not*
    /// alphabetical — which is the whole point of the first test below.
    private func declaringSession(_ fake: FakeLibrary) {
        fake.setTracks([
            (name: "main_video", kind: "video", direction: "recvonly"),
            (name: "source", kind: "video", direction: "sendonly"),
            (name: "audio_out", kind: "audio", direction: "recvonly"),
        ])
    }

    // MARK: - What the session declares

    @Test("tracks come back in declaration order, not sorted")
    func declarationOrderIsKept() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        // Order is part of the contract: tracks[0] is what the session declared
        // first. A name-keyed dictionary would sort these alphabetically —
        // audio_out, main_video, source — and silently renumber what that index
        // means for every caller.
        #expect(client.tracks.map(\.name) == ["main_video", "source", "audio_out"])
    }

    @Test("an undeclared name is refused, naming what is declared")
    func unknownTrackIsRefused() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        do {
            _ = try client.track("man_video")
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .notFound)
            // The names it does declare, or the caller is left guessing at a typo.
            #expect(error.message.contains("main_video"))
            #expect(error.message.contains("source"))
        }
    }

    @Test("any name is accepted before the session declares anything")
    func undeclaredSessionAcceptsAnyName() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        // "[]" means "no tracks yet", not "no such track" — which is what lets
        // handlers be registered before connecting.
        let track = try client.track("main_video")
        #expect(track.kind == nil)
        #expect(track.direction == nil)
    }

    @Test("the same object comes back for a name, so handlers stay registered")
    func trackIdentityIsStable() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let first = try client.track("main_video")
        let second = try client.track("main_video")

        #expect(first === second)
    }

    @Test("kind and direction are read live, not cached from the first look")
    func declarationsAreReadLive() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("main_video")
        #expect(track.kind == nil)

        // What the session declares arrives after connect, and a cached answer
        // here would keep saying "nothing declared" for the life of the client.
        declaringSession(fake)
        #expect(track.kind == .video)
        #expect(track.direction == .recvonly)
    }

    @Test("a declaration this SDK cannot parse is dropped, not guessed at")
    func unparseableDeclarationIsDropped() throws {
        let fake = FakeLibrary()
        fake.setRawTracksJSON(
            #"[{"name":"main_video","kind":"video","direction":"recvonly"},"#
                + #"{"name":"mystery","kind":"hologram","direction":"recvonly"}]"#)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        // A track whose kind we invented would accept the wrong handlers and
        // refuse the right ones.
        #expect(client.tracks.map(\.name) == ["main_video"])
    }

    @Test("paused is read from the session, so a reconnect cannot leave it stale")
    func pausedIsReadLive() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("main_video")
        #expect(!track.paused)

        fake.setPausedTracks(["main_video"])
        #expect(track.paused)

        // Recvonly tracks resume automatically after a reconnect; a cached true
        // would go on claiming otherwise.
        fake.setPausedTracks([])
        #expect(!track.paused)
    }

    @Test("the media id arrives from on_track")
    func midComesFromTheTrackEvent() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("main_video")
        #expect(track.mid == nil)

        fake.fireTrack(name: "main_video", mid: "0")
        #expect(track.mid == "0")
    }

    // MARK: - Filters

    @Test("filters chain in either order")
    func filtersChain() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let byKindFirst = client.tracks.withKind(.video).withDirection(.recvonly)
        let byDirectionFirst = client.tracks.withDirection(.recvonly).withKind(.video)

        #expect(byKindFirst.map(\.name) == ["main_video"])
        #expect(byDirectionFirst.map(\.name) == ["main_video"])
    }

    @Test("one() takes the single match")
    func oneTakesTheSingleMatch() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.tracks.withDirection(.sendonly).one()
        #expect(track.name == "source")
    }

    @Test("one() refuses none, and refuses several by naming them")
    func oneRefusesAmbiguity() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        #expect(throws: ReactorError.self) {
            try client.tracks.withKind(.audio).withDirection(.sendonly).one()
        }

        do {
            _ = try client.tracks.withKind(.video).one()
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.message.contains("main_video"))
            #expect(error.message.contains("source"))
        }
    }

    // MARK: - The refusals

    @Test("a frame handler on a sendonly track is refused")
    func frameHandlerOnSendonlyIsRefused() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        do {
            _ = try source.onFrame { _ in }
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            // It would never fire, and a handler that never fires is exactly the
            // silent failure this SDK refuses.
            #expect(error.code == .invalidState)
            #expect(error.message.contains("sendonly"))
            #expect(error.message.contains("pushFrame"))
        }
    }

    @Test("a video handler on an audio track is refused, naming the other method")
    func videoHandlerOnAudioTrackIsRefused() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let audio = try client.track("audio_out")
        do {
            _ = try audio.onFrame { _ in }
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .invalidState)
            #expect(error.message.contains("onAudio"))
        }

        // And the reverse.
        let video = try client.track("main_video")
        #expect(throws: ReactorError.self) { _ = try video.onAudio { _ in } }
    }

    // MARK: - Delivery

    @Test("a frame runs its handler inline, on the thread the library called on")
    func framesRunInline() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("main_video")
        let seen = Locked<(thread: Thread, count: Int)?>(nil)
        let subscription = try track.onFrame { _ in
            seen.withLock { $0 = (Thread.current, ($0?.count ?? 0) + 1) }
        }
        defer { subscription.cancel() }

        fake.fireFrame(track: "main_video")

        // Inline is the design, not an accident: while this handler runs the
        // library keeps only the newest frame, so blocking here IS the
        // backpressure. Queueing would look correct and silently trade a bounded
        // drop for unbounded latency and memory.
        let result = seen.withLock { $0 }
        #expect(result?.count == 1)
        #expect(result?.thread == Thread.current)
    }

    @Test("a frame carries its pixels and its trailer")
    func frameCarriesTheTrailer() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("main_video")
        let seen = Locked<VideoFrame?>(nil)
        let subscription = try track.onFrame { frame in seen.withLock { $0 = frame } }
        defer { subscription.cancel() }

        fake.fireFrame(
            track: "main_video", width: 2, height: 2, frameID: 77, captureTimeUs: 1_234_567,
            userData: Array("tag".utf8), fill: 0x10)

        let frame = seen.withLock { $0 }
        #expect(frame?.trackName == "main_video")
        #expect(frame?.width == 2)
        #expect(frame?.height == 2)
        // BGRA: four bytes per pixel, and the copy has to be all of them.
        #expect(frame?.pixels.count == 16)
        #expect(frame?.pixels.allSatisfy { $0 == 0x10 } == true)
        #expect(frame?.frameID == 77)
        #expect(frame?.captureTimeUs == 1_234_567)
        #expect(frame.flatMap { $0.userData.map { String(decoding: $0, as: UTF8.self) } } == "tag")
    }

    @Test("a frame without a trailer reports zeros and no tag")
    func frameWithoutTrailer() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("main_video")
        let seen = Locked<VideoFrame?>(nil)
        let subscription = try track.onFrame { frame in seen.withLock { $0 = frame } }
        defer { subscription.cancel() }

        fake.fireFrame(track: "main_video")

        // No published model attaches a trailer today, so this is the shape
        // every example actually sees: zeros, and nothing to read.
        let frame = seen.withLock { $0 }
        #expect(frame?.frameID == 0)
        #expect(frame?.captureTimeUs == 0)
        #expect(frame?.userData == nil)
    }

    @Test("onRawFrame hands over the library's own buffer")
    func rawFrameIsNotCopied() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("main_video")
        let seen = Locked<(bytes: Int, first: UInt8?, tag: String?)>((0, nil, nil))
        let subscription = try track.onRawFrame { frame in
            let tag = frame.userData.map { String(decoding: $0, as: UTF8.self) }
            seen.withLock { $0 = (frame.pixels.count, frame.pixels.first, tag) }
        }
        defer { subscription.cancel() }

        fake.fireFrame(track: "main_video", userData: Array("raw".utf8), fill: 0x7F)

        let result = seen.withLock { $0 }
        #expect(result.bytes == 16)
        #expect(result.first == 0x7F)
        #expect(result.tag == "raw")
    }

    @Test("a frame for a track nobody handles is dropped")
    func unhandledFrameIsDropped() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("main_video")
        let count = Locked(0)
        let subscription = try track.onFrame { _ in count.withLock { $0 += 1 } }
        defer { subscription.cancel() }

        // A different track, and the empty name the library uses when a
        // transceiver could not be matched to a declaration. Neither may reach a
        // handler registered for main_video.
        fake.fireFrame(track: "audio_out")
        fake.fireFrame(track: "")

        #expect(count.withLock { $0 } == 0)
    }

    @Test("audio arrives decoded, with its rate and channel count")
    func audioIsDelivered() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("audio_out")
        let seen = Locked<AudioFrame?>(nil)
        let subscription = try track.onAudio { frame in seen.withLock { $0 = frame } }
        defer { subscription.cancel() }

        fake.fireAudio(track: "audio_out", samples: [3, -3, 4, -4], sampleRate: 48000, channels: 1)

        let frame = seen.withLock { $0 }
        #expect(frame?.samples == [3, -3, 4, -4])
        #expect(frame?.sampleRate == 48000)
        #expect(frame?.channels == 1)
    }

    @Test("a cancelled frame subscription stops receiving")
    func cancelledFrameSubscriptionStops() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let track = try client.track("main_video")
        let count = Locked(0)
        let subscription = try track.onFrame { _ in count.withLock { $0 += 1 } }

        fake.fireFrame(track: "main_video")
        subscription.cancel()
        fake.fireFrame(track: "main_video")

        #expect(count.withLock { $0 } == 1)
    }

    @Test("closing stops delivery without reaching into a freed client")
    func framesAfterCloseAreDropped() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)

        let track = try client.track("main_video")
        let count = Locked(0)
        let subscription = try track.onFrame { _ in count.withLock { $0 += 1 } }
        defer { subscription.cancel() }

        client.close()
        // reactor_destroy promises no callback starts after it answers 0, but a
        // fake can be ruder than the library — and the SDK still has to cope.
        fake.fireFrame(track: "main_video")

        #expect(count.withLock { $0 } == 0)
    }
}
