pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "reactor-kotlin"
include(":reactor-core", ":reactor-jvm", ":reactor-android")

// Diagnostic APK only; never published as part of the SDK.
include(":native-probe")
