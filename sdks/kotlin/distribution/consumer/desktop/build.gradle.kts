plugins {
    kotlin("jvm") version "2.2.21"
    application
    id("inc.reactor.desktop-platform")
}
kotlin { jvmToolchain(17) }
val sdkVersion = providers.gradleProperty("sdkVersion").get()
dependencies {
    implementation("inc.reactor:reactor-desktop:$sdkVersion")
}

// The plugin selects the host runtime. The property keeps cross-platform rehearsal deterministic.
providers.gradleProperty("nativePlatform").orNull?.let {
    reactorDesktop { platform.set(it) }
}
application { mainClass.set("MainKt"); applicationDefaultJvmArgs = listOf("-Xcheck:jni") }
