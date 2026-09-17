# Reactor Java SDK

A Reactor client for desktop JVM applications, bound to `libreactor_ffi`
through the [Foreign Function & Memory API][ffm] — no JNI, no JNA, and no
native code of its own.

> **Status: not published yet.** The binding is complete and all eight example
> scenarios run against published production models; what remains before a first
> release is the live integration suite, the endurance suite, and the version bump
> that is itself the release switch.

## Requirements

| | |
| --- | --- |
| Minimum JDK | **22** — where the Foreign Function & Memory API stopped being a preview feature |
| Built and tested on | 22 and 25 LTS, on every CI run |
| Platforms | Linux x86_64/aarch64 (glibc 2.34+), macOS arm64 (11+) and x86_64 (13+), Windows x86_64 |

Java 21 and earlier cannot run this SDK: FFM is a preview feature there, and
`--enable-preview` is not a reasonable thing to ask of a consumer's production
build.

Android is **not** a target. FFM does not run there; the Kotlin SDK covers
Android through JNI.

## Native access

Every call this SDK makes into the native library goes through a *restricted*
method. From JDK 24 the JVM warns on each one unless the module is granted
access, and a future release will refuse them outright. Consumers pass:

```
--enable-native-access=inc.reactor.sdk     # on the module path
--enable-native-access=ALL-UNNAMED         # on the classpath
```

The module declares itself as `inc.reactor.sdk` precisely so this can be
granted to one module rather than to everything.

## What gets published

One dependency line, and nothing configured:

```kotlin
implementation("inc.reactor:reactor-sdk:1.0.0")
```

Gradle resolves the native library for the host from this SDK's own variants — no
plugin applied, no classifier written. Maven ignores that metadata, so Maven users
name the aggregate instead:

```xml
<dependency>
  <groupId>inc.reactor</groupId>
  <artifactId>reactor-sdk-platform</artifactId>
  <version>1.0.0</version>
</dependency>
```

| Coordinate | What it carries |
| --- | --- |
| `reactor-sdk` | the binding, and the variants that resolve a native library |
| `reactor-sdk-natives` (5 classifiers) | one native library each |
| `reactor-sdk-platform` | POM-only: the SDK plus all five, for Maven |
| `reactor-sdk-audio` | optional microphone and speaker helpers |
| `reactor-sdk-jackson` | optional `JsonValue` / `JsonNode` adapters |
| `reactor-sdk-kotlin` | optional Kotlin facade: `suspend` functions and flows |

## From Kotlin

`reactor-sdk-kotlin` is a facade over the same binding, not a second one. It holds
no native symbol — `scripts/check-abi-parity.py` fails the build if one appears
there — and every method forwards to the Java client underneath, which is what
keeps the two from being able to disagree.

```kotlin
implementation("inc.reactor:reactor-sdk-kotlin:1.0.0")
```

```kotlin
val jwt = ReactorClient.fetchJwt(apiUrl, apiKey)

ReactorClient.open(reactorOptions(apiUrl, "reactor/helios") { jwt(jwt) }).use { client ->
    client.connect()

    client.sendCommand("set_prompt", JsonValue.`object`().put("prompt", prompt).build())
    client.sendCommand("start")

    client.track("main_video").onVideoFrame { frame ->
        render(frame.toByteArray(), frame.width(), frame.height())
    }

    client.statusFlow().collect { println(it) }
}
```

What it adds: `suspend` in place of every `CompletableFuture`, a `Flow` for each
control event, `Dispatchers.Main.asReactorDispatcher()` for a UI toolkit's thread,
tracks as a plain `List<ReactorTrack>` with `tracks["main_video"]`, and a builder
block for the options.

