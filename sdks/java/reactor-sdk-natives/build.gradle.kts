plugins {
    id("reactor-maven-central")
    // The conventions, even though this module holds no Java: they set the same release target
    // every other module has — without which resolution refuses it as "compatible with 25 or
    // newer" — and produce the empty -sources and -javadoc jars Central requires of every
    // coordinate, including one whose content is a shared library.
    id("reactor-java-conventions")
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

// Only the platforms this machine actually has a library for, decided while the build is being
// configured rather than skipped while it runs. A task that is registered and then skipped still
// leaves the publication naming a file that was never written, which fails the publish with
// "artifact file does not exist" — so a developer, and every pull request, could not stage the
// bundle at all without cross-compiling all five first. Publishing fewer than five is not a risk
// this has to carry: release-java.yml refuses a release whose staging directory is missing any of
// them, before anything is uploaded.
val nativeJars =
    platforms
        .filter { (token, libraryFile) -> stagingDirectory.dir(token).asFile.resolve(libraryFile).isFile }
        .map { (token, _) ->
            tasks.register<Jar>("nativesJar-$token") {
                archiveBaseName = "reactor-sdk-natives"
                archiveClassifier = token
                // The resource directory is not a valid package name, on purpose: JPMS encapsulates
                // resources that live in packages, and this one has to be readable from the core
                // module whether a consumer is on the classpath or the module path.
                from(stagingDirectory.dir(token)) { into("reactor-native/$token") }
            }
        }

tasks.named("assemble") { dependsOn(nativeJars) }

publishing {
    publications {
        create<MavenPublication>("natives") {
            artifactId = "reactor-sdk-natives"
            // The main jar and both companions, then one classified jar per platform. Central
            // refuses a coordinate that has only classifiers: the classifier-less jar is what a
            // POM resolves, and -sources/-javadoc are required of every jar coordinate, including
            // one whose only content is a shared library. All three are empty here, which is the
            // honest answer for a module with no Java in it.
            from(components["java"])
            nativeJars.forEach { jar -> artifact(jar) }
            // standardPom, not a name and a description: the licence, developer and SCM blocks are
            // what Central refuses a release without, and this publication used to carry neither.
            pom { standardPom("Reactor SDK natives", project.description ?: "") }
        }
    }
}
