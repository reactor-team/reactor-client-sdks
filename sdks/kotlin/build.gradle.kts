import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.dokka) apply false
}

allprojects {
    group = "inc.reactor"
    version = providers.gradleProperty("reactorVersion").get()
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.asProvider())
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnit()
        testLogging { events("failed", "skipped") }
    }
}

// Local rehearsal only. There is deliberately no remote repository or publishing credential.
subprojects {
    if (name != "native-probe") {
        apply(plugin = "maven-publish")
        apply(plugin = "org.jetbrains.dokka")

        // Keep the site docs available to consumers as a separate archive. The javadoc classifier
        // is reserved for Dokka output so Maven tooling and javadoc.io can render the API reference.
        val manualDocsJar = tasks.register<Jar>("manualDocsJar") {
            archiveClassifier.set("docs")
            from(rootProject.file("docs"))
            from(rootProject.file("README.md"))
            from(rootProject.file("distribution/index.html"))
        }
        val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
            archiveClassifier.set("javadoc")
            val dokkaTask = tasks.named("dokkaGeneratePublicationJavadoc")
            dependsOn(dokkaTask)
            from(dokkaTask)
        }
        val dokkaHtmlJar = tasks.register<Jar>("dokkaHtmlJar") {
            archiveClassifier.set("dokka")
            val dokkaTask = tasks.named("dokkaGeneratePublicationHtml")
            dependsOn(dokkaTask)
            from(dokkaTask)
        }
        }
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "staging"
                    url = uri(providers.gradleProperty("reactorStagingRepository").orElse(rootProject.layout.buildDirectory.dir("staging").map { it.asFile.absolutePath }).get())
                }
            }
        }
        val sdkProject = this
        gradle.projectsEvaluated {
            sdkProject.extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("sdk") {
                    from(sdkProject.components[if (sdkProject.plugins.hasPlugin("com.android.library")) "release" else "java"])
                    artifact(manualDocsJar)
                    artifact(dokkaJavadocJar)
                    artifact(dokkaHtmlJar)
                }
                publications.withType<MavenPublication>().configureEach {
                    pom {
                        name.set("Reactor Kotlin SDK — $artifactId")
                        description.set("Kotlin bindings for Reactor, for desktop JVM and Android")
                        url.set("https://github.com/reactor-team/reactor-client-sdks")
                        licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
                        developers { developer { id.set("reactor-team"); name.set("Reactor Team"); email.set("support@reactor.inc") } }
                        scm { url.set("https://github.com/reactor-team/reactor-client-sdks"); connection.set("scm:git:https://github.com/reactor-team/reactor-client-sdks.git") }
                    }
                }
            }
        }
        tasks.withType<PublishToMavenRepository>().configureEach {
            doFirst {
                check(repository.url.scheme == "file") { "Kotlin publishing is limited to local rehearsal until the live integration gate is wired" }
            }
        }
        tasks.withType<Jar>().configureEach {
            from(rootProject.file("../../LICENSE")) { into("META-INF") }
        }
    }
}
