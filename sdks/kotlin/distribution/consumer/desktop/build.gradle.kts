plugins {
    kotlin("jvm") version "2.2.21"
    application
}
kotlin { jvmToolchain(17) }
val sdkVersion = providers.gradleProperty("sdkVersion").get()
dependencies {
    implementation("inc.reactor:reactor-desktop:$sdkVersion")
    if (providers.gradleProperty("omitNative").orNull != "true") {
        runtimeOnly("inc.reactor:reactor-native-${providers.gradleProperty("nativePlatform").get()}:$sdkVersion")
    }
}
application { mainClass.set("MainKt"); applicationDefaultJvmArgs = listOf("-Xcheck:jni") }
