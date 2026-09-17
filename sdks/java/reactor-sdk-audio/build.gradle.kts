plugins {
    id("reactor-java-conventions")
}

description =
    "Optional microphone and speaker helpers for the Reactor SDK, over " +
        "javax.sound.sampled. Separate from the core on purpose: nothing that " +
        "opens audio hardware belongs on the mandatory import path."

dependencies {
    api(project(":reactor-sdk"))
}
