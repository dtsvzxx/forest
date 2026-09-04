package io.mainactor.worktree.usage

/** Dollars per million tokens, one row per billing category. */
data class Pricing(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite5m: Double,
    val cacheWrite1h: Double,
) {
    fun cost(usage: TokenUsage): Double =
        (usage.input * input +
            usage.output * output +
            usage.cacheRead * cacheRead +
            usage.cacheWrite5m * cacheWrite5m +
            usage.cacheWrite1h * cacheWrite1h) / 1_000_000.0
}

/**
 * List prices, per million tokens.
 *
 * Taken from https://platform.claude.com/docs/en/about-claude/pricing on **2026-09-04**. Nothing on
 * disk carries a price — `additionalModelCostsCache` in `~/.claude.json` is empty and the
 * transcripts record tokens only — so this table is the whole basis of the cost figure, and it goes
 * stale silently. Every number it produces is presented as an estimate.
 *
 * Rates are written out rather than derived from the base input price: the multipliers are 1.25x
 * for a five-minute cache write and 2x for an hour, and 0.1x for a read — except on Fable 5.1 and
 * Mythos 5.1, where a read is 0.025x. A formula would get those two wrong.
 *
 * The table also ignores two modifiers that do not apply here: the Batch API discount (Claude Code
 * does not batch) and the 1.1x for `inference_geo: "us"` (the transcripts on this machine record
 * `not_available`).
 */
object ModelPricing {

    /**
     * Longest prefix wins, so `claude-opus-4-5` is not matched by the `claude-opus-4` row. The
     * transcript writes short ids such as `claude-opus-5`, but a dated id like
     * `claude-opus-4-5-20251101` has to resolve to the same row.
     */
    private val standard: List<Pair<String, Pricing>> = listOf(
        "claude-fable-5-1" to Pricing(10.0, 50.0, 0.25, 12.50, 20.0),
        "claude-mythos-5-1" to Pricing(10.0, 50.0, 0.25, 12.50, 20.0),
        "claude-fable-5" to Pricing(10.0, 50.0, 1.0, 12.50, 20.0),
        "claude-mythos-5" to Pricing(10.0, 50.0, 1.0, 12.50, 20.0),

        "claude-opus-5" to Pricing(5.0, 25.0, 0.50, 6.25, 10.0),
        "claude-opus-4-8" to Pricing(5.0, 25.0, 0.50, 6.25, 10.0),
        "claude-opus-4-7" to Pricing(5.0, 25.0, 0.50, 6.25, 10.0),
        "claude-opus-4-6" to Pricing(5.0, 25.0, 0.50, 6.25, 10.0),
        "claude-opus-4-5" to Pricing(5.0, 25.0, 0.50, 6.25, 10.0),
        "claude-opus-4-1" to Pricing(15.0, 75.0, 1.50, 18.75, 30.0),
        "claude-opus-4" to Pricing(15.0, 75.0, 1.50, 18.75, 30.0),

        "claude-sonnet-5" to Pricing(2.0, 10.0, 0.20, 2.50, 4.0),
        "claude-sonnet-4-6" to Pricing(3.0, 15.0, 0.30, 3.75, 6.0),
        "claude-sonnet-4-5" to Pricing(3.0, 15.0, 0.30, 3.75, 6.0),
        "claude-sonnet-4" to Pricing(3.0, 15.0, 0.30, 3.75, 6.0),

        "claude-haiku-4-5" to Pricing(1.0, 5.0, 0.10, 1.25, 2.0),
        "claude-3-5-haiku" to Pricing(0.80, 4.0, 0.08, 1.0, 1.60),
        "claude-haiku-3-5" to Pricing(0.80, 4.0, 0.08, 1.0, 1.60),
    ).sortedByDescending { it.first.length }

    /**
     * Fast mode, a research preview available on Opus 5 and Opus 4.8 only.
     *
     * It is $10/$50 rather than $5/$25, and the cache multipliers stack on top of that — so a fast
     * session billed at standard rates would be reported at half its real cost.
     */
    private val fast: List<Pair<String, Pricing>> = listOf(
        "claude-opus-5" to Pricing(10.0, 50.0, 1.0, 12.50, 20.0),
        "claude-opus-4-8" to Pricing(10.0, 50.0, 1.0, 12.50, 20.0),
    ).sortedByDescending { it.first.length }

    /** The rates for [key], or null when the model is not in the table. */
    fun of(key: ModelKey): Pricing? {
        val id = key.model.lowercase()
        if (key.fast) fast.firstOrNull { id.startsWith(it.first) }?.let { return it.second }
        return standard.firstOrNull { id.startsWith(it.first) }?.second
    }

    /** The date the table above was taken, for the tooltip that presents the figure as an estimate. */
    const val PRICES_AS_OF = "2026-09-04"
}
