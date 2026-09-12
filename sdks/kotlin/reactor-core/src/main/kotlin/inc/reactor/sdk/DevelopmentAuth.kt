package inc.reactor.sdk

import inc.reactor.sdk.internal.NativeClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Development/server use only. Android applications should get short-lived tokens from their backend. */
object DevelopmentAuth {
    suspend fun fetchJwt(
        apiKey: String,
        options: JsonObject,
        apiUrl: String = "https://api.reactor.inc",
        local: Boolean = false,
    ): String {
        val operations = Operations()
        try {
            return operations.registry.await({ payload ->
                val jwt = (payload as JsonObject)["jwt"] as JsonPrimitive
                require(jwt.isString && jwt.content.isNotBlank()) { "Missing JWT" }
                jwt.content
            }) { id ->
                NativeClient.authenticate(
                    apiUrl.encodeToByteArray(),
                    apiKey.encodeToByteArray(),
                    options.toString().encodeToByteArray(),
                    local,
                    operations.receiver(id),
                )
            }
        } finally {
            operations.close() // Detached native ticket lives until its callback, including cancellation.
        }
    }
}
