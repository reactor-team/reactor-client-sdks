plugins {
    // One Kotlin example, beside the eight Java ones, in the same module so it reuses the same
    // Display and the same environment helpers rather than growing copies of them.
    id("reactor-kotlin-conventions")
}

description = "The numbered example scenarios every Reactor SDK ships. Not published."

dependencies {
    implementation(project(":reactor-sdk"))
    implementation(project(":reactor-sdk-audio"))
    implementation(project(":reactor-sdk-kotlin"))
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

// The Kotlin example has its own task rather than a number in the glob above: there is one of it,
// and `example:java 01` already means the Java twin.
tasks.register<JavaExec>("exampleKotlin") {
    group = "application"
    description = "Run the Kotlin example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "inc.reactor.examples.KotlinConnectAndReceive"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    environment(System.getenv())
}
