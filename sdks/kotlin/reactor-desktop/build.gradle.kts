plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

dependencies {
    api(project(":reactor-jvm"))
    testImplementation(libs.junit)
}

val testHardware by tasks.registering(Test::class) {
    description = "Explicitly open the desktop microphone and speaker for a short local check"
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    filter { includeTestsMatching("inc.reactor.sdk.desktop.DesktopHardwareTest") }
    systemProperty("reactor.desktop.hardware", "true")
    outputs.upToDateWhen { false }
}
