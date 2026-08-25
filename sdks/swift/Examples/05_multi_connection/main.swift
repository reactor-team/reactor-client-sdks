// 05 · Two clients on one session, the second adopting it by id.
//
//     export REACTOR_API_KEY=rk_...
//     scripts/swift.sh run 05_multi_connection
//
// What this teaches, and what it cost the C++ SDK to learn: a **session-scoped
// token cannot adopt a session it did not create** — that answers 403, and no
// unit test would ever produce it. So both clients here are made from the same
// key, and the second one adopts by passing the first one's session id.
//
// The other half: a creator that goes away without disconnecting orphans the
// session. Both clients tear down in a defer.

import ExampleSupport
import Foundation
import Reactor

await runExample {
    let model = Env.model(default: "reactor/helios")
    guard let key = Env.apiKey, !Env.local else {
        fail(
            "this example needs REACTOR_API_KEY against the cloud: adopting a session is a platform behaviour"
        )
    }

    // One token, two clients. A token scoped to one session could not do this.
    let token = try await Reactor.fetchJWT(
        apiKey: key, apiURL: Env.apiURL, options: .init(models: [model]), local: false)

    let creator = try Reactor(model: model, jwt: token, apiURL: Env.apiURL)
    defer { creator.close() }
    let creatorWatch = watch(creator)

    try await creator.connect()
    guard let sessionID = creator.sessionID else {
        fail("connected but there is no session id, which should be impossible")
    }
    print("creator session: \(sessionID)")

    try await creator.sendCommand("set_prompt", ["prompt": "a harbour at night"])
    try await creator.sendCommand("start")

    let creatorFrames = FrameCounter(label: "05-creator")
    let creatorSubscription = try creator.track("main_video").onFrame { creatorFrames.submit($0) }

    // The second client adopts the same session rather than creating one. Nothing
    // else about it differs.
    let observer = try Reactor(model: model, jwt: token, apiURL: Env.apiURL)
    defer { observer.close() }
    let observerWatch = watch(observer)

    try await observer.connect(sessionID: sessionID)
    print("observer session: \(observer.sessionID ?? "none")")
    if observer.sessionID != sessionID {
        print(
            "note: the observer landed on a different session, which means adoption did not happen")
    }

    let observerFrames = FrameCounter(label: "05-observer")
    let observerSubscription = try observer.track("main_video").onFrame {
        observerFrames.submit($0)
    }

    try await hold(Env.seconds(default: 15), "both clients")
    print("creator: \(creatorFrames.frames) frames")
    print("observer: \(observerFrames.frames) frames")

    _ = (creatorWatch, observerWatch, creatorSubscription, observerSubscription)

    // Only the creator ends the session; the observer just leaves.
    try await creator.disconnect()
}
