plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

dependencies { testImplementation(libs.junit) }