Two things it does **not** do. Frames stay a callback: they arrive on the FFI's
delivery thread and blocking there is the backpressure — while a handler runs, the
FFI keeps only the newest frame. A flow puts a channel in between, which turns a
bounded frame drop into unbounded latency and memory. `videoFrames()` and
`audioFrames()` exist for callers who want one anyway, conflated so they drop in
the same shape the FFI already does, and their KDoc says so. And nullability needs
no wrapper: the Java modules are `@NullMarked`, so Kotlin already sees which types
can be null.

Cancelling a coroutine that awaits one of these cancels **the await, not the
operation** — the native call is already in flight and cannot be recalled. A
command whose caller walked away still reached the model.

The aggregate is POM-only, not a fat jar — the shape JavaCPP publishes as
`opencv-platform`. `-uber` would mean a fat jar and `-all` means all *modules* of
a library in this ecosystem, not all platforms.

## Finding the native library

`libreactor_ffi` is looked for in three places, in this order. The order is part
of the SDK's contract, not an implementation detail:

1. **`REACTOR_FFI_LIB`**, pointing straight at a library file. It always wins,
   and pointing it at something that is not a file is an error rather than a
   fallthrough — someone who set it meant to use *that* library.
2. **The packaged resource** for this platform, extracted to a versioned cache
   directory. This is how a normal consumer gets one, with nothing configured.
3. **An enclosing checkout's `target/release`**, which is what makes running the
   published SDK against a local `cargo build -p reactor-ffi --release` work.

Failing to find one reports all three attempts, because the step you are failing
at is the only useful thing to know.

**Rebuild the library after pulling changes under `crates/`.** The exported
surface is hand-copied in several places and checked by name, so a library older
than the crates still links and still resolves every symbol — it only misbehaves
at the call, which looks like a hang rather than a version error. This SDK checks
`reactor_abi_version()` at load and refuses a mismatch, which turns most of that
into a message; it cannot catch a function whose *signature* changed under a name
that stayed the same. `scripts/check-abi-parity.py` covers that half, and for
this binding it also compares the arity each `FunctionDescriptor` declares
against the header's own prototype — a check the other bindings' declarations
cannot be taken apart for.

## Development

The whole toolchain — the JDK and Gradle included — is pinned by
[mise](https://mise.jdx.dev) and locked in `mise.lock` at the repository root.
There is **no Gradle wrapper**: it would be a second, unlocked source of truth
for the Gradle version, and mise's `[tools]` table is deliberately the only one.

```bash
mise install                 # the pinned JDK, Gradle and everything else
mise run build:java          # jars
mise run test:java           # unit tests
mise run lint:java           # formatting (palantir-java-format, via Spotless)
mise run fmt:java            # apply formatting
```

Compilation always targets release 22, whichever JDK runs the build. To build
on the floor rather than on the pinned JDK — what CI's second matrix leg does —
make that JDK the one Gradle runs on:

```bash
mise exec java@temurin-22.0.2+9 -- \
  gradle --project-dir sdks/java -PreactorJdk=22 test
```

## Layout

| Module | What it is |
| --- | --- |
| `reactor-sdk` | the binding and the public API |
| `reactor-sdk-natives` | the native library, one classified artifact per platform |
| `reactor-sdk-platform` | POM-only aggregate, for builds that cannot read Gradle Module Metadata |
| `reactor-sdk-audio` | optional microphone and speaker helpers; nothing that opens audio hardware is on the mandatory import path |
| `reactor-sdk-jackson` | optional interop between `JsonValue` and Jackson's `JsonNode` |
| `reactor-sdk-kotlin` | optional Kotlin facade over the binding; holds no native symbol of its own |
| `examples` | the numbered scenarios every Reactor SDK ships |
| `integration-tests` | the live suite, against a real model |
| `endurance-tests` | long-running leak and resource-trend scenarios |

`integration-tests` and `endurance-tests` are excluded from `check`: both need a
live session, so neither can gate a unit-test run.

[ffm]: https://docs.oracle.com/en/java/javase/22/core/foreign-function-and-memory-api.html
[REA-6435]: https://linear.app/reactor-team/issue/REA-6435
