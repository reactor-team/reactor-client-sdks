import inc.reactor.examples.ExampleConfig
import inc.reactor.examples.desktop.runScenario

fun main() { ExampleConfig.fromEnvironment().let { runScenario(1, it.model, it.token) } }
