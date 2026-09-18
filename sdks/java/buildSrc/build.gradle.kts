plugins {
    `kotlin-dsl`
}

dependencies {
    // Versions live here and in reactor-java-conventions.gradle.kts rather than in
    // a version catalog: a precompiled script plugin cannot use the catalog's
    // generated accessors, and reaching for it through VersionCatalogsExtension
    // costs more to read than the four literals it would replace.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.10.2")
}
