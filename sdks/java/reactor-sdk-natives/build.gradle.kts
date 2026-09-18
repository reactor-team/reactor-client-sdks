plugins {
    `java-library`
    `maven-publish`
}

description =
    "The native library, one artifact per platform. A consumer never names one of these: the " +
        "core resolves the right one, and this module exists so that resolution has something to " +
        "resolve."

// Where the built libraries are staged, one directory per platform token. CI fills all five from
// its build matrix; a local build usually has only the host's, and packages only that.
val stagingDirectory = layout.projectDirectory.dir("natives")

/**
 * The platforms this SDK publishes for, and the file each one carries. Matches
 * NativePlatform in the core — that enum is what reads these back out at run time.
 */
val platforms =
    mapOf(
        "linux-x86_64" to "libreactor_ffi.so",
        "linux-aarch64" to "libreactor_ffi.so",
        "macos-arm64" to "libreactor_ffi.dylib",
        "macos-x86_64" to "libreactor_ffi.dylib",
        "windows-x86_64" to "reactor_ffi.dll",
    )

val nativeJars =
    platforms.map { (token, libraryFile) ->
        tasks.register<Jar>("nativesJar-$token") {
            archiveBaseName = "reactor-sdk-natives"
            archiveClassifier = token
            // The resource directory is not a valid package name, on purpose: JPMS encapsulates
            // resources that live in packages, and this one has to be readable from the core
            // module whether a consumer is on the classpath or the module path.
            from(stagingDirectory.dir(token)) { into("reactor-native/$token") }
            // Nothing to package for a platform this machine did not build. Absent is better than
            // an empty jar that resolves and then fails at load.
            onlyIf { stagingDirectory.dir(token).asFile.resolve(libraryFile).isFile }
        }
    }

tasks.named("assemble") { dependsOn(nativeJars) }

publishing {
    publications {
        create<MavenPublication>("natives") {
            artifactId = "reactor-sdk-natives"
            nativeJars.forEach { jar -> artifact(jar) }
            pom {
                name = "Reactor SDK natives"
                description = project.description
            }
        }
    }
}
