package io.mainactor.worktree.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageParserTest {

    @Test
    fun `reads the token counts off an assistant line`() {
        val record = UsageParser.parse(assistantLine(requestId = "req_1"))!!

        assertEquals("req_1", record.requestId)
        assertEquals(ModelKey("claude-opus-5", fast = false), record.key)
        assertEquals(2, record.usage.input)
        assertEquals(888, record.usage.output)
        assertEquals(251_658, record.usage.cacheRead)
    }

    @Test
    fun `a one-hour cache write is not counted as a five-minute one`() {
        // They are billed at 2x and 1.25x of the input rate. Claude Code uses the one-hour cache,
        // so collapsing them would understate the largest line on the bill.
        val record = UsageParser.parse(assistantLine(requestId = "req_1"))!!

        assertEquals(0, record.usage.cacheWrite5m)
        assertEquals(2_492, record.usage.cacheWrite1h)
    }

    @Test
    fun `a usage block with no breakdown counts as a five-minute write`() {
        val line = """
            {"type":"assistant","requestId":"req_1","message":{"id":"m","model":"claude-sonnet-5",
            "usage":{"input_tokens":10,"output_tokens":20,"cache_creation_input_tokens":300}}}
        """.trimIndent().replace("\n", "")

        val record = UsageParser.parse(line)!!

        assertEquals(300, record.usage.cacheWrite5m)
        assertEquals(0, record.usage.cacheWrite1h)
    }

    @Test
    fun `fast mode is part of the model's identity`() {
        val line = assistantLine(requestId = "req_1", speed = "fast")

        val record = UsageParser.parse(line)!!

        // Opus fast mode is $10/$50 against $5/$25, so it cannot share a bucket with standard.
        assertTrue(record.key.fast)
        assertEquals(10.0, ModelPricing.of(record.key)!!.input)
        assertEquals(5.0, ModelPricing.of(record.key.copy(fast = false))!!.input)
    }

    @Test
    fun `lines that carry no billed usage are ignored`() {
        assertNull(UsageParser.parse("""{"type":"user","message":{"role":"user","content":"hi"}}"""))
        assertNull(UsageParser.parse("""{"type":"mode","mode":"normal"}"""))
        // Claude Code writes <synthetic> for messages it made up itself after an API error.
        assertNull(UsageParser.parse(assistantLine(requestId = "req_1", model = "<synthetic>")))
        assertNull(UsageParser.parse(""))
    }

    @Test
    fun `a half-written line is ignored rather than thrown on`() {
        // The reader tails a file that is being appended to; the last line is routinely partial.
        val truncated = assistantLine(requestId = "req_1").dropLast(40)

        assertNull(UsageParser.parse(truncated))
    }

    @Test
    fun `cost is computed from the published rates`() {
        val opus = ModelPricing.of(ModelKey("claude-opus-5"))!!

        // A million output tokens on Opus 5 is $25; a million cache reads is $0.50.
        assertEquals(25.0, opus.cost(TokenUsage(output = 1_000_000)), 1e-9)
        assertEquals(0.50, opus.cost(TokenUsage(cacheRead = 1_000_000)), 1e-9)
        assertEquals(10.0, opus.cost(TokenUsage(cacheWrite1h = 1_000_000)), 1e-9)
        assertEquals(6.25, opus.cost(TokenUsage(cacheWrite5m = 1_000_000)), 1e-9)
    }

    @Test
    fun `a dated model id resolves to the same rates as the short one`() {
        // The transcript writes `claude-opus-5`, but an API id can carry a date suffix.
        assertEquals(
            ModelPricing.of(ModelKey("claude-opus-5")),
            ModelPricing.of(ModelKey("claude-opus-5-20260115")),
        )
        // The longest prefix has to win, or every 4.x Opus would be priced as the retired Opus 4.
        assertEquals(5.0, ModelPricing.of(ModelKey("claude-opus-4-5"))!!.input)
        assertEquals(15.0, ModelPricing.of(ModelKey("claude-opus-4"))!!.input)
    }

    @Test
    fun `an unknown model is counted in tokens and flagged in cost`() {
        val usage = WorktreeUsage(mapOf(ModelKey("some-future-model") to TokenUsage(output = 100)), requests = 1)

        assertEquals(100, usage.tokens.total)
        assertEquals(0.0, usage.costUsd)
        assertTrue(usage.hasUnpricedModel, "the estimate is short and the UI has to say so")
    }

    private fun assistantLine(
        requestId: String,
        model: String = "claude-opus-5",
        speed: String = "standard",
    ) = """
        {"parentUuid":"p","isSidechain":false,"userType":"external","cwd":"/repo",
        "sessionId":"s","version":"2.1.259","gitBranch":"main","type":"assistant","uuid":"u",
        "timestamp":"2026-09-04T08:03:45.105Z","requestId":"$requestId","apiBlockIndex":2,
        "message":{"id":"msg_1","type":"message","role":"assistant","model":"$model",
        "stop_reason":"tool_use","content":[],
        "usage":{"input_tokens":2,"cache_creation_input_tokens":2492,
        "cache_read_input_tokens":251658,"output_tokens":888,
        "cache_creation":{"ephemeral_1h_input_tokens":2492,"ephemeral_5m_input_tokens":0},
        "speed":"$speed","service_tier":"standard"}}}
    """.trimIndent().replace("\n", "")
}
