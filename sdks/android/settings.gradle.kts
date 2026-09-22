// The Android SDK's Gradle build.
//
// Deliberately without a Gradle wrapper, exactly as sdks/java is: mise.toml's [tools] table is
// the only place this repository pins a toolchain, checksummed per platform in mise.lock. A
// wrapper would be a second source of truth for the Gradle version, unlocked and unchecked.
// `mise run build:android` and CI both call the pinned `gradle`.
//
// There is one exception to "[tools] is the only place a version lives", and it is worth stating
// here because it is the kind of thing a reader will otherwise assume is an oversight. mise pins
// the Android *command-line tools* — see the root mise.toml — but the plugin that provides them
// installs only cmdline-tools. The SDK components those tools then fetch (the platform, the
// build-tools and the NDK) are installed by `sdkmanager`, which mise has no view of and cannot
// lock. Those three versions live in sdk-packages.txt, installed and verified by
// scripts/setup-android-sdk.sh. One file, one script, verified — the same property mise.lock
// gives, expressed where mise cannot reach.
//
// AGP, Kotlin, Spotless and the AndroidX libraries are *not* toolchains. They are ordinary Maven
// artifacts resolved from google() and mavenCentral(), and they are pinned in
// gradle/libs.versions.toml.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "reactor-android"

include(
    "reactor-sdk-android",
    "reactor-sdk-android-media",
    "examples",
    "integration-tests",
    "endurance-tests",
)
