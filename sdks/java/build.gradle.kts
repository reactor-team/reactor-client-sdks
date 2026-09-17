/*
 * The root project holds no code. It exists for one task: the bundle a release uploads.
 *
 * The Central Portal takes a whole release as a single zip — every coordinate validated together,
 * accepted or rejected as one — rather than one upload per artifact. Each publishing module writes
 * an ordinary Maven layout into build/central-staging, and this zips it.
 */

val centralStaging = layout.buildDirectory.dir("central-staging")

/** Every module that has something to publish, named once. */
val publishingModules = listOf(
    ":reactor-sdk",
    ":reactor-sdk-audio",
    ":reactor-sdk-jackson",
    ":reactor-sdk-natives",
    ":reactor-sdk-platform",
)

/**
 * Stages every publication into build/central-staging.
 *
 * Runs on a pull request too, without a signing key and without credentials. Whether a publication
 * is valid — a duplicated classifier, a POM missing its licence, a jar without its companions — is
 * a question this answers in the build that proposes the change, rather than in the release that
 * has already tagged.
 */
// Never incremental: a leftover from a previous version would be uploaded alongside this one, and
// the Portal validates the bundle as a whole. A task of its own rather than a doFirst on the
// aggregator below — a doFirst runs *after* everything the task depends on, so that version of
// this deleted exactly what the publish tasks had just written and staged an empty bundle.
val clearStaging by tasks.registering(Delete::class) {
    delete(centralStaging)
}

val stageForCentral by tasks.registering {
    group = "publishing"
    description = "Stage every published coordinate into build/central-staging"
    dependsOn(clearStaging)
    dependsOn(publishingModules.map { "$it:publishAllPublicationsToStagingRepository" })
    outputs.dir(centralStaging)
}

// Ordering, not a dependency: each publish task belongs to its own module and has no reason to
// know this exists, but none of them may run before the directory is cleared.
publishingModules.forEach { path ->
    project(path).tasks.matching { it.name.endsWith("PublicationToStagingRepository") }.configureEach {
        mustRunAfter(clearStaging)
    }
}

/** The single artefact the Portal accepts. */
val centralBundle by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Zip the staged release into the bundle the Central Portal accepts"
    dependsOn(stageForCentral)
    from(centralStaging)
    // Maven's own metadata is for a repository to maintain, not for a publisher to upload; the
    // Portal rejects a bundle carrying it.
    exclude("**/maven-metadata.xml*")
    archiveFileName = "reactor-sdk-${project.version}-bundle.zip"
    destinationDirectory = layout.buildDirectory.dir("central-bundle")
}
