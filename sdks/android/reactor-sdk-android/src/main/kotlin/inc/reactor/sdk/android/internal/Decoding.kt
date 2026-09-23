package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.CommandReply
import inc.reactor.sdk.android.Stats

// How successful completions become typed results.
//
// Each of these runs *before* the promise is claimed, so throwing here produces a DECODE_FAILED
// the caller can catch rather than a settled promise nothing can un-settle.

/**
 * A command reply.
 *
 * An absent payload is success with nothing to report — the ABI documents `result_json` as absent
 * when the handler acknowledged the command but returned no message. A payload that *will not
 * parse* is a decode failure, and the difference matters: collapsing both to an empty reply is
 * what makes a malformed response indistinguishable from a silent handler.
 */
internal fun decodeCommandReply(resultJson: String?): CommandReply {
    if (resultJson.isNullOrBlank()) return CommandReply()
    val fields = Json.parseObject(resultJson)
    return CommandReply(type = fields["type"] as? String, data = fields["data"])
}

/**
 * A schema.
 *
 * An absent schema is **not** an empty one. Substituting `{}` here would make `requestSchema()`
 * answer with a schema declaring nothing, which no caller can tell from a model that genuinely
 * declares nothing — the exact failure the skill warns about. So an absent payload throws, and
 * only a parseable object succeeds.
 */
internal fun decodeSchema(resultJson: String?): Map<String, Any?> {
    if (resultJson.isNullOrBlank()) {
        throw JsonException(
            "The schema request succeeded but carried no schema. An empty schema and a missing " +
                "one are different answers, and this SDK will not report one as the other.",
        )
    }
    return Json.parseObject(resultJson)
}

/** Statistics, kept as the platform sent them. */
internal fun decodeStats(resultJson: String?): Stats {
    if (resultJson.isNullOrBlank()) return Stats(emptyMap())
    return Stats(Json.parseObject(resultJson))
}
