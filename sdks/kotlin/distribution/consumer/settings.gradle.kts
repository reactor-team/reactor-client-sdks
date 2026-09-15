pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
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
