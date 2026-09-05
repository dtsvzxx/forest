package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the emulator has to keep up with, as numbers rather than as a feeling.
 *
 * The same idea as `RefreshCostTest` in `:shared`: a budget that fails when it regresses, set well
 * below what this machine does so it reports a real change rather than a busy afternoon. A terminal
 * that cannot outrun a pty is not slow, it is broken — the pty delivers at the speed of a pipe, and
 * anything slower turns `cat` of a large file into a frozen window.
 */
class TerminalThroughputTest {

    /**
     * A flood is survived in the parser or not at all.
     *
     * The renderer draws once per frame however much arrives, so this is the number that decides
     * whether a build log scrolls or stutters. Measured on ordinary output rather than on escape
     * sequences, because that is what a build log is.
     */
    @Test
    fun `plain output is parsed far faster than a pty can deliver it`() {
        val line = "a line of ordinary build output, about eighty columns wide, as they usually are\r\n"
        val chunk = line.repeat(128).encodeToByteArray()
        val model = TerminalModel(80, 24, scrollback = 10_000)

        // Warm up, then measure: the first pass through is the JIT's, not the parser's.
        repeat(200) { model.feed(chunk, chunk.size) }
        val rounds = 2_000
        val started = System.nanoTime()
        repeat(rounds) { model.feed(chunk, chunk.size) }
        val elapsed = System.nanoTime() - started

        val megabytes = rounds.toLong() * chunk.size / (1024.0 * 1024.0)
        val perSecond = megabytes / (elapsed / 1_000_000_000.0)
        assertTrue(
            perSecond > 30.0,
            "parsing ran at %.1f MB/s, which is below the budget of 30".format(perSecond),
        )
    }

    /** Escape sequences are the expensive path; a coloured log must not fall off a cliff. */
    @Test
    fun `coloured output stays within the same order of magnitude`() {
        val escape = 27.toChar()
        val line = "$escape[32m ok $escape[0m$escape[1;33mwarn$escape[0m plain text on the line\r\n"
        val chunk = line.repeat(128).encodeToByteArray()
        val model = TerminalModel(80, 24, scrollback = 10_000)

        repeat(200) { model.feed(chunk, chunk.size) }
        val rounds = 2_000
        val started = System.nanoTime()
        repeat(rounds) { model.feed(chunk, chunk.size) }
        val elapsed = System.nanoTime() - started

        val megabytes = rounds.toLong() * chunk.size / (1024.0 * 1024.0)
        val perSecond = megabytes / (elapsed / 1_000_000_000.0)
        assertTrue(
            perSecond > 15.0,
            "parsing coloured output ran at %.1f MB/s, which is below the budget of 15".format(perSecond),
        )
    }

    /**
     * A hundred thousand lines cost the same as ten thousand.
     *
     * The ceiling is what makes a pane's memory a property of the terminal rather than of what
     * somebody's build printed.
     */
    @Test
    fun `the history has a ceiling however much is printed`() {
        val model = TerminalModel(80, 24, scrollback = 10_000)
        val chunk = (1..1000).joinToString("") { "line $it\r\n" }.encodeToByteArray()
        repeat(100) { model.feed(chunk, chunk.size) }

        assertEquals(10_000, model.buffer.scrollbackSize, "the history grew past its ceiling")
        assertEquals("line 1000", model.buffer.line(22).text(), "the newest output should be on screen")
    }

    /** A wide pane is the expensive one to draw; it must not be expensive to fill. */
    @Test
    fun `a very wide pane is still parsed within budget`() {
        val model = TerminalModel(400, 100, scrollback = 5_000)
        val chunk = ("x".repeat(399) + "\r\n").repeat(64).encodeToByteArray()

        repeat(50) { model.feed(chunk, chunk.size) }
        val started = System.nanoTime()
        repeat(500) { model.feed(chunk, chunk.size) }
        val elapsed = System.nanoTime() - started

        val megabytes = 500L * chunk.size / (1024.0 * 1024.0)
        val perSecond = megabytes / (elapsed / 1_000_000_000.0)
        assertTrue(
            perSecond > 20.0,
            "a 400-column pane parsed at %.1f MB/s, which is below the budget of 20".format(perSecond),
        )
    }
}
