import java.util.concurrent.Callable

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

/**
 * The staged libwebrtc JAR, as a file collection that is empty when it has not been staged.
 *
 * A plain `files(...)` on a path that does not exist fails *configuration resolution*, not just
 * packaging — so declaring it directly made every configuration that inherits it unresolvable,
 * and `test:android` could not run without a Rust cross-compile having happened first. Unit tests
 * neither compile against these classes nor load them.
 *
 * Tolerating its absence here is safe precisely because `requireNativeArtifacts` gates the AAR:
 * a release can never quietly ship without the JAR, because packaging refuses outright. The
 * looseness is confined to the configurations that have no use for it.
 */
val webrtcJar: FileCollection =
    objects.fileCollection().from(
        // A Callable, not a provider: a provider that resolves to null has "no value available" and
        // Gradle refuses to query it, which fails the task graph rather than yielding an empty
        // classpath. A Callable returning an empty list is the shape that means "nothing here yet".
        Callable {
            val jar = nativeStage.file("libs/libwebrtc.jar").asFile
            if (jar.exists()) listOf(jar) else emptyList()
        },
    )

/**
 * `REACTOR_ABI_VERSION` as the canonical header declares it.
 *
 * Read out of the header rather than written here, and handed to the instrumented test as a
 * BuildConfig field. That is what lets the test assert the *packaged* library agrees with the
 * header this AAR was built against — a stale .so staged from an older checkout links, loads and
 * then reports a different number, which is precisely what NativeAbi.checkAbi() exists to catch
 * and precisely what a test asserting `> 0` would not.
 */
val headerAbiVersion: Int =
    rootProject
        .file("../../crates/reactor-ffi/include/reactor_ffi.h")
        .readLines()
        .firstNotNullOfOrNull { Regex("""^#define\s+REACTOR_ABI_VERSION\s+(\d+)""").find(it) }
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: error("No `#define REACTOR_ABI_VERSION` in crates/reactor-ffi/include/reactor_ffi.h")

android {
    namespace = "inc.reactor.sdk.android"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("int", "REACTOR_ABI_VERSION", "$headerAbiVersion")
    }

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
    // names at run time.
    //
    // `runtimeOnly`, not `api`, and the distinction is load-bearing rather than stylistic: no
    // Kotlin in this SDK references `inc.reactor.org.webrtc.*`, so the JAR has no business on the
    // compile classpath — and putting it there made compiling the *unit tests* require a Rust
    // cross-compile to have run first, which is what broke CI's Linux job. It still reaches the
    // consumer's runtime classpath and still rides into the AAR.
    runtimeOnly(webrtcJar)
}

/**
 * Refuse to *package* an AAR with nothing in it, rather than shipping one that fails at load.
 *
 * Packaging is permissive: an absent jniLibs directory and an absent `files()` dependency are
 * both silently empty, so without this the build succeeds and the failure surfaces as an
 * UnsatisfiedLinkError on a device.
 *
 * Hung off the AAR-producing tasks specifically, and not off `preBuild`. Every variant task
 * depends on preBuild — including the unit tests, which compile Kotlin and never touch a native
 * library — so gating there made `test:android` fail on any machine that had not run a Rust
 * cross-compile first. That is what it did to CI: the Linux job, whose whole purpose is lint and
 * unit tests, could not run them.
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

// Matched by name rather than by type, so this survives AGP renaming its task classes.
//
// Both families, because they need different halves and fail differently without them. The CMake
// build links libreactor_ffi.so, and without it ninja reports a bare linker error about a missing
// input — CMake's own FATAL_ERROR does not fire, because configuration already succeeded back
// when the file was there. Packaging needs the JAR too, which CMake knows nothing about. Running
// this first means the failure names the task to run rather than naming a path.
//
// Not `preBuild`: every variant task depends on that, including the unit tests, which compile
// Kotlin and never load a native library. Gating there made `test:android` impossible on a
// machine that had not run a Rust cross-compile — which is exactly what it did to CI's Linux job.
tasks
    .matching {
        (it.name.startsWith("bundle") && it.name.endsWith("Aar")) ||
            it.name.startsWith("buildCMake") ||
            it.name.startsWith("configureCMake")
    }.configureEach { dependsOn(requireNativeArtifacts) }
