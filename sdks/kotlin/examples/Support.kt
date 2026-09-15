package inc.reactor.examples

data class ExampleConfig(val model: String, val token: String) {
    companion object {
        fun fromEnvironment(): ExampleConfig {
            val model = System.getenv("REACTOR_MODEL")?.takeIf { it.isNotBlank() }
                ?: error("Set REACTOR_MODEL to an owner-qualified production model")
            val token = System.getenv("REACTOR_TOKEN")?.takeIf { it.isNotBlank() }
                ?: error("Set REACTOR_TOKEN to a short-lived application token")
            return ExampleConfig(model, token)
        }
    }
}
