The Android examples are launched by `ExampleActivity` in the instrumentation
sample. Select the scenario directory and pass `reactor.scenario`,
`reactor.model`, and `reactor.token` as Intent extras. The emulator runner maps
`REACTOR_MODEL` and `REACTOR_TOKEN` into those extras; no model or credential is
hard-coded. `FrameView` renders the newest BGRA video frame in the app and the
Activity owns the lifecycle scope and teardown.
