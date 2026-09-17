plugins {
    `kotlin-dsl`
}

dependencies {
    // Versions live here and in reactor-java-conventions.gradle.kts rather than in
    // a version catalog: a precompiled script plugin cannot use the catalog's
    // generated accessors, and reaching for it through VersionCatalogsExtension
    // costs more to read than the four literals it would replace.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.10.2")
    // Same version the Android Kotlin SDK pins, so the two do not drift into needing different
    // compilers in one repository.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    // Dumps the Kotlin module's public ABI to a checked-in file and fails when it changes without
    // the file changing with it. On a facade, an accidental widening is the whole risk.
    implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1")
    // KDoc, rendered. Central requires a -javadoc jar of every coordinate, and an empty one on the
    // module a Kotlin user reads most would be the wrong thing to give them. 2.1.0 at the earliest:
    // see the note in gradle.properties about 2.0.0 and JDK 25.
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.1.0")
}
