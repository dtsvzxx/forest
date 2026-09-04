package io.mainactor.worktree.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsageFormatTest {

    @Test
    fun `token counts are shortened without lying about them`() {
        assertEquals("847", UsageFormat.tokens(847))
        assertEquals("12.3K", UsageFormat.tokens(12_345))
        // Rounds rather than truncates, and rounds a tie up: 1.25M is 1.3M, not the 1.2M that
        // ties-to-even would give.
        assertEquals("1.3M", UsageFormat.tokens(1_250_000))
        assertEquals("1.7M", UsageFormat.tokens(1_690_000))
        assertEquals("301.0M", UsageFormat.tokens(300_960_000))
    }

    @Test
    fun `money rounds to the cent and keeps both places`() {
        assertEquals("$12.34", UsageFormat.usd(12.34))
        assertEquals("$0.03", UsageFormat.usd(0.0257))
        // A trailing zero has to survive, or three dollars ten reads as three dollars one.
        assertEquals("$3.10", UsageFormat.usd(3.1))
        assertEquals("$0.00", UsageFormat.usd(0.0))
        // Half a cent rounds up; ties-to-even would report a cent less.
        assertEquals("$0.13", UsageFormat.usd(0.125))
    }

    @Test
    fun `exact counts are grouped in the tooltip`() {
        assertEquals("1", UsageFormat.grouped(1))
        assertEquals("999", UsageFormat.grouped(999))
        assertEquals("1,000", UsageFormat.grouped(1_000))
        assertEquals("300,910,725", UsageFormat.grouped(300_910_725))
    }

    @Test
    fun `the tooltip says where the money figure comes from`() {
        val usage = WorktreeUsage(
            byModel = mapOf(ModelKey("claude-opus-5") to TokenUsage(output = 1_000_000)),
            requests = 3,
            sessions = 1,
        )

        val detail = UsageFormat.detail(usage)

        assertTrue("3 responses" in detail)
        assertTrue("1 session" in detail)
        assertTrue("Claude Code · claude-opus-5" in detail, detail)
        // A hardcoded price table goes stale silently, so the badge must never claim to be a bill.
        assertTrue("Estimated ${'$'}25.00" in detail, detail)
        assertTrue(ModelPricing.PRICES_AS_OF in detail)
    }

    @Test
    fun `the tooltip admits when the estimate is short`() {
        val usage = WorktreeUsage(
            byModel = mapOf(ModelKey("some-future-model") to TokenUsage(output = 10)),
            requests = 1,
            sessions = 1,
        )

        assertTrue("short" in UsageFormat.detail(usage), UsageFormat.detail(usage))
    }

    @Test
    fun `each tool gets its own line with its own subtotal`() {
        val usage = WorktreeUsage(
            byModel = mapOf(
                ModelKey("claude-opus-5", tool = AgentTool.CLAUDE) to TokenUsage(output = 1_000_000),
                ModelKey("gpt-5.3-codex", tool = AgentTool.CODEX) to TokenUsage(output = 1_000_000),
            ),
            requests = 4,
            sessions = 2,
        )

        val detail = UsageFormat.detail(usage)

        assertTrue("Claude Code · claude-opus-5 · 1.0M · ${'$'}25.00" in detail, detail)
        assertTrue("Codex · gpt-5.3-codex · 1.0M · ${'$'}14.00" in detail, detail)
        assertEquals("${'$'}39.00", UsageFormat.usd(usage.costUsd))
    }

    @Test
    fun `fast mode is named, because it is a different price`() {
        val usage = WorktreeUsage(
            byModel = mapOf(ModelKey("claude-opus-5", fast = true) to TokenUsage(output = 1_000_000)),
            requests = 1,
            sessions = 1,
        )

        assertTrue("(fast)" in UsageFormat.detail(usage))
        assertEquals("$50.00", UsageFormat.usd(usage.costUsd))
    }
}
