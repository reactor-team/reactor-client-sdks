plugins {
    id("reactor-java-conventions")
}

description = "The numbered example scenarios every Reactor SDK ships. Not published."

dependencies {
    implementation(project(":reactor-sdk"))
}
