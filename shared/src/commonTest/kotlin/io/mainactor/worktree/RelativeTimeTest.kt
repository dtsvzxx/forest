package io.mainactor.worktree

import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeTest {

    private val now = 1_700_000_000L

    @Test
    fun `renders each unit, singular and plural`() {
        assertEquals("just now", relativeTime(now - 30, now))
        assertEquals("1 minute ago", relativeTime(now - 60, now))
        assertEquals("5 minutes ago", relativeTime(now - 5 * 60, now))
        assertEquals("1 hour ago", relativeTime(now - 3_600, now))
        assertEquals("3 hours ago", relativeTime(now - 3 * 3_600, now))
        assertEquals("1 day ago", relativeTime(now - 86_400, now))
        assertEquals("6 days ago", relativeTime(now - 6 * 86_400, now))
        assertEquals("1 month ago", relativeTime(now - 40 * 86_400, now))
        assertEquals("2 years ago", relativeTime(now - 800 * 86_400, now))
    }

    @Test
    fun `an absent timestamp renders as nothing`() {
        assertEquals("", relativeTime(0, now))
        assertEquals("", relativeTime(-1, now))
    }

    @Test
    fun `a clock skewed into the future does not produce a negative age`() {
        assertEquals("just now", relativeTime(now + 500, now))
    }
}
