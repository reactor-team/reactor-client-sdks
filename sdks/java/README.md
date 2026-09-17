# Reactor Java SDK

A Reactor client for desktop JVM applications, bound to `libreactor_ffi`
through the [Foreign Function & Memory API][ffm] — no JNI, no JNA, and no
native code of its own.

> **Status: under construction.** This directory currently holds the build,
> the toolchain contract and the CI job. The binding itself lands slice by
> slice ([REA-6435][] onward) and nothing is published yet.

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
| `reactor-sdk-audio` | optional microphone and speaker helpers; nothing that opens audio hardware is on the mandatory import path |
| `examples` | the numbered scenarios every Reactor SDK ships |
| `integration-tests` | the live suite, against a real model |
| `endurance-tests` | long-running leak and resource-trend scenarios |

`integration-tests` and `endurance-tests` are excluded from `check`: both need a
live session, so neither can gate a unit-test run.

[ffm]: https://docs.oracle.com/en/java/javase/22/core/foreign-function-and-memory-api.html
[REA-6435]: https://linear.app/reactor-team/issue/REA-6435
