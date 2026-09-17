plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        create("reactorDesktopPlatform") {
            id = "inc.reactor.desktop-platform"
            implementationClass = "inc.reactor.gradle.ReactorDesktopPlatformPlugin"
            displayName = "Reactor desktop native platform selection"
            description = "Adds the Reactor native runtime matching the desktop host platform"
        }
    }
}

tasks.test {
    useJUnit()
}
