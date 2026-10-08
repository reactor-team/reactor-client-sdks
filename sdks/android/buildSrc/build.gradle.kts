plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    // Pinned to the same version AGP declares, so the conventions plugin compiles against the
    // Kotlin DSL types AGP will actually apply. See the note in gradle/libs.versions.toml.
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
}
