// swift-tools-version: 6.0
//
// The Swift SDK's manifest, at the repository root because SwiftPM requires it
// there: a package living in a subdirectory cannot be resolved as a git
// dependency, and `.package(url:)` has no notion of a path inside the repo.
// Everything the manifest points at lives under `sdks/swift/`, beside the other
// bindings — only this file has to sit up here.
//
// Consumers therefore depend on the repository itself:
//
//     .package(url: "https://github.com/reactor-team/reactor-client-sdks", from: "1.0.0")
//
// which is also why Swift releases are tagged `v<version>` rather than
// `swift-v<version>`: SwiftPM only recognises `1.0.0` and `v1.0.0`. See
// CONTRIBUTING.md, where that exception is written down beside `python-v*`.

import PackageDescription

// ── Where libreactor_ffi comes from ──────────────────────────────────────────
//
// Three answers, and all three are called `CReactorFFI`: that is the module
// `import CReactorFFI` resolves, and the name the XCFramework's own module map
// declares. The SDK's own code cannot tell which one it got, which is the point —
// what the development loop tests is what ships.
//
//   REACTOR_XCFRAMEWORK=<path>   a binary target over an archive already on disk.
//                                SwiftPM requires that path to be relative to the
//                                package root, which `target/xcframework/` — where
//                                build:xcframework puts it — already is.
//   REACTOR_SWIFT_DEV=1          the crate's header, with the library linked from
//                                a cargo build by scripts/swift.sh. The inner
//                                loop: waiting for four Rust slices before every
//                                `swift test` is not a loop anyone would use.
//   neither                      the XCFramework a release publishes, which is
//                                the only shape that works for an app — there is
//                                no source distribution, because building the
//                                native library needs a Rust toolchain and a
//                                libwebrtc download.
//
// That last one has no URL yet, because no release exists: the tag namespace and
// the release workflow arrive with REA-5588, and the checksum of an archive has
// to be committed alongside the version that produces it. Until then it falls
// back to the development shape rather than failing, so a bare `swift build` —
// and Xcode opening this package — keep working exactly as they do today. The day
// a release is cut, filling in `releasedFFI` makes the consumer's answer the
// default without anything else here moving.
let releasedFFI: (url: String, checksum: String)? = nil

let ffiTarget: Target = {
    if let archive = Context.environment["REACTOR_XCFRAMEWORK"] {
        return .binaryTarget(name: "CReactorFFI", path: archive)
    }
    if let released = releasedFFI, Context.environment["REACTOR_SWIFT_DEV"] == nil {
        return .binaryTarget(name: "CReactorFFI", url: released.url, checksum: released.checksum)
    }
    return .systemLibrary(name: "CReactorFFI", path: "sdks/swift/Sources/CReactorFFI")
}()

