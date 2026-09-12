plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

dependencies { testImplementation(libs.junit) }

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
