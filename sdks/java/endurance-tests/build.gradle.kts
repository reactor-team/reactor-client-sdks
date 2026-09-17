plugins {
    id("reactor-java-conventions")
}

description =
    "Long-running leak and resource-trend scenarios. Not published, manual-only: " +
        "these read a trend over minutes to hours rather than a right-or-wrong answer."

dependencies {
    testImplementation(project(":reactor-sdk"))
}

// Same reasoning as integration-tests, plus duration: nothing here belongs in a
// task a contributor runs to check their change compiles.
tasks.named("test") {
    enabled = false
}
