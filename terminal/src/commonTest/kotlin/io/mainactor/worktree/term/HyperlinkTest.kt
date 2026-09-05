package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `OSC 8`, which turns a run of cells into something you can click.
 *
 * The cells hold an id rather than the address, so a thousand cells of the same link cost a
 * thousand ints — and so two runs of the same address can still be told apart when a program gives
 * them different ids.
 */
class HyperlinkTest {

    private val escape = 27.toChar().toString()
    private fun terminal() = TerminalModel(30, 3, scrollback = 10)

    private fun TerminalModel.linkOn(column: Int): String? =
        emulator.linkTarget(buffer.line(0).linkAt(column))

    @Test
    fun `cells printed inside a link remember where they point`() {
        val terminal = terminal()
        terminal.feed(escape + "]8;;https://example.com" + escape + "\\click" + escape + "]8;;" + escape + "\\ plain")

        assertEquals("https://example.com", terminal.linkOn(0))
        assertEquals("https://example.com", terminal.linkOn(4))
        assertNull(terminal.linkOn(6), "the text after the link is not part of it")
        assertEquals("click plain", terminal.buffer.line(0).text())
    }

    /** An empty address is how a program says the underlined part is over. */
    @Test
    fun `an empty address closes the link`() {
        val terminal = terminal()
        terminal.feed(escape + "]8;;https://example.com" + escape + "\\ab")
        terminal.feed(escape + "]8;;" + escape + "\\cd")
        assertEquals("https://example.com", terminal.linkOn(1))
        assertEquals(TerminalLine.NO_LINK, terminal.buffer.line(0).linkAt(2))
    }

    @Test
    fun `two links are told apart even when they point at the same place`() {
        val terminal = terminal()
        val target = "https://example.com"
        terminal.feed(escape + "]8;;" + target + escape + "\\one" + escape + "]8;;" + escape + "\\ ")
        terminal.feed(escape + "]8;;" + target + escape + "\\two" + escape + "]8;;" + escape + "\\")
        val first = terminal.buffer.line(0).linkAt(0)
        val second = terminal.buffer.line(0).linkAt(4)
        assertEquals(target, terminal.emulator.linkTarget(first))
        assertEquals(target, terminal.emulator.linkTarget(second))
        assert(first != second) { "the two runs should be separate links" }
    }

    @Test
    fun `overwriting a linked cell unlinks it`() {
        val terminal = terminal()
        terminal.feed(escape + "]8;;https://example.com" + escape + "\\link" + escape + "]8;;" + escape + "\\")
        terminal.feed(escape + "[1Gx")
        assertEquals(TerminalLine.NO_LINK, terminal.buffer.line(0).linkAt(0))
    }

    @Test
    fun `a reset forgets every link`() {
        val terminal = terminal()
        terminal.feed(escape + "]8;;https://example.com" + escape + "\\link")
        terminal.feed(escape + "c")
        assertNull(terminal.emulator.linkTarget(1))
    }
}
