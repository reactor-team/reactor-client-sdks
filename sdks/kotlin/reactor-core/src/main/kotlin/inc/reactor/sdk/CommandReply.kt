package inc.reactor.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A correlated model reply. A null reply means acknowledgement without a message. */
data class CommandReply(
    val type: String?,
    val data: JsonElement?,
)

internal fun commandReply(payload: JsonElement?): CommandReply? {
    if (payload == null) return null
    val value = payload as? JsonObject ?: error("Command reply must be an object")
    val type = value["type"]
    require(type == null || type == JsonNull || (type is JsonPrimitive && type.isString)) { "Reply type must be a string or null" }
    return CommandReply((type as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content, value["data"])
}
