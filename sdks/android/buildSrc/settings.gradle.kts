rootProject.name = "buildSrc"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // The main build's catalog, read here too. Without this, the AGP and Kotlin versions on the
    // plugin classpath would be literals in buildSrc/build.gradle.kts and the ones the modules
    // resolve would come from the catalog — two places to bump, one of which someone forgets.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
