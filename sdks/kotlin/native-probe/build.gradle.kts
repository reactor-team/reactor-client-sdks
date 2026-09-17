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
        "../reactor-core/src/main/kotlin/inc/reactor/sdk/internal",
        "../reactor-core/src/test/kotlin/inc/reactor/sdk/internal",
    )
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("native/jniLibs"))
}

dependencies {
    implementation(files(layout.buildDirectory.file("native/libwebrtc.jar")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
