plugins {
    id("reactor-java-conventions")
    id("reactor-maven-central")
}

description =
    "Optional microphone and speaker helpers for the Reactor SDK, over " +
        "javax.sound.sampled. Separate from the core on purpose: nothing that " +
        "opens audio hardware belongs on the mandatory import path."

dependencies {
    api(project(":reactor-sdk"))
}

publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
            pom { standardPom("Reactor SDK audio devices", project.description ?: "") }
        }
    }
}
