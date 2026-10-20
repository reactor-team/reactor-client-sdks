/*
 * What it takes to put an AAR on Maven Central, in one place.
 *
 * Applied by the two modules that publish — the SDK and its media helpers — and by nothing else.
 * Central's rules are refusals rather than warnings: an unsigned artifact, a POM without a
 * licence, an AAR without its -sources and -javadoc companions. Each is a release that fails
 * after the version has been tagged.
 *
 * Kept apart from reactor-android-conventions, which the examples and both live suites apply too:
 * a module that publishes nothing should never be asked about a signing key.
 */

plugins {
    id("reactor-android-conventions")
    `maven-publish`
    signing
}

/** The module's name and one sentence, for its POM. Set by each publishing module. */
interface ReactorPublication {
    val pomName: Property<String>
    val pomDescription: Property<String>
}

val reactorPublication = extensions.create<ReactorPublication>("reactorPublication")

android {
    // The variant Maven Central gets, with the two companions it refuses an artifact without.
    // AGP builds the -javadoc jar from the Kotlin sources itself; there is no Dokka to configure.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// ── Where a release is staged ────────────────────────────────────────────────
//
// Not uploaded straight to Central. The Portal takes one bundle for the whole release — every
// coordinate at once, validated together — so each module writes an ordinary Maven layout into a
// directory the root project zips, and the release workflow uploads that. A pull request can
// therefore stage the exact bundle with no credential at all, which turns "the publication is
// invalid" from a release-time failure into a pull-request one.
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("central-staging"))
        }
    }
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
            // After evaluation, for two things that do not exist while this script runs: AGP's
            // `release` component, created with the variants, and the module's own name and
            // description, set by its build script after it applies this plugin.
            afterEvaluate {
                from(components["release"])
                pom {
                    standardPom(reactorPublication.pomName.get(), reactorPublication.pomDescription.get())
                }
            }
        }
    }
}

// ── Signing ──────────────────────────────────────────────────────────────────

/**
 * The armoured private key and its passphrase, from ORG_GRADLE_PROJECT_signingKey and
 * ORG_GRADLE_PROJECT_signingPassword. In memory rather than from a keyring: a runner has no
 * gpg-agent, and a key written to disk is a key left behind.
 */
val signingKey: String? = providers.gradleProperty("signingKey").orNull
val signingPassword: String? = providers.gradleProperty("signingPassword").orNull

signing {
    // Absent locally and on a pull request, where staging is there to check the shape of the
    // publication rather than to produce something publishable. Central refuses an unsigned
    // bundle, so the release workflow checks every file is signed before it uploads — this must
    // not be the thing that decides, because a signing block that quietly does nothing publishes
    // an unsigned release the first time a secret is misspelled.
    isRequired = !signingKey.isNullOrBlank()
    if (isRequired) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
