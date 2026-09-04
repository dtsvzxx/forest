package io.mainactor.worktree.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One API response, as recorded in a transcript. */
data class UsageRecord(
    /** What identifies the *response*, which is not the same as the line. See [UsageParser]. */
    val requestId: String,
    val key: ModelKey,
    val usage: TokenUsage,
)

/**
 * Pulls token counts out of one line of a Claude Code transcript.
 *
 * Only four things are read from a line — its `type`, the request id, `message.model` and
 * `message.usage`. Nothing else is looked at and nothing else is kept: these files hold the whole
 * conversation, and this feature has no business with any of it.
 *
 * **One response spans several lines.** Claude Code writes one line per content block, and every
 * one of them repeats the identical `usage` object. In a real 3728-line transcript there were 1050
 * assistant lines carrying usage but only 596 distinct `requestId`s — so summing lines rather than
 * responses over-reports by about 1.8x. Callers must de-duplicate on [UsageRecord.requestId];
 * [TranscriptTail] is what does that here.
 */
object UsageParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns the record this line carries, or null when it carries none.
     *
     * Null covers everything uninteresting and everything broken alike — a user line, a control
     * line, a locally generated `<synthetic>` error, and a half-written line at the end of a file
     * being appended to as we read it.
     */
    fun parse(line: String): UsageRecord? {
        // Cheap gate: most lines in a transcript are user turns, attachments and tool results, and
        // building a JSON tree for those is the bulk of the work this avoids.
        if (!line.contains("\"usage\"")) return null

        return runCatching {
            val root = json.parseToJsonElement(line).jsonObject
            if (root.string("type") != "assistant") return null

            val message = root["message"]?.jsonObject ?: return null
            val model = message.string("model") ?: return null
            // Claude Code writes `<synthetic>` for messages it generated itself after an API
            // error; they carry a usage block of zeros and no model was billed.
            if (model.isBlank() || model == "<synthetic>") return null

            val usage = message["usage"]?.jsonObject ?: return null
            val cacheCreation = usage["cache_creation"]?.jsonObject

            UsageRecord(
                // `requestId` is the response. Falling back to the message id keeps older or
                // partial envelopes from being counted once per content block.
                requestId = root.string("requestId")
                    ?: message.string("id")
                    ?: return null,
                key = ModelKey(model = model, fast = usage.string("speed") == "fast"),
                usage = TokenUsage(
                    input = usage.long("input_tokens"),
                    output = usage.long("output_tokens"),
                    cacheRead = usage.long("cache_read_input_tokens"),
                    // Split by lifetime when the API reported it; the flat total is a 5-minute
                    // write, which is what it means when there is no breakdown.
                    cacheWrite5m = cacheCreation?.long("ephemeral_5m_input_tokens")
                        ?: usage.long("cache_creation_input_tokens"),
                    cacheWrite1h = cacheCreation?.long("ephemeral_1h_input_tokens") ?: 0L,
                ),
            )
        }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNullSafe()

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: 0L

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it != "null" }
}
