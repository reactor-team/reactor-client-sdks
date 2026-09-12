plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

dependencies {
    api(project(":reactor-core"))
    testImplementation(libs.junit)
}
