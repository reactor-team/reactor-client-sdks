plugins {
    id("reactor-java-conventions")
}

description =
    "The live suite: the real client against a real model. Not published, and " +
        "not part of `check` — it needs a session, so it cannot gate a unit-test run."

dependencies {
    testImplementation(project(":reactor-sdk"))
}

// Excluded from `check` deliberately. `mise run test:java` must stay runnable
// without credentials and without spending a live session; the live suite has
// its own task and its own CI job.
tasks.named("test") {
    enabled = false
}
