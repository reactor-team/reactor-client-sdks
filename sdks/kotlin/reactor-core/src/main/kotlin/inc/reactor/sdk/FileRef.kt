package inc.reactor.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Uploaded once; pass this reference to a command instead of embedding file bytes. */
data class FileRef(
    val uploadId: String,
    val name: String,
    val mimeType: String,
    val size: ULong,
) {
    init {
        require(uploadId.isNotBlank() && name.isNotBlank() && mimeType.isNotBlank()) { "Upload reference fields must be nonempty" }
    }

    /** Also usable inside nested JSON arrays/objects when the model expects file references there. */
    fun toJson(): JsonObject =
        JsonObject(
            mapOf(
                "upload_id" to JsonPrimitive(uploadId),
                "name" to JsonPrimitive(name),
                "mime_type" to JsonPrimitive(mimeType),
                "size" to Json.parseToJsonElement(size.toString()),
            ),
        )
}

internal fun fileRef(payload: JsonElement?): FileRef {
    val value = payload as? JsonObject ?: error("Upload result must be a FileRef object")

    fun string(key: String): String {
        val field = value[key] as? JsonPrimitive ?: error("Missing upload $key")
        require(field.isString && field.content.isNotBlank()) { "Upload $key must be a nonempty string" }
        return field.content
    }
    val size = value["size"] as? JsonPrimitive ?: error("Missing upload size")
    require(!size.isString) { "Upload size must be an integer" }
    return FileRef(
        string("upload_id"),
        string("name"),
        string("mime_type"),
        requireNotNull(size.content.toULongOrNull()) {
            "Upload size must fit uint64"
        },
    )
}
