package io.mainactor.worktree.usage

import kotlin.math.floor

/**
 * How usage is written in the pane header and its tooltip.
 *
 * Kept out of the composables so the rounding can be tested: a badge that says `$0.00` for three
 * cents, or `1.0M` for 1.6 million, is worse than no badge at all.
 */
object UsageFormat {

    /** `847`, `12.3K`, `1.6M` — short enough to sit in a 26dp header beside three buttons. */
    fun tokens(count: Long): String = when {
        count >= 1_000_000 -> oneDecimal(count / 1_000_000.0) + "M"
        count >= 1_000 -> oneDecimal(count / 1_000.0) + "K"
        else -> count.toString()
    }

    /** Grouped in threes, for the tooltip where the exact number is the point. */
    fun grouped(count: Long): String = count.toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

    /** `$12.34`. Rounds to the cent rather than truncating, so it never reads low. */
    fun usd(amount: Double): String {
        val cents = halfUp(amount * 100)
        return "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }

    /** The badge: what it cost, and how many tokens that was. */
    fun badge(usage: WorktreeUsage): String =
        "${tokens(usage.tokens.total)} · ${usd(usage.costUsd)}"

    /**
     * The tooltip: the totals, then a line per tool, then where the money figure comes from.
     *
     * It says "estimate" because it is one — neither tool records a price, so the figure is
     * computed from a table of list prices that goes stale silently.
     */
    fun detail(usage: WorktreeUsage): String {
        val t = usage.tokens
        return buildString {
            append("${grouped(t.total)} tokens over ${grouped(usage.requests.toLong())} responses")
            append(" in ${usage.sessions} ${if (usage.sessions == 1) "session" else "sessions"}\n")
            append("in ${grouped(t.input)} · out ${grouped(t.output)} · ")
            append("cache read ${grouped(t.cacheRead)} · cache write ${grouped(t.cacheWrite5m + t.cacheWrite1h)}\n")
            usage.tools.forEach { tool -> append(line(usage, tool)) }
            append("Estimated ${usd(usage.costUsd)} at list prices as of ${ModelPricing.PRICES_AS_OF}")
            if (usage.hasUnpricedModel) {
                append(" — and short, some tokens were spent on a model with no price here")
            }
        }
    }

    /** One tool's share: which models it used, and what they came to. */
    private fun line(usage: WorktreeUsage, tool: AgentTool): String {
        val mine = usage.byModel.filterKeys { it.tool == tool }
        val total = mine.values.fold(TokenUsage.NONE, TokenUsage::plus)
        val cost = mine.entries.sumOf { (key, u) -> ModelPricing.of(key)?.cost(u) ?: 0.0 }
        val models = mine.keys
            .sortedBy { it.model }
            .joinToString(", ") { if (it.fast) "${it.model} (fast)" else it.model }
        return "${tool.label} · $models · ${tokens(total.total)} · ${usd(cost)}\n"
    }

    private fun oneDecimal(value: Double): String {
        val scaled = halfUp(value * 10)
        return "${scaled / 10}.${scaled % 10}"
    }

    /**
     * Rounds a non-negative value half *up*.
     *
     * `kotlin.math.round` is ties-to-even, which on money reads low half the time it matters —
     * 12.5 cents would come out as 12. Everything passed here is a token count or a price, so
     * neither is ever negative.
     */
    private fun halfUp(value: Double): Long = floor(value + 0.5).toLong()
}
