import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * What every Android module in this build shares.
 *
 * A precompiled script plugin cannot use the version catalog's generated `libs.` accessors, but
 * it can read the catalog itself — which is what the two lookups below do. Worth the extra line
 * each: the alternative is literals here *and* entries in gradle/libs.versions.toml, and a
 * version that lives in two files is a version that gets bumped in one.
 */

plugins {
    id("com.android.library")
    // No `org.jetbrains.kotlin.android` here: from AGP 9.0 Kotlin support is built into the
    // Android plugin, and applying the standalone one is a hard error. The Kotlin *version* is
    // still ours to pin — see gradle/libs.versions.toml, which buildSrc reads too.
    id("com.diffplug.spotless")
}

/**
 * A version pinned in sdks/android/sdk-packages.txt, by its `<kind>/` prefix.
 *
 * Read from that file rather than written here, because it is the file that
 * scripts/setup-android-sdk.sh installs from and verifies against. A second copy in the Gradle
 * build is a second thing to bump, and when the two disagree AGP quietly downloads its own
 * default alongside the pin — which is exactly the drift the packages file exists to prevent.
 * (It did: AGP pulled build-tools/36.0.0 in beside the pinned 36.1.0, and the setup script's
 * verification is what caught it.)
 */
fun pinnedVersion(kind: String): String =
    rootProject.file("sdk-packages.txt").readLines()
        .map { it.substringBefore('#').trim() }
        .firstOrNull { it.startsWith("$kind/") }
        ?.removePrefix("$kind/")
        ?: error("No $kind/<version> pinned in sdks/android/sdk-packages.txt")

android {
    // API 36 is the newest stable platform; sdk-packages.txt pins the matching
    // `platforms/android-36`, so a compileSdk bump means a line there too.
    compileSdk = 36

    // Told to AGP explicitly. Left alone it resolves its own default and downloads it, ignoring
    // the pin entirely.
    buildToolsVersion = pinnedVersion("build-tools")

    // Set for every module, though only the one with native sources uses it. A function declared
    // in a precompiled script plugin is not visible to the build scripts that apply it, so the
    // alternative is re-reading the packages file in each module — which is the duplication this
    // whole helper exists to remove.
    ndkVersion = pinnedVersion("ndk")

    defaultConfig {
        // API 26 is the floor. It is where java.time and the desugaring-free library surface this
        // binding wants become available, and it is comfortably below anything that can run a
        // real-time WebRTC session.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Keep every Kotlin artifact on the version the compiler is, rather than letting Gradle's
// "highest wins" resolution mix a 2.2 stdlib from AGP with the 2.4 one buildSrc's `kotlin-dsl`
// brings. See the note in gradle/libs.versions.toml for why the version is Gradle's and not
// AGP's.
val kotlinVersion = "2.4.0"
configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion",
        "org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion",
    )
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // -Werror on a binding whose mistakes are use-after-frees rather than exceptions. A
        // warning here is cheap; the thing it is warning about is not. Same reasoning as the
        // Java SDK's -Xlint:all -Werror.
        allWarningsAsErrors = true
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(libs.findLibrary("junit").get())
    androidTestImplementation(libs.findLibrary("junit").get())
    androidTestImplementation(libs.findLibrary("androidx-test-runner").get())
    androidTestImplementation(libs.findLibrary("androidx-test-junit").get())
}

tasks.withType<Test>().configureEach {
    // SdkPackagesTest reads the pinned component list. Handed over explicitly rather than reached
    // for with a relative path, which depends on a working directory Gradle does not promise.
    //
    // Declared as an input as well as a system property, and that is the load-bearing half: a
    // system property alone leaves the task up-to-date when only sdk-packages.txt changes, so
    // editing a pin re-runs nothing and the suite passes without having looked at the edit. It
    // did exactly that before this line existed.
    val sdkPackages = rootProject.file("sdk-packages.txt")
    inputs.file(sdkPackages)
        .withPropertyName("sdkPackages")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("reactor.sdkPackagesFile", sdkPackages.absolutePath)
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spotless {
    val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion)
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
}
