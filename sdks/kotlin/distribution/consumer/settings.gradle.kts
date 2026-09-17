pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
pluginManagement {
    val staged = providers.gradleProperty("stagedRepository")
    if (staged.isPresent) {
        repositories { maven { url = uri(staged.get()) } }
    }
    plugins {
        id("inc.reactor.desktop-platform") version providers.gradleProperty("sdkVersion").orElse("0.0.0-SNAPSHOT").get()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(providers.gradleProperty("stagedRepository").get())
            content { includeGroup("inc.reactor") }
        }
        google()
        mavenCentral { content { excludeGroup("inc.reactor") } }
    }
}
rootProject.name = "external-reactor-consumer"
include(":desktop")
if (providers.gradleProperty("includeAndroid").orNull == "true") include(":android")
