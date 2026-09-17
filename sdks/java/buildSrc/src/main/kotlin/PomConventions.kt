import org.gradle.api.publish.maven.MavenPom

/**
 * The metadata Maven Central refuses a release without: a name, a description, a project URL, a
 * licence, a developer and an SCM block. Written once here rather than in each module, because
 * five copies of it would drift and the failure only shows up at publish time.
 */
fun MavenPom.standardPom(pomName: String, pomDescription: String) {
    name.set(pomName)
    description.set(pomDescription)
    url.set("https://github.com/reactor-team/reactor-client-sdks")
    licenses {
        license {
            name.set("Apache License 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        }
    }
    developers {
        developer {
            name.set("Reactor")
            url.set("https://www.reactor.inc")
        }
    }
    scm {
        url.set("https://github.com/reactor-team/reactor-client-sdks")
        connection.set("scm:git:https://github.com/reactor-team/reactor-client-sdks.git")
        developerConnection.set("scm:git:git@github.com:reactor-team/reactor-client-sdks.git")
    }
}
