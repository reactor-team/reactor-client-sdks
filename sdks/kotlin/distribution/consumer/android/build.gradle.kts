plugins {
    id("com.android.application") version "8.9.2"
    kotlin("android") version "2.2.21"
}
kotlin { jvmToolchain(17) }
android {
    namespace = "inc.reactor.consumer"
    compileSdk = 35
    defaultConfig {
        applicationId = "inc.reactor.consumer"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    testBuildType = "release"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("inc.reactor:reactor-android-media:${providers.gradleProperty("sdkVersion").get()}")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}
