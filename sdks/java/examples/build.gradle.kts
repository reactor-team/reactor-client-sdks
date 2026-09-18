plugins {
    id("reactor-java-conventions")
}

description = "The numbered example scenarios every Reactor SDK ships. Not published."

dependencies {
    implementation(project(":reactor-sdk"))
    implementation(project(":reactor-sdk-audio"))
}

// Runs one example by its number: `mise run example:java 01`. Not the application plugin,
// because there are eight mains and picking one by property is less ceremony than eight
// application blocks.
tasks.register<JavaExec>("example") {
    group = "application"
    description = "Run one numbered example, e.g. -Pexample=01"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = providers.gradleProperty("example").map { number ->
        val matches = sourceSets["main"].allJava.matching { include("**/Example$number*.java") }
        val file = matches.singleOrNull()
            ?: error("no single example matches $number; found ${matches.files.map { it.name }}")
        "inc.reactor.examples." + file.name.removeSuffix(".java")
    }
    // Restricted methods again: an example is an ordinary consumer of this SDK and has to grant
    // native access exactly as one would.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Passed through so an example can be pointed at another model, or told to open a window.
    environment(System.getenv())
}