let package = Package(
    name: "reactor-sdk",
    // macOS 13 and iOS 16 — what the shipped binary actually requires, not the
    // lowest floor any one slice could hold.
    //
    // reactor-webrtc's prebuilt libwebrtc goes down to macOS 11 on arm64, but
    // its x86_64 build is made for 13, and the XCFramework's macOS slice is a
    // single lipo'd arm64 + x86_64 archive. So 13 is the floor for both
    // architectures, and saying 11 here would let SwiftPM resolve this package
    // for an app targeting macOS 11 that then fails against a binary needing 13.
    //
    // Intel Macs are why this is 13 rather than 11: an arm64-only slice could
    // hold the lower floor, at the price of every x86_64 Mac. That trade is
    // decided here in favour of Intel. (The Intel *simulator* is a different
    // question and is genuinely unsupported — see rust-toolchain.toml.) The
    // platform table in sdks/swift/README.md is the full story.
    platforms: [
        .macOS(.v13),
        .iOS(.v16),
    ],
    products: [
        .library(name: "Reactor", targets: ["Reactor"]),
        // The seven examples, numbered as in sdks/python/examples/ so the set is
        // comparable across bindings at a glance. Product names carry the numbers
        // because that is what a reader types: `swift run 01_connect_and_receive`.
        .executable(name: "01_connect_and_receive", targets: ["Example01ConnectAndReceive"]),
        .executable(name: "02_upload_image", targets: ["Example02UploadImage"]),
        .executable(name: "03_pause_and_resume", targets: ["Example03PauseAndResume"]),
        .executable(name: "04_publish_track", targets: ["Example04PublishTrack"]),
        .executable(name: "05_multi_connection", targets: ["Example05MultiConnection"]),
        .executable(name: "06_record_clip", targets: ["Example06RecordClip"]),
        .executable(name: "07_frame_metadata", targets: ["Example07FrameMetadata"]),
    ],
    targets: [
        // The C ABI, imported through a module map over the header the Rust
        // crate publishes. There is no hand-written copy of any signature here:
        // unlike the Python binding's ctypes declarations, the compiler derives
        // every one of them from crates/reactor-ffi/include/reactor_ffi.h, so a
        // signature that moves in the FFI is a compile error rather than a
        // corrupted stack at the call.
        //
        // The module map names no library to link: in this shape the library is
        // linked on the command line by scripts/swift.sh, and in the XCFramework
        // shape the archive carries it. Which of the two this is was decided
        // above, and the SDK's code is written against neither.
        ffiTarget,
        .target(
            name: "Reactor",
            dependencies: ["CReactorFFI"],
            path: "sdks/swift/Sources/Reactor",
            // The frameworks libwebrtc needs at final link.
            //
            // A **static** library carries none of the link flags its build
            // emitted: `reactor-webrtc-sys`'s build.rs prints them for a cargo
            // link, and nothing survives into the archive. So the app that links
            // this package is where they have to be declared, which is here —
            // and a missing one is an undefined-symbol failure in the
            // *consumer's* build, naming a symbol they have never heard of.
            //
            // `Network` is on the iOS list and **not** in what build.rs emits:
            // libwebrtc's RTCNetworkMonitor calls nw_path_monitor_create and
            // friends, and an iOS link without it fails with nine undefined
            // `_nw_*` symbols. Found by linking, not by reading the list. The
            // gap is upstream's; declaring it here is what makes this package
            // usable in the meantime.
            //
            // scripts/build-swift-xcframework.sh links an iOS cdylib against
            // exactly this list, so a framework going missing is a failed build
            // here rather than a failed build in someone's app.
            linkerSettings: [
                .linkedLibrary("c++"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreAudio"),
                .linkedFramework("CoreFoundation"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreVideo"),
                .linkedFramework("Foundation"),
                .linkedFramework("Metal"),
                .linkedFramework("VideoToolbox"),
                .linkedFramework("Network", .when(platforms: [.iOS])),
                .linkedFramework("UIKit", .when(platforms: [.iOS])),
                .linkedFramework("AppKit", .when(platforms: [.macOS])),
                .linkedFramework("CoreImage", .when(platforms: [.macOS])),
                .linkedFramework("IOKit", .when(platforms: [.macOS])),
                .linkedFramework("IOSurface", .when(platforms: [.macOS])),
                .linkedFramework("OpenGL", .when(platforms: [.macOS])),
                .linkedFramework("QuartzCore", .when(platforms: [.macOS])),
                .linkedFramework("ScreenCaptureKit", .when(platforms: [.macOS])),
                .linkedFramework("Security", .when(platforms: [.macOS])),
                .linkedFramework("SystemConfiguration", .when(platforms: [.macOS])),
            ]
        ),
        // Everything the examples share: reading the environment, counting
        // frames, and writing a PNG so a reader can see that what arrived was the
        // right something rather than merely something.
        .target(
            name: "ExampleSupport",
            dependencies: ["Reactor"],
            path: "sdks/swift/Examples/Support"
        ),
        .executableTarget(
            name: "Example01ConnectAndReceive",
            dependencies: ["Reactor", "ExampleSupport"],
            path: "sdks/swift/Examples/01_connect_and_receive"
        ),
        .executableTarget(
            name: "Example02UploadImage",
            dependencies: ["Reactor", "ExampleSupport"],
            path: "sdks/swift/Examples/02_upload_image"
        ),
        .executableTarget(
            name: "Example03PauseAndResume",
            dependencies: ["Reactor", "ExampleSupport"],
            path: "sdks/swift/Examples/03_pause_and_resume"
        ),
        .executableTarget(
            name: "Example04PublishTrack",
            dependencies: ["Reactor", "ExampleSupport"],
            path: "sdks/swift/Examples/04_publish_track"
        ),
        .executableTarget(
            name: "Example05MultiConnection",
            dependencies: ["Reactor", "ExampleSupport"],
            path: "sdks/swift/Examples/05_multi_connection"
        ),
        .executableTarget(
            name: "Example06RecordClip",
            dependencies: ["Reactor", "ExampleSupport"],
            path: "sdks/swift/Examples/06_record_clip"
        ),
        .executableTarget(
            name: "Example07FrameMetadata",
            dependencies: ["Reactor", "ExampleSupport"],
            path: "sdks/swift/Examples/07_frame_metadata"
        ),
        .testTarget(
            name: "ReactorTests",
            dependencies: ["Reactor", "CReactorFFI"],
            path: "sdks/swift/Tests/ReactorTests"
        ),
    ]
)
