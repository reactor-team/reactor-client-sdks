/*
 * The AAR: the binding, the object model, the per-ABI native libraries and the relocated WebRTC
 * classes. A02 carries the native pipeline and the load-time ABI guard; the object model itself
 * arrives with A03 onward.
 */

plugins {
    id("reactor-android-conventions")
}

/** Where scripts/build-android-ffi.sh stages libreactor_ffi.so and the libwebrtc JAR. */
val nativeStage: Directory = layout.buildDirectory.dir("reactor-native").get()

android {
    namespace = "inc.reactor.sdk.android"

    defaultConfig {
        // Native methods are resolved by name at load time, so R8 cannot see that they are used.
        // Shipped to consumers rather than applied here: it is their minifier that would remove
        // them. See consumer-rules.pro.
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // arm64-v8a alone, and that is a platform constraint rather than a choice:
            // reactor-webrtc publishes an android arm64 prebuilt and no other Android ABI.
            // x86_64 joins it when REA-6551 lands one — which is also what makes an emulator
            // possible on an x86_64 CI runner.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            // Pinned in sdk-packages.txt and installed by setup-android-sdk.sh.
            version = "4.1.2"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DREACTOR_FFI_STAGE=${nativeStage.dir("jniLibs").asFile.absolutePath}"
            }
        }
    }
}

// libreactor_ffi.so rides into the AAR from here; libreactor_jni.so is packaged by
// externalNativeBuild.
//
// Added through the variant API rather than `sourceSets["main"].jniLibs`: from AGP 9 that
// indexed accessor returns a decorated type the Kotlin DSL cannot cast, and this is the
// supported way to contribute a generated directory in any case.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            nativeStage.dir("jniLibs").asFile.absolutePath,
        )
    }
}

dependencies {
    // The Java half of WebRTC, whose classes the native library looks up by their relocated
    // names. `api`, not `implementation`: it has to be on the consumer's runtime classpath, and
    // a consumer never declares it because it is published nowhere — it comes out of
    // reactor-webrtc's Android archive by way of scripts/build-android-ffi.sh.
    api(files(nativeStage.file("libs/libwebrtc.jar")))
}

/**
 * Refuse to build an AAR with nothing in it, rather than shipping one that fails at load.
 *
 * Packaging is permissive: an absent jniLibs directory and an absent `files()` dependency are
 * both silently empty, so without this the build succeeds and the failure surfaces as an
 * UnsatisfiedLinkError on a device.
 */
val requireNativeArtifacts =
    tasks.register("requireNativeArtifacts") {
        val jniLibs = nativeStage.dir("jniLibs").asFile
        val jar = nativeStage.file("libs/libwebrtc.jar").asFile
        doFirst {
            val missing =
                buildList {
                    if (!jniLibs.isDirectory || jniLibs.listFiles().orEmpty().isEmpty()) add(jniLibs.path)
                    if (!jar.isFile) add(jar.path)
                }
            if (missing.isNotEmpty()) {
                error(
                    "The native artifacts are not staged:\n" +
                        missing.joinToString("\n") { "  missing: $it" } +
                        "\n\nRun: mise run build:android:native",
                )
            }
        }
    }

tasks.named("preBuild") { dependsOn(requireNativeArtifacts) }
