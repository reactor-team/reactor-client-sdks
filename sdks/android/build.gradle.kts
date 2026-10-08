/*
 * The root project holds no code.
 *
 * It exists so that `gradle spotlessCheck` and `gradle test` at the root reach every module, and
 * so the Maven Central bundle task has somewhere to live once A15 adds it — the same shape
 * sdks/java's root project has.
 */

// No scaffoldCheck task. It used to be registered here, described as "what `mise run
// test:android` calls" — and nothing called it: that task runs `gradle test`, which already
// aggregates the subprojects from this root. An alias nobody invokes is a second name for the
// build that will drift from the first one, and its description was already wrong.
