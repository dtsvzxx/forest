package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Zero-width characters, which are the difference between one thing on the screen and several.
 *
 * A cell holds one code point, which is all almost every cell needs — so the marks live in a side
 * table that is usually null rather than in a second array that is usually empty.
 */
class CombiningMarksTest {

    private val escape = 27.toChar().toString()

    /** Written out of their code points, so no invisible character has to survive an editor. */
    private val acute = 0x0301.toChar().toString()
    private val joiner = 0x200D.toChar().toString()

    private fun terminal(columns: Int = 20, rows: Int = 3) =
        TerminalModel(columns, rows, scrollback = 10)

    /** An accent written separately from its letter belongs to the letter, not to a cell of its own. */
    @Test
    fun `a combining accent stays on the letter it belongs to`() {
        val terminal = terminal()
        terminal.feed("e" + acute + "x")
        assertEquals("e" + acute + "x", terminal.buffer.line(0).text())
        assertEquals(2, terminal.cursorColumn, "the accent must not take a column of its own")
    }

    /**
     * A family emoji is several people and the joiners between them. Dropping the joiners turns it
     * into several emoji, which is the most visible form of this bug in an agent's output.
     */
    @Test
    fun `a joined emoji stays one cluster`() {
        val terminal = terminal()
        val family = "👨" + joiner + "👩" + joiner + "👧"
        terminal.feed(family)
        assertEquals(family, terminal.buffer.line(0).text())
    }

    /** After a double-width character the cursor is two columns along; the mark belongs to the character. */
    @Test
    fun `a mark after a wide character finds the character, not its continuation`() {
        val terminal = terminal()
        terminal.feed("中" + acute)
        assertEquals("中" + acute, terminal.buffer.line(0).text())
        assertEquals(acute, terminal.buffer.line(0).marksAt(0))
        assertNull(terminal.buffer.line(0).marksAt(1), "nothing hangs off a continuation cell")
    }

    @Test
    fun `overwriting a cell drops what was hanging off it`() {
        val terminal = terminal()
        terminal.feed("e" + acute)
        terminal.feed(escape + "[1G")
        terminal.feed("o")
        assertEquals("o", terminal.buffer.line(0).text(), "the accent outlived its letter")
    }

    @Test
    fun `marks move with their cells when characters are inserted`() {
        val terminal = terminal()
        terminal.feed("ae" + acute + "b")
        terminal.feed(escape + "[1G" + escape + "[4h") // home, then insert mode
        terminal.feed("XY")
        assertEquals("XYae" + acute + "b", terminal.buffer.line(0).text())
    }

    @Test
    fun `a mark survives a rewrap`() {
        val terminal = TerminalModel(6, 3, scrollback = 10)
        terminal.feed("abcde" + acute + "fgh")
        terminal.resize(12, 3)
        val document = (0 until terminal.buffer.documentLines)
            .mapNotNull { terminal.buffer.documentLine(it)?.text() }
        assertEquals("abcde" + acute + "fgh", document.first { it.isNotEmpty() })
    }

    @Test
    fun `copied text carries the marks`() {
        val terminal = terminal()
        terminal.feed("e" + acute + "x")
        val text = terminal.buffer.textBetween(
            TerminalPosition(terminal.buffer.scrollbackSize, 0),
            TerminalPosition(terminal.buffer.scrollbackSize, 2),
        )
        assertEquals("e" + acute + "x", text)
    }
}
