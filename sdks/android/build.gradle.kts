/*
 * The root project holds no code.
 *
 * It exists so that `gradle spotlessCheck` and `gradle test` at the root reach every module, and
 * so the Maven Central bundle task has somewhere to live once A15 adds it — the same shape
 * sdks/java's root project has.
 */

tasks.register("scaffoldCheck") {
    group = "verification"
    description = "Build and test every module — what `mise run test:android` calls"
    dependsOn(subprojects.map { "${it.path}:test" })
}
