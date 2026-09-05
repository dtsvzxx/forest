package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ESC = 27.toChar().toString()

/** The whole document as text, which is what a reflow test actually wants to look at. */
private fun TerminalModel.document(): List<String> =
    (0 until buffer.documentLines).mapNotNull { buffer.documentLine(it)?.text() }

/**
 * The hardest correctness problem in the emulator, and the one a user meets every time they drag a
 * splitter.
 */
class ReflowTest {

    private fun terminal(columns: Int = 10, rows: Int = 4) =
        TerminalModel(columns, rows, scrollback = 100)

    /** What everyone notices: narrowing a pane used to throw away everything past the new edge. */
    @Test
    fun `narrowing rewraps rather than truncating`() {
        val terminal = terminal(columns = 12, rows = 4)
        terminal.feed("abcdefghijkl")

        terminal.resize(6, 4)

        val document = terminal.document()
        val first = document.indexOf("abcdef")
        assertTrue(first >= 0, "the line was not rewrapped: $document")
        assertEquals("ghijkl", document[first + 1], "the second half should follow the first")
    }

    /** And widening puts back together what a narrower pane had to break. */
    @Test
    fun `widening rejoins what wrapping split`() {
        val terminal = terminal(columns = 6, rows = 4)
        terminal.feed("abcdefghijkl")
        assertTrue(terminal.buffer.line(0).wrapped)

        terminal.resize(12, 4)

        assertEquals("abcdefghijkl", terminal.buffer.line(0).text())
        assertFalse(terminal.buffer.line(0).wrapped, "it fits now, so it is not a wrapped line")
    }

    /**
     * The rule that makes the whole thing meaningful: a break the *program* asked for is not the
     * same as a break the terminal had to make, and only the second may be undone.
     */
    @Test
    fun `a newline the program printed is never rejoined`() {
        val terminal = terminal(columns = 6, rows = 4)
        terminal.feed("abc\r\ndef")

        terminal.resize(20, 4)

        val document = terminal.document()
        assertTrue("abc" in document && "def" in document, "the two lines were merged: $document")
        assertTrue("abcdef" !in document, "a printed newline was rejoined: $document")
    }

    @Test
    fun `going narrow and wide again gives back what was there`() {
        val terminal = terminal(columns = 20, rows = 6)
        terminal.feed("the quick brown fox\r\njumps over\r\nthe lazy dog")
        val before = terminal.screenText()

        terminal.resize(7, 6)
        terminal.resize(20, 6)

        assertEquals(before, terminal.screenText())
    }

    /** A prompt that jumps out from under the person typing into it is the worst kind of bug. */
    @Test
    fun `the cursor keeps the character it was on`() {
        val terminal = terminal(columns = 12, rows = 4)
        terminal.feed("abcdefghij")
        assertEquals(10, terminal.cursorColumn)

        terminal.resize(6, 4)

        // The eleventh cell of a twelve-column line is the fifth of the second six-column one.
        assertEquals(4, terminal.cursorColumn)
        val cursorLine = terminal.buffer.documentLine(terminal.buffer.scrollbackSize + terminal.cursorRow)
        assertEquals("ghij", cursorLine?.text(), "the cursor should be on the half it was on")
    }

    @Test
    fun `a double-width character is never split across the new edge`() {
        val terminal = terminal(columns = 10, rows = 3)
        terminal.feed("abc中文def")

        terminal.resize(5, 3)

        // `abc` plus one wide character is exactly five columns; the second wide one starts the
        // next line whole rather than losing half of itself to the edge.
        val document = terminal.document()
        val first = document.indexOf("abc中")
        assertTrue(first >= 0, "the wide character was split: $document")
        assertEquals("文def", document[first + 1])
    }

    @Test
    fun `styles travel with the characters they were on`() {
        val terminal = terminal(columns = 12, rows = 3)
        terminal.feed("${ESC}[31mredredredred${ESC}[0m")

        terminal.resize(6, 3)

        // The second half moved to a line of its own and has to have taken its colour with it.
        val document = terminal.document()
        val second = document.indexOf("redred") + 1
        val moved = terminal.buffer.documentLine(second)!!
        assertEquals("redred", moved.text())
        assertEquals(CellStyle.indexed(1), CellStyle.foreground(moved.styleAt(0)))
    }

    /**
     * A full-screen program will redraw itself for the new size; rewrapping its screen would fight
     * that redraw with a stale copy of the old one.
     */
    @Test
    fun `the alternate screen is not rewrapped`() {
        val terminal = terminal(columns = 12, rows = 3)
        terminal.feed("shell output\r\n")
        terminal.feed("${ESC}[?1049h")
        terminal.feed("abcdefghijkl")

        terminal.resize(6, 3)

        assertEquals("abcdef", terminal.buffer.line(0).text(), "the alternate screen is truncated, not rewrapped")

        terminal.feed("${ESC}[?1049l")
        // The primary screen was rewrapped while the program had the alternate one, so its line is
        // now in two halves — which is what a six-column pane can hold.
        val document = terminal.document()
        assertTrue("shell " in document && "output" in document, "the shell output was lost: ${'$'}document")
    }

    /** History and screen rewrap together, because a wrapped line can straddle the two. */
    @Test
    fun `a line that straddles the history is rejoined`() {
        val terminal = terminal(columns = 6, rows = 2)
        terminal.feed("abcdefghijkl\r\none\r\ntwo")
        assertTrue(terminal.buffer.scrollbackSize > 0, "the long line should have scrolled off")

        terminal.resize(12, 2)

        val document = (0 until terminal.buffer.documentLines)
            .mapNotNull { terminal.buffer.documentLine(it)?.text() }
        assertTrue("abcdefghijkl" in document, "the two halves were not rejoined: $document")
    }

    @Test
    fun `resizing an empty terminal does nothing surprising`() {
        val terminal = terminal(columns = 10, rows = 4)
        terminal.resize(30, 10)
        assertEquals(30, terminal.columns)
        assertEquals(10, terminal.rows)
        assertEquals(0, terminal.cursorRow)
        assertEquals(0, terminal.cursorColumn)
    }
}
