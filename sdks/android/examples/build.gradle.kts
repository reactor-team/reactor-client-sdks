/*
 * One sample app carrying the seven numbered scenarios.
 *
 * An application rather than a library, so it can be installed and run on a device — which is the
 * only way these prove anything. They are parity requirements rather than documentation: an
 * example missing from a binding is a code path that binding has never run.
 *
 * Configured directly instead of through reactor-android-conventions, which applies
 * com.android.library.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.diffplug.spotless")
}

fun pinnedVersion(kind: String): String =
    rootProject
        .file("sdk-packages.txt")
        .readLines()
        .map { it.substringBefore('#').trim() }
        .firstOrNull { it.startsWith("$kind/") }
        ?.removePrefix("$kind/")
        ?: error("No $kind/<version> pinned in sdks/android/sdk-packages.txt")

android {
    namespace = "inc.reactor.sdk.android.examples"
    compileSdk = 36
    buildToolsVersion = pinnedVersion("build-tools")

    defaultConfig {
        applicationId = "inc.reactor.sdk.android.examples"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The key never belongs in an APK. It is passed at install time —
        //   gradle --project-dir sdks/android :examples:installDebug -PreactorApiKey=rk_…
        // — and an app shipped to users would mint a short-lived token on its own backend
        // instead. The examples say so in their own header rather than modelling bad practice.
        //
        // No matching model override: each scenario names the model it needs, and they are not
        // interchangeable — 04 publishes into X2 because X2 edits a live track, and pointing it
        // at Helios would fail in a way that reads as a binding bug.
        buildConfigField(
            "String",
            "REACTOR_API_KEY",
            "\"${project.findProperty("reactorApiKey") ?: ""}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val kotlinVersion = "2.4.0"

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion",
        "org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion",
    )
}

dependencies {
    implementation(project(":reactor-sdk-android"))
    implementation(libs.findLibrary("coroutines-android").get())
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
