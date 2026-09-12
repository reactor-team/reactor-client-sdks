plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

dependencies {
    api(libs.coroutines)
    api(libs.serialization.json)
    testImplementation(libs.junit)
}

val buildJniTests by tasks.registering(Exec::class) {
    dependsOn(tasks.compileTestKotlin)
    inputs.dir(layout.buildDirectory.dir("classes/kotlin"))
    inputs.file(rootProject.file("../../scripts/kotlin-jni-headers.py"))
    inputs.file(rootProject.file("../../scripts/kotlin-jni-test-build.sh"))
    commandLine("bash", rootProject.file("../../scripts/kotlin-jni-test-build.sh"))
    inputs.dir(rootProject.file("native"))
    inputs.file(rootProject.file("../../crates/reactor-ffi/include/reactor_ffi.h"))
    outputs.dir(layout.buildDirectory.dir("jni-test"))
}

tasks.test {
    dependsOn(buildJniTests)
    jvmArgs("-Xcheck:jni")
    systemProperty(
        "reactor.jni.test.library",
        layout.buildDirectory
            .file("jni-test/${System.mapLibraryName("reactor_jni_test")}")
            .get()
            .asFile.absolutePath,
    )
}

val generateSdkVersion by tasks.registering {
    val sdkVersion = project.version.toString()
    val output = layout.buildDirectory.dir("generated/sdkVersion")
    inputs.property("sdkVersion", sdkVersion)
    outputs.dir(output)
    doLast {
        val file = output.get().file("inc/reactor/sdk/internal/SdkVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package inc.reactor.sdk.internal\n\ninternal const val SDK_VERSION = \"$sdkVersion\"\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateSdkVersion) }

val testRealNative by tasks.registering(Test::class) {
    description = "Smoke-test the real JNI/FFI libraries in an isolated JVM"
    dependsOn(tasks.testClasses)
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    filter {
        includeTestsMatching("inc.reactor.sdk.internal.RealNativeLifecycleTest")
        includeTestsMatching("inc.reactor.sdk.internal.RealNativeDownloadTest")
    }
    jvmArgs("-Xcheck:jni")
    systemProperty("reactor.jni.real.directory", rootProject.file("../../target/kotlin-jni/host").absolutePath)
}

val buildJniSanitizer by tasks.registering(Exec::class) {
    dependsOn(tasks.compileTestKotlin)
    environment("JNI_TEST_OUTPUT", "jni-asan")
    environment("JNI_ASAN", "1")
    // mise also provides conda clang; use the host GCC and its matching ASan runtime.
    environment("CXX", "/usr/bin/g++")
    commandLine("bash", rootProject.file("../../scripts/kotlin-jni-test-build.sh"))
}
val testJniSanitizer by tasks.registering(Test::class) {
    description = "Run JNI lifetime tests under AddressSanitizer on Linux"
    dependsOn(buildJniSanitizer)
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    filter {
        includeTestsMatching("inc.reactor.sdk.internal.NativeBoundaryTest")
        includeTestsMatching("inc.reactor.sdk.internal.LifecycleTest")
        includeTestsMatching("inc.reactor.sdk.internal.MediaReceiveTest")
        includeTestsMatching("inc.reactor.sdk.internal.MediaSendTest")
        includeTestsMatching("inc.reactor.sdk.internal.CommandTest")
        includeTestsMatching("inc.reactor.sdk.internal.UploadTest")
        includeTestsMatching("inc.reactor.sdk.internal.RecordingTest")
    }
    jvmArgs("-Xcheck:jni")
    systemProperty(
        "reactor.jni.test.library",
        layout.buildDirectory
            .file("jni-asan/libreactor_jni_test.so")
            .get()
            .asFile.absolutePath,
    )
    doFirst {
        check(System.getProperty("os.name") == "Linux") { "The JVM sanitizer task requires Linux" }
        val compiler = "/usr/bin/g++"

        fun runtimeLibrary(name: String): String {
            val process = ProcessBuilder(compiler, "-print-file-name=$name").start()
            val path =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            check(process.waitFor() == 0 && file(path).isFile) { "Cannot locate GCC runtime $name" }
            return path
        }
        val asan = runtimeLibrary("libasan.so")
        val cpp = runtimeLibrary("libstdc++.so")
        // Java loads C++ through dlopen later; ASan must resolve __cxa_throw at startup.
        environment("LD_PRELOAD", "$asan:$cpp")
        // The JVM and deliberate destroy=-1 orphans are outside leak detection's contract.
        environment("ASAN_OPTIONS", "detect_leaks=0:abort_on_error=1")
    }
}
