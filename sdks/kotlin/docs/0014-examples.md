# K14 — Seven examples on three consumers

The Kotlin SDK examples cover the same seven production scenarios in three
forms: desktop Kotlin, desktop Java and Android Kotlin. The source runners are
under [`examples/`](../examples/README.md). They intentionally share scenario
numbers and acceptance evidence so a parity report can compare the three
consumers without comparing unrelated UI code.

Desktop Kotlin uses `runBlocking` only at the command-line boundary and keeps
the SDK API suspend-first. Desktop Java uses the JVM `Reactor` adapter, which
owns a coroutine scope and exposes blocking methods for Java callers; it must
never be invoked from an Android main thread. Android Kotlin launches work from
an Activity or ViewModel scope, uses application-owned permissions, and routes
content URIs through `ContentUploads`/`ContentRecordings` rather than treating
them as filesystem paths.

For each scenario, record the model/runtime, SDK commit, platform, result, and
sanitized evidence. Use `try/finally` teardown and an owner-qualified model.
Production runs are required for parity; local models are useful for diagnosing
binding failures but do not replace the production evidence.
