plugins {
    id("reactor-java-conventions")
    id("reactor-maven-central")
}

description =
    "Reactor client SDK for desktop JVM applications, bound to libreactor_ffi " +
        "through the Foreign Function & Memory API."

// ── Zero-configuration platform resolution ───────────────────────────────────
//
// A consumer writes one dependency line. On Gradle, these variants are what makes
// that work: resolution matches the host's own operatingSystem and architecture
// attributes and pulls exactly one natives artifact, with no plugin applied and no
// classifier written.
//
// Maven ignores Gradle Module Metadata entirely, which is why reactor-sdk-platform
// exists beside this.
val nativePlatforms =
    listOf(
        Triple("linux-x86_64", OperatingSystemFamily.LINUX, MachineArchitecture.X86_64),
        Triple("linux-aarch64", OperatingSystemFamily.LINUX, MachineArchitecture.ARM64),
        Triple("macos-arm64", OperatingSystemFamily.MACOS, MachineArchitecture.ARM64),
        Triple("macos-x86_64", OperatingSystemFamily.MACOS, MachineArchitecture.X86_64),
        Triple("windows-x86_64", OperatingSystemFamily.WINDOWS, MachineArchitecture.X86_64),
    )

nativePlatforms.forEach { (token, os, arch) ->
    val variant = configurations.create("nativeRuntime-$token") {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named(os))
            attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named(arch))
        }
        dependencies.add(
            project.dependencies.create("${project.group}:reactor-sdk-natives:${project.version}:$token"),
        )
    }
    // Named after the platform rather than numbered: a resolution failure names the variant, and
    // "no matching variant nativeRuntime-linux-aarch64" is a sentence someone can act on.
    components["java"].let { it as AdhocComponentWithVariants }.addVariantsFromConfiguration(variant) {}
}

publishing {
    publications {
        create<MavenPublication>("sdk") {
            // Carries the jar, both companions and every native variant. Not `artifact(javadocJar)`
            // beside it: the conventions' withJavadocJar() already puts that jar in this component,
            // and naming it twice makes the publication invalid — "multiple artifacts with the
            // identical extension and classifier" — at publish time rather than at build time.
            from(components["java"])
            pom { standardPom("Reactor SDK", project.description ?: "") }
        }
    }
}
