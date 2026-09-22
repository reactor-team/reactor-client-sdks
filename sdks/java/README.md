# Reactor Java SDK

[![Maven Central: reactor-sdk](https://img.shields.io/maven-central/v/inc.reactor/reactor-sdk.svg?label=reactor-sdk)](https://central.sonatype.com/artifact/inc.reactor/reactor-sdk)
[![build](https://img.shields.io/github/actions/workflow/status/reactor-team/reactor-client-sdks/ci.yml?branch=main)](https://github.com/reactor-team/reactor-client-sdks/actions)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/reactor-team/reactor-client-sdks/blob/main/LICENSE)

A Reactor client for desktop JVM applications, bound to `libreactor_ffi`
through the [Foreign Function & Memory API][ffm] — no JNI, no JNA, and no
native code of its own.

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

One dependency line, on Gradle and on Maven alike, and nothing configured:

```kotlin
implementation("inc.reactor:reactor-sdk:1.0.0")
```

```xml
<dependency>
  <groupId>inc.reactor</groupId>
  <artifactId>reactor-sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

That pulls `reactor-sdk-natives`, one jar carrying every platform's library, and
the SDK loads the one this machine needs. No plugin, no classifier, no profile,
and nothing to change when the code runs somewhere else.

| Coordinate | What it carries |
| --- | --- |
| `reactor-sdk` | the binding, and a runtime dependency on the natives |
| `reactor-sdk-natives` | every platform's library — about 50 MB — plus one classified jar each |
| `reactor-sdk-audio` | optional microphone and speaker helpers |
| `reactor-sdk-jackson` | optional `JsonValue` / `JsonNode` adapters |
| `reactor-sdk-kotlin` | optional Kotlin facade: `suspend` functions and flows |

### If 50 MB is too much

A build that knows exactly what it runs on — a container image, usually — takes
the classifier it wants instead:

```kotlin
implementation("inc.reactor:reactor-sdk:1.0.0") {
    exclude(group = "inc.reactor", module = "reactor-sdk-natives")
}
runtimeOnly("inc.reactor:reactor-sdk-natives:1.0.0:macos-arm64")
```

The classifiers are `linux-x86_64`, `linux-aarch64`, `macos-arm64`,
`macos-x86_64` and `windows-x86_64`. Take the wrong one and the failure is loud
and immediate: the SDK names the platform it looked for and the three places it
looked.

This is opt-in, and it is the only part of the packaging you can get wrong —
which is why it is the second thing on this page rather than the first. An
earlier design tried to make Gradle pick the classifier for you from variant
attributes. It does not work: a plain JVM project requests no operating-system
or architecture attribute, so Gradle selects the ordinary runtime variant and no
native artifact arrives at all. Expressing those attributes in the consumer only
makes resolution ambiguous. Zero configuration through variants would require
every consumer to apply a plugin, which is not zero configuration.

## Authenticating

Two ways in, and which one you want depends on where the code runs.

```java
// On a machine you control: the client exchanges the key itself, on connect.
ReactorOptions.builder(apiUrl, "reactor/helios").apiKey(apiKey).build();

// Anywhere else: your backend holds the key and hands out tokens.
ReactorOptions.builder(apiUrl, "reactor/helios").jwt(jwt).build();
```

`apiKey` is the one to reach for when this SDK is the thing talking to the
platform. The token it mints is scoped to the model the options name, so a
token that escapes is worth sessions on that model rather than everything the
key can reach, and it is minted per connect rather than once for the life of
the process — which matters, because a token carries a session grant with a
capacity, and a session spends its slot for the life of the grant whether or
not you disconnect.

`jwt` is for a token minted elsewhere — by your own backend, through
`Reactor.fetchJwt`, or by whatever else owns the key. A token you supply is
yours: the SDK never replaces it, and never mints over it even if a key is
also set. Pass a key to a client you do not control and you have shipped the
key.

One case needs the broader token and gets it without being asked:
`connect(sessionId, connectionId)` adopts a session this client did not
create, which a model-scoped token cannot reach, so a client holding a key
mints an unscoped one for that connect and rebuilds itself around it.

## From Kotlin

`reactor-sdk-kotlin` is a facade over the same binding, not a second one. It holds
no native symbol — `scripts/check-abi-parity.py` fails the build if one appears
there — and every method forwards to the Java client underneath, which is what
keeps the two from being able to disagree.

```kotlin
implementation("inc.reactor:reactor-sdk-kotlin:1.0.0")
```

```kotlin
ReactorClient.open(reactorOptions(apiUrl, "reactor/helios") { apiKey(apiKey) }).use { client ->
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

### Versioning

`reactor-sdk` and `reactor-sdk-kotlin` carry **one version between them**. Both
are published from this build, from the single `version=` line in
`gradle.properties`, so a release of either is a release of both. Pin whichever
you depend on and there is nothing to work out: there is no pairing table, and no
release in which the facade lags the binding it forwards to.

The facade is a separate coordinate rather than part of `reactor-sdk` for one
reason — it carries `kotlinx-coroutines-core`, and a Java consumer should not.
That is a packaging decision, not a lifecycle one.

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
| `reactor-sdk-natives` | every platform's library in the main jar, plus one classified artifact each |
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
