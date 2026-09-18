plugins {
    `java-platform`
    id("reactor-maven-central")
}

description =
    "The SDK and every platform's native library, for builds that cannot read Gradle Module " +
        "Metadata. POM-only: it carries no code, only the dependencies Maven needs named for it."

// The shape JavaCPP publishes as opencv-platform. Not a fat jar — those are what `-uber` means,
// and not `-all`, which in this ecosystem means all *modules* of a library rather than all
// platforms (netty-all). Naming it wrong would be the kind of mistake people repeat for years.
//
// Gradle users never need this: the core's own variants resolve exactly one native artifact from
// the host's attributes. It is here because Maven ignores that metadata entirely, and the price of
// Maven having no variants is carrying all five.
dependencies {
    constraints {
        api(project(":reactor-sdk"))
    }
}

val platforms =
    listOf("linux-x86_64", "linux-aarch64", "macos-arm64", "macos-x86_64", "windows-x86_64")

publishing {
    publications {
        create<MavenPublication>("platform") {
            artifactId = "reactor-sdk-platform"
            from(components["javaPlatform"])
            pom {
                standardPom("Reactor SDK platform", project.description ?: "")
                withXml {
                    val dependencies = asNode().appendNode("dependencies")
                    dependencies.appendNode("dependency").apply {
                        appendNode("groupId", project.group)
                        appendNode("artifactId", "reactor-sdk")
                        appendNode("version", project.version)
                    }
                    platforms.forEach { token ->
                        dependencies.appendNode("dependency").apply {
                            appendNode("groupId", project.group)
                            appendNode("artifactId", "reactor-sdk-natives")
                            appendNode("version", project.version)
                            appendNode("classifier", token)
                            // Runtime, not compile: nothing is written against these, they are
                            // only loaded.
                            appendNode("scope", "runtime")
                        }
                    }
                }
            }
        }
    }
}
