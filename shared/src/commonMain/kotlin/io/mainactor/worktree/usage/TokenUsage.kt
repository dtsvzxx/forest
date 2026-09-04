package io.mainactor.worktree.usage

/**
 * What one or many API responses cost in tokens.
 *
 * Cache writes are split by lifetime because they are priced differently — a five-minute write is
 * 1.25x the base input rate and a one-hour write is 2x — and Claude Code uses the one-hour cache,
 * so collapsing the two would understate the bill by a third of the cache line.
 */
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite5m: Long = 0,
    val cacheWrite1h: Long = 0,
) {
    val total: Long get() = input + output + cacheRead + cacheWrite5m + cacheWrite1h

    val isEmpty: Boolean get() = total == 0L

    operator fun plus(other: TokenUsage) = TokenUsage(
        input = input + other.input,
        output = output + other.output,
        cacheRead = cacheRead + other.cacheRead,
        cacheWrite5m = cacheWrite5m + other.cacheWrite5m,
        cacheWrite1h = cacheWrite1h + other.cacheWrite1h,
    )

    companion object {
        val NONE = TokenUsage()
    }
}

/**
 * What a run was billed as: the model, and whether it ran in fast mode.
 *
 * Fast mode is not a detail — on Opus it doubles both the input and the output rate — so it cannot
 * be folded into the model name.
 */
data class ModelKey(val model: String, val fast: Boolean = false)

/** Everything one worktree has spent, as read from the transcripts Claude Code writes. */
data class WorktreeUsage(
    val byModel: Map<ModelKey, TokenUsage> = emptyMap(),
    /** How many API responses this covers, after de-duplication. */
    val requests: Int = 0,
) {
    val tokens: TokenUsage get() = byModel.values.fold(TokenUsage.NONE, TokenUsage::plus)

    val isEmpty: Boolean get() = requests == 0

    /** Estimated dollars, counting only models the price table knows. */
    val costUsd: Double
        get() = byModel.entries.sumOf { (key, usage) ->
            ModelPricing.of(key)?.cost(usage) ?: 0.0
        }

    /** True when something was spent on a model with no price, so the estimate is short. */
    val hasUnpricedModel: Boolean get() = byModel.any { (key, usage) ->
        ModelPricing.of(key) == null && !usage.isEmpty
    }

    companion object {
        val NONE = WorktreeUsage()
    }
}
