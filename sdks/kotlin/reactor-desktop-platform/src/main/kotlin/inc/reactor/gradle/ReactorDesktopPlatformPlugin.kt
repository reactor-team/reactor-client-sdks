package inc.reactor.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Configuration for the native runtime selected by the desktop plugin. */
abstract class ReactorDesktopPlatformExtension @Inject constructor(objects: ObjectFactory) {
    /** Override host detection when resolving dependencies for another target. */
    val platform: Property<String> = objects.property(String::class.java)
}

internal object ReactorDesktopPlatformResolver {
    private val supported = setOf("macos-arm64", "macos-x64", "linux-arm64", "linux-x64", "windows-x64")

    fun detect(osName: String, osArch: String): String {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        val family = when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") -> "linux"
            os.contains("windows") -> "windows"
            else -> error("Unsupported Reactor desktop operating system: $osName")
        }
        val architecture = when {
            arch in setOf("aarch64", "arm64") -> "arm64"
            arch in setOf("x86_64", "amd64", "x64") -> "x64"
            else -> error("Unsupported Reactor desktop architecture: $osArch")
        }
        val platform = "$family-$architecture"
        check(platform in supported) {
            "Unsupported Reactor desktop platform: $platform. Supported platforms: ${supported.sorted().joinToString()}."
        }
        return platform
    }
}

class ReactorDesktopPlatformPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "reactorDesktop",
            ReactorDesktopPlatformExtension::class.java,
        )

        project.afterEvaluate {
            val desktopDependency = project.configurations
                .flatMap { it.dependencies.toList() }
                .firstOrNull { it.group == "inc.reactor" && it.name == "reactor-desktop" }
                ?: return@afterEvaluate
            val platform = extension.platform.orNull?.also { requested ->
                check(requested in setOf("macos-arm64", "macos-x64", "linux-arm64", "linux-x64", "windows-x64")) {
                    "Unsupported reactorDesktop.platform '$requested'"
                }
            } ?: ReactorDesktopPlatformResolver.detect(
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
            )
            val version = desktopDependency.version
                ?: error("reactor-desktop must use a fixed version for native platform selection")
            project.dependencies.add(
                "runtimeOnly",
                "inc.reactor:reactor-native-$platform:$version",
            )
            project.logger.lifecycle("Reactor desktop native runtime: $platform")
        }
    }
}
