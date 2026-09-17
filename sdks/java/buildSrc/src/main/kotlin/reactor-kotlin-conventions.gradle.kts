/*
 * What every Kotlin module in this build compiles the same way.
 *
 * Two modules use it: the published facade, and the one Kotlin example. Kept apart from
 * reactor-java-conventions because the Kotlin plugin has to be applied for any of this to resolve,
 * and most modules here have no Kotlin in them at all.
 */

plugins {
    id("reactor-java-conventions")
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        // The same floor the Java modules compile to, for the same reason: a consumer on 22 has to
        // be able to read these class files. Not optional either — the Kotlin plugin refuses to
        // compile at all when its target and javac's disagree, which is how this was noticed.
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22
        // A warning in a facade is usually a shape that did not survive translation: an unchecked
        // cast across the Java boundary, a deprecation this module would be passing on.
        allWarningsAsErrors = true
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktfmt("0.56").kotlinlangStyle()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
