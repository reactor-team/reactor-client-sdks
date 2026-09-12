plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

kotlin { jvmToolchain(17) }

android {
    namespace = "inc.reactor.sdk.probe"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["androidTest"].java.srcDirs(
        "../reactor-core/src/main/kotlin",
        "../reactor-core/build/generated/sdkVersion",
        "../reactor-core/src/test/kotlin",
    )
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("native/jniLibs"))
}

dependencies {
    implementation(files(layout.buildDirectory.file("native/libwebrtc.jar")))
    androidTestImplementation(libs.coroutines)
    androidTestImplementation(libs.serialization.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

tasks.matching { it.name.contains("AndroidTest") || it.name.contains("Ktlint") }.configureEach {
    dependsOn(":reactor-core:generateSdkVersion")
}
