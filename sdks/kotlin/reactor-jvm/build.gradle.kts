plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

dependencies {
    api(project(":reactor-core"))
    testImplementation(libs.junit)
}

val nativePlatform = providers.gradleProperty("reactorNativePlatform")
val nativeDirectory = providers.gradleProperty("reactorNativeDirectory")
if (nativePlatform.isPresent) {
    val platform = nativePlatform.get()
    check(platform in listOf("macos-arm64", "macos-x64", "linux-arm64", "linux-x64", "windows-x64"))
    val nativeJar =
        tasks.register<Jar>("nativeJar") {
            archiveBaseName.set("reactor-native-$platform")
            from(nativeDirectory.map { "$it/$platform" }) { into("inc/reactor/natives/$platform") }
            doFirst {
                val directory = nativeDirectory.orNull ?: error("Missing reactorNativeDirectory")
                check(file("$directory/$platform/NOTICE").isFile) { "Stage native binaries and notices first" }
            }
        }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("native") {
            artifactId = "reactor-native-$platform"
            artifact(nativeJar)
            artifact(
                tasks.register<Jar>("nativeSourcesJar") {
                    archiveBaseName.set("reactor-native-$platform")
                    archiveClassifier.set("sources")
                    from(rootProject.file("native/src")) { into("src") }
                    from(rootProject.file("native/include")) { into("include") }
                },
            )
        }
    }
}
