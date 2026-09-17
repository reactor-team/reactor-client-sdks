/*
 * What it takes to put a coordinate on Maven Central, in one place.
 *
 * Applied by every module that publishes, and by no module that does not. Central's rules are
 * refusals rather than warnings — an unsigned artifact, a POM without a licence, a jar without its
 * -sources companion — and each one is a release that fails after the tag exists.
 *
 * Deliberately not folded into reactor-java-conventions: that one is applied by the examples and
 * both live suites too, and a signing key is not something a module that publishes nothing should
 * ever be asked about.
 */

plugins {
    `maven-publish`
    signing
}

// ── Where a release is staged ────────────────────────────────────────────────
//
// Not uploaded straight to Central. The Portal takes one bundle for the whole release — every
// coordinate at once, validated together — so the build writes an ordinary Maven layout into a
// directory the root project zips, and the workflow uploads that. The useful side effect is that
// a pull request can stage the exact bundle without any credential, which is what turns "the
// publication is invalid" from a release-time failure into a pull-request one.
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("central-staging"))
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
    // Absent locally and on a pull request, where the point of staging is to validate the shape of
    // the publication rather than to produce something publishable. Central refuses an unsigned
    // bundle, so the release workflow checks the key is there before it uploads — this must not be
    // the thing that decides, because a signing block that quietly does nothing publishes an
    // unsigned release the first time a secret is misspelled.
    isRequired = signingKey != null && signingKey.isNotBlank()
    if (isRequired) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
