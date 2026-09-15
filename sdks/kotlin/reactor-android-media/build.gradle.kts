plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

kotlin { jvmToolchain(17) }

android {
    publishing { singleVariant("release") { withSourcesJar() } }
    namespace = "inc.reactor.sdk.android.media"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        // Version upgrades are reviewed together with the pinned toolchain.
        // New upstream releases must not turn an unchanged commit red.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency")
    }
}

dependencies {
    api(project(":reactor-android"))
    api(libs.lifecycle.common)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.lifecycle.runtime)
    androidTestImplementation(libs.androidx.test.core)
    debugImplementation(libs.lifecycle.runtime)
}
