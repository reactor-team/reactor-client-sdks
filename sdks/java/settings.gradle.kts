// The Java SDK's Gradle build.
//
// Deliberately without a Gradle wrapper. mise.toml's [tools] table is the only
// place this repository pins a toolchain, checksummed per platform in mise.lock;
// a wrapper would be a second source of truth for the Gradle version, unlocked
// and unchecked. `mise run build:java` and CI both call the pinned `gradle`.
rootProject.name = "reactor-java"

include(
    "reactor-sdk",
    "reactor-sdk-audio",
    "reactor-sdk-jackson",
    "examples",
    "integration-tests",
    "endurance-tests",
)
