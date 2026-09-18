plugins {
    id("reactor-maven-central")
    // The conventions, even though this module holds no Java: they set the same release target
    // every other module has — without which resolution refuses it as "compatible with 25 or
    // newer" — and produce the empty -sources and -javadoc jars Central requires of every
    // coordinate, including one whose content is a shared library.
    id("reactor-java-conventions")
}

description =
    "Every platform's native library. The main jar carries all five, which is what makes one " +
        "dependency line enough; the classified artifacts beside it are for a build that wants " +
        "exactly one."

tasks.named<Javadoc>("javadoc") {
    // Documented as a module, not as a set of types.
    //
    // The only source here is a module descriptor and there are no public classes, which javadoc
    // on JDK 22 calls a fatal error — "No public or protected classes found to document" — while 25
    // renders the module summary happily. So adding module-info.java broke one leg of the matrix
    // and only in CI, since every local build ran on 25.
    //
    // Naming the module makes both agree, and both produce the page: what this artifact is, and why
    // it is a module at all. Disabling the task instead would have been consistent and empty, which
    // is a worse answer for the one file here that has something to say.
    val moduleName = "inc.reactor.sdk.natives"
    options {
        this as StandardJavadocDocletOptions
        addStringOption("-module-source-path", "$moduleName=" + file("src/main/java").absolutePath)
        addStringOption("-module", moduleName)
    }
}

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

// ── The jar that makes one dependency line enough ────────────────────────────
//
// All five libraries, in one artifact, roughly 50 MB of it. That is the price of the thing this
// SDK promises: a consumer writes one line, configures nothing, and the library for whatever
// machine the code ends up on is already there.
//
// It replaced a design that did not work. reactor-sdk used to publish five extra variants keyed on
// operating system and architecture, on the theory that Gradle would match the host's. A plain JVM
// project requests neither attribute, so Gradle selected runtimeElements and no native artifact
// reached the classpath at all — the client opened and then failed at load. Setting the attributes
// in the consumer made it worse rather than better: the extra variants compete with
// runtimeElements rather than adding to it, so resolution became ambiguous. addVariantsFromConfiguration
// on the java component is the wrong mechanism for "a library plus a per-platform companion", and
// there is no producer-side fix for it — the consumer would have to apply a plugin.
//
// An ordinary runtime dependency needs no metadata anyone has to opt into, which is also why
// reactor-sdk-platform is gone: it existed only because Maven ignores Gradle Module Metadata.
tasks.named<Jar>("jar") {
    from(stagingDirectory) {
        // The resource directory is not a valid package name, on purpose: JPMS encapsulates
        // resources that live in packages, and these have to be readable from the core module
        // whether a consumer is on the classpath or the module path.
        into("reactor-native")
        // Only the tokens this module publishes for. A stray directory under natives/ — a
        // half-finished cross-compile, an editor's backup — must not become a resource the
        // loader would then find and try to load.
        include(platforms.keys.map { "$it/**" })
    }
}

// Beside it, one jar per platform, for a build that wants exactly one: a container image that
// knows what it runs on can exclude the module above and name its own.
//
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
                from(stagingDirectory.dir(token)) { into("reactor-native/$token") }
                // The module descriptor too. A consumer on the module path resolves modules, not
                // artifacts, so whichever of these jars they end up with has to be the same module
                // — otherwise `requires inc.reactor.sdk.natives` in the core holds for the default
                // dependency and fails for the slim path.
                from(sourceSets["main"].output)
            }
        }

tasks.named("assemble") { dependsOn(nativeJars) }

publishing {
    publications {
        create<MavenPublication>("natives") {
            artifactId = "reactor-sdk-natives"
            // The main jar — every platform's library — and both companions, then one classified
            // jar per platform. Central refuses a coordinate that has only classifiers: the
            // classifier-less jar is what a POM resolves, and -sources/-javadoc are required of
            // every jar coordinate, including one whose content is five shared libraries. Those
            // two are empty, which is the honest answer for a module with no Java in it.
            from(components["java"])
            nativeJars.forEach { jar -> artifact(jar) }
            // standardPom, not a name and a description: the licence, developer and SCM blocks are
            // what Central refuses a release without, and this publication used to carry neither.
            pom { standardPom("Reactor SDK natives", project.description ?: "") }
        }
    }
}
