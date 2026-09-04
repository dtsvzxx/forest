package io.mainactor.worktree.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexRolloutTest {

    private val fs = InMemoryFiles()
    private val path = "/t/rollout-x.jsonl"

    @Test
    fun `the running total is taken, not added up`() {
        // Codex reports `total_token_usage`, the session's total so far, on every turn. Adding
        // those up would multiply the bill by the number of turns.
        fs.write(
            path,
            meta("/repo/feature") + turn("gpt-5.5") +
                tokenCount(input = 1_000, cached = 0, output = 100) +
                tokenCount(input = 3_000, cached = 500, output = 250),
        )
        val rollout = CodexRollout(path)

        rollout.poll(fs)

        val usage = rollout.usage
        assertEquals(250, usage.tokens.output)
        // 3,000 input of which 500 were cached reads, so 2,500 billed at the full rate.
        assertEquals(2_500, usage.tokens.input)
        assertEquals(500, usage.tokens.cacheRead)
    }

    @Test
    fun `a later poll replaces the total rather than adding to it`() {
        val rollout = CodexRollout(path)
        fs.write(path, meta("/repo/feature") + turn("gpt-5.5") + tokenCount(1_000, 0, 100))
        rollout.poll(fs)
        assertEquals(100, rollout.usage.tokens.output)

        fs.append(path, tokenCount(input = 2_000, cached = 0, output = 400))
        rollout.poll(fs)

        assertEquals(400, rollout.usage.tokens.output, "the second total supersedes the first")
        assertEquals(2_000, rollout.usage.tokens.input)
    }

    @Test
    fun `cached input is not billed twice`() {
        // `input_tokens` includes `cached_input_tokens`; charging both would roughly double the
        // input line, which on a long agent session is most of the bill.
        fs.write(path, meta("/r") + turn("gpt-5.6-sol") + tokenCount(input = 9_269_277, cached = 8_897_152, output = 37_075))
        val rollout = CodexRollout(path)

        rollout.poll(fs)

        val usage = rollout.usage
        assertEquals(372_125, usage.tokens.input)
        assertEquals(8_897_152, usage.tokens.cacheRead)
        assertEquals(9_306_352, usage.tokens.total, "input already contains the cached part")
    }

    @Test
    fun `the working directory comes off the first line`() {
        fs.write(path, meta("/repo/feature") + turn("gpt-5.5") + tokenCount(10, 0, 1))
        val rollout = CodexRollout(path)

        rollout.poll(fs)

        assertEquals("/repo/feature", rollout.cwd)
    }

    @Test
    fun `usage is attributed to Codex and to its model`() {
        fs.write(path, meta("/r") + turn("gpt-5.3-codex") + tokenCount(1_000_000, 0, 1_000_000))
        val rollout = CodexRollout(path)

        rollout.poll(fs)

        val key = rollout.usage.byModel.keys.single()
        assertEquals(AgentTool.CODEX, key.tool)
        assertEquals("gpt-5.3-codex", key.model)
        // $1.75 per million in, $14 per million out.
        assertEquals("$15.75", UsageFormat.usd(rollout.usage.costUsd))
    }

    @Test
    fun `a model switched mid-session takes the total`() {
        // The cumulative counter cannot be split, so it goes to the model in force at the end.
        fs.write(
            path,
            meta("/r") + turn("gpt-5.4") + tokenCount(1_000, 0, 100) +
                turn("gpt-5.5") + tokenCount(2_000, 0, 300),
        )
        val rollout = CodexRollout(path)

        rollout.poll(fs)

        assertEquals("gpt-5.5", rollout.usage.byModel.keys.single().model)
        assertEquals(300, rollout.usage.tokens.output)
    }

    @Test
    fun `conversation lines are not mistaken for counters`() {
        // A rollout's bulk is the conversation itself, and this very repository discusses
        // `token_count` in its own source — a mention must not become a number.
        fs.write(
            path,
            meta("/r") + turn("gpt-5.5") + tokenCount(1_000, 0, 100) +
                """{"type":"response_item","payload":{"type":"message","text":"see token_count in the parser"}}""" + "\n",
        )
        val rollout = CodexRollout(path)

        rollout.poll(fs)

        assertEquals(100, rollout.usage.tokens.output)
        assertTrue(rollout.usage.requests == 1)
    }

    private fun meta(cwd: String) =
        """{"type":"session_meta","payload":{"id":"x","cwd":"$cwd","cli_version":"0.148.0"}}""" + "\n"

    private fun turn(model: String) =
        """{"type":"turn_context","payload":{"turn_id":"t","model":"$model","effort":"high"}}""" + "\n"

    private fun tokenCount(input: Long, cached: Long, output: Long) = """
        {"type":"event_msg","payload":{"type":"token_count","info":{
        "total_token_usage":{"input_tokens":$input,"cached_input_tokens":$cached,
        "cache_write_input_tokens":0,"output_tokens":$output,"reasoning_output_tokens":0,
        "total_tokens":${input + output}},
        "last_token_usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}}
    """.trimIndent().replace("\n", "") + "\n"
}
