import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    id("com.diffplug.spotless")
}

repositories {
    mavenCentral()
}

/**
 * The JDK that compiles and runs this build. See gradle.properties: mise pins 25
 * locally, CI runs the suite on 22 as well.
 */
val reactorJdk: Int = (providers.gradleProperty("reactorJdk").orNull ?: "25").toInt()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(reactorJdk)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    // The published floor, whichever JDK is doing the compiling: 22 is where the
    // Foreign Function & Memory API stopped being a preview feature, and nothing
    // in this SDK may reach for an API newer than that.
    options.release = 22
    // -Werror on a binding whose mistakes are use-after-frees rather than
    // exceptions. A warning here is cheap; the thing it is warning about is not.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    // Annotations only, and absent at run time — consumers never see it on their
    // classpath. It is what lets Kotlin read this API's nullability as real types
    // instead of platform types, which is why it is here from the first commit
    // rather than added when the Kotlin facade needs it.
    // compileOnlyApi, not compileOnly. The annotations are absent at run time either way, but this
    // one also puts them on a consumer's compile classpath — and module-info declares
    // `requires static transitive org.jspecify`, which the module system has to resolve when a
    // consumer compiles a named module of their own. With compileOnly the dependency is published
    // nowhere, so that consumer got "module not found: org.jspecify" against a module they never
    // named. A consumer on the class path never noticed, which is why nobody did.
    compileOnlyApi("org.jspecify:jspecify:1.0.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Jar>().configureEach {
    manifest {
        // Implementation-Version is where ReactorSdk.version() reads from, and that
        // is what the coordinator records as client_info.sdk_version. A jar built
        // without it reports itself as a development build.
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Restricted methods — SymbolLookup.libraryLookup, MemorySegment.reinterpret,
    // Linker.nativeLinker — are what this SDK is built out of. Granting access to
    // the unnamed module covers the test classpath, where tests do not run as
    // modules.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (reactorJdk >= 24) {
        // Turns a missed grant from a warning into a failure. Only from 24, which
        // is where the option exists; on the 22 floor the warning is all there is.
        jvmArgs("--illegal-native-access=deny")
    }
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.98.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
