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
        .library(name: "Reactor", targets: ["Reactor"])
    ],
    targets: [
        // The C ABI, imported through a module map over the header the Rust
        // crate publishes. There is no hand-written copy of any signature here:
        // unlike the Python binding's ctypes declarations, the compiler derives
        // every one of them from crates/reactor-ffi/include/reactor_ffi.h, so a
        // signature that moves in the FFI is a compile error rather than a
        // corrupted stack at the call.
        //
        // The module map names no library to link, because nothing calls into
        // the FFI yet. Linking arrives with the FFI layer, and the XCFramework
        // that carries libreactor_ffi for each platform slice arrives with
        // packaging.
        .systemLibrary(
            name: "CReactorFFI",
            path: "sdks/swift/Sources/CReactorFFI"
        ),
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
        .testTarget(
            name: "ReactorTests",
            dependencies: ["Reactor", "CReactorFFI"],
            path: "sdks/swift/Tests/ReactorTests"
        ),
    ]
)
