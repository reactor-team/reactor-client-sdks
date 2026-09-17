# Kotlin SDK examples

The example suite runs the same seven scenarios in three consumers. Every
scenario has its own directory, matching the Swift SDK layout:

| Consumer | Runner | Purpose |
| --- | --- | --- |
| Desktop Kotlin | `desktop-kotlin/` | Coroutine-first JVM usage with optional desktop media helpers |
| Desktop Java | `desktop-java/` | Java application usage through the JVM blocking adapter |
| Android Kotlin | `android-kotlin/` | Android lifecycle, content URI and media adapter usage |

`01_connect_and_receive/` through `07_frame_metadata/` each contain the three
consumer entry points. They are real runners, not placeholder snippets: each
reads `REACTOR_MODEL` and `REACTOR_TOKEN`, refuses missing credentials, and
connects to that model before executing its scenario. Android entry points are
launched from the instrumentation sample's Activity/ViewModel scope.

Every runner uses a short-lived token from the application environment. No
API key is embedded in source. Set `REACTOR_MODEL` and `REACTOR_TOKEN` before
running a scenario against a production model; local development must be
enabled explicitly in the runner.

The numbered scenarios intentionally match the other Reactor SDKs:

1. Connect, send the first command, receive a reply and inspect frames.
2. Upload bytes and pass the resulting `FileRef` to a command.
3. Pause and resume a declared track.
4. Publish a track and push a tagged frame.
5. Adopt a session from a second client.
6. Request a clip and download it with progress.
7. Inspect frame trailer metadata (frame ID, capture timestamp and user data).

Each platform runner calls `runScenario(number)` and uses `try/finally` to
close clients. The Android runner keeps all permission and Activity ownership
in the sample application; the SDK never starts hardware implicitly.
