package io.mainactor.worktree

/**
 * Renders a timestamp as an age, the way git's own `%cr` does.
 *
 * Rolled by hand rather than taken from a date library because the app needs the same wording for
 * commit times (from git) and file times (from the filesystem), and because commonMain has no
 * clock of its own — "now" is always passed in.
 */
fun relativeTime(then: Long, now: Long): String {
    val seconds = now - then
    return when {
        then <= 0L -> ""
        seconds < 0L -> "just now"
        seconds < 60L -> "just now"
        seconds < 3600L -> plural(seconds / 60L, "minute")
        seconds < 86_400L -> plural(seconds / 3600L, "hour")
        seconds < 2_592_000L -> plural(seconds / 86_400L, "day")
        seconds < 31_536_000L -> plural(seconds / 2_592_000L, "month")
        else -> plural(seconds / 31_536_000L, "year")
    }
}

private fun plural(count: Long, unit: String) =
    if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
