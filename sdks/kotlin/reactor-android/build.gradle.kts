plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

kotlin { jvmToolchain(17) }

android {
    publishing { singleVariant("release") { withSourcesJar() } }
    namespace = "inc.reactor.sdk.android"
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
    api(project(":reactor-core"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

val nativeDirectory = providers.gradleProperty("reactorNativeDirectory")
android {
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    sourceSets["main"].assets.srcDir(nativeDirectory.map { "$it/android/assets" }.orElse("build/absent-assets"))
    sourceSets["main"].jniLibs.srcDir(nativeDirectory.map { "$it/android/jniLibs" }.orElse("build/absent-natives"))
}
if (nativeDirectory.isPresent) {
    dependencies { implementation(files(nativeDirectory.map { "$it/android/libwebrtc.jar" })) }
}
tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
    doFirst {
        val directory = nativeDirectory.orNull ?: error("Stage real Android natives with scripts/kotlin-distribution.py first")
        for (name in listOf("libreactor_ffi.so", "libreactor_jni.so")) {
            check(file("$directory/android/jniLibs/arm64-v8a/$name").isFile) { "Missing Android $name" }
        }
        check(file("$directory/android/libwebrtc.jar").isFile) { "Missing matching WebRTC Java classes" }
    }
}
