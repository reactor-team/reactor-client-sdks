import inc.reactor.examples.ExampleConfig
import inc.reactor.examples.desktop.runScenario
fun main() { ExampleConfig.fromEnvironment().let { runScenario(5, it.model, it.token) } }
