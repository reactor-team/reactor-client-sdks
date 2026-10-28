/*
 * The root project holds no code.
 *
 * It exists so that `gradle spotlessCheck` and `gradle test` at the root reach every module, and
 * for the one task a release needs: the bundle it uploads. The Central Portal takes a whole
 * release as a single zip — every coordinate validated together, accepted or rejected as one —
 * rather than one upload per artifact. Each publishing module writes an ordinary Maven layout into
 * build/central-staging, and this zips it. The same shape sdks/java's root project has.
 */

val centralStaging = layout.buildDirectory.dir("central-staging")

/** Every module that publishes, named once. */
val publishingModules =
    listOf(
        ":reactor-sdk-android",
        ":reactor-sdk-android-media",
    )

// Never incremental: a leftover from a previous version would be uploaded alongside this one, and
// the Portal validates the bundle as a whole. A task of its own rather than a doFirst on the
// aggregator below — a doFirst runs *after* everything the task depends on, and would delete
// exactly what the publish tasks had just written.
val clearStaging =
    tasks.register<Delete>("clearStaging") {
        delete(centralStaging)
    }

/**
 * Stages every publication into build/central-staging.
 *
 * Runs on a pull request too, without a signing key and without credentials, so a publication
 * Central would refuse — a POM missing its licence, an AAR without its companions — fails the
 * change that introduced it rather than the release that has already tagged.
 */
val stageForCentral =
    tasks.register("stageForCentral") {
        group = "publishing"
        description = "Stage every published coordinate into build/central-staging"
        dependsOn(clearStaging)
        dependsOn(publishingModules.map { "$it:publishAllPublicationsToStagingRepository" })
        outputs.dir(centralStaging)
    }

// Ordering, not a dependency: each publish task belongs to its own module, but none of them may
// run before the directory is cleared.
publishingModules.forEach { path ->
    project(path).tasks.matching { it.name.endsWith("PublicationToStagingRepository") }.configureEach {
        mustRunAfter(clearStaging)
    }
}

/** The single artifact the Portal accepts. */
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zip the staged release into the bundle the Central Portal accepts"
    dependsOn(stageForCentral)
    from(centralStaging)
    // Maven's own metadata is for a repository to maintain, not for a publisher to upload; the
    // Portal rejects a bundle carrying it.
    exclude("**/maven-metadata.xml*")
    archiveFileName = "reactor-sdk-android-${project.version}-bundle.zip"
    destinationDirectory = layout.buildDirectory.dir("central-bundle")
}

// No scaffoldCheck task. It used to be registered here, described as "what `mise run
// test:android` calls" — and nothing called it: that task runs `gradle test`, which already
// aggregates the subprojects from this root. An alias nobody invokes is a second name for the
// build that will drift from the first one, and its description was already wrong.
