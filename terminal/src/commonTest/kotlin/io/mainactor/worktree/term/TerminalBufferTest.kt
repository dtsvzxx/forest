package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellStyleTest {

    @Test
    fun `a style carries a foreground, a background and its attributes at once`() {
        var style = CellStyle.DEFAULT
        style = CellStyle.withForeground(style, CellStyle.rgb(255, 128, 0))
        style = CellStyle.withBackground(style, CellStyle.indexed(4))
        style = CellStyle.with(style, CellStyle.BOLD)
        style = CellStyle.with(style, CellStyle.ITALIC)
        style = CellStyle.withUnderline(style, CellStyle.UNDERLINE_CURLY)

        val foreground = CellStyle.foreground(style)
        assertEquals(CellStyle.COLOR_RGB, CellStyle.colorKind(foreground))
        assertEquals(0xFF8000, CellStyle.colorValue(foreground))

        val background = CellStyle.background(style)
        assertEquals(CellStyle.COLOR_INDEXED, CellStyle.colorKind(background))
        assertEquals(4, CellStyle.colorValue(background))

        assertTrue(CellStyle.has(style, CellStyle.BOLD))
        assertTrue(CellStyle.has(style, CellStyle.ITALIC))
        assertFalse(CellStyle.has(style, CellStyle.REVERSE))
        assertEquals(CellStyle.UNDERLINE_CURLY, CellStyle.underline(style))
    }

    /** The fields share one Long, so the test that matters is that they do not overwrite each other. */
    @Test
    fun `changing one field leaves the others alone`() {
        var style = CellStyle.withForeground(CellStyle.DEFAULT, CellStyle.rgb(1, 2, 3))
        style = CellStyle.withBackground(style, CellStyle.rgb(4, 5, 6))
        style = CellStyle.with(style, CellStyle.STRIKETHROUGH)
        style = CellStyle.withUnderline(style, CellStyle.UNDERLINE_DOUBLE)

        style = CellStyle.without(style, CellStyle.STRIKETHROUGH)
        assertEquals(0x010203, CellStyle.colorValue(CellStyle.foreground(style)))
        assertEquals(0x040506, CellStyle.colorValue(CellStyle.background(style)))
        assertEquals(CellStyle.UNDERLINE_DOUBLE, CellStyle.underline(style))
        assertFalse(CellStyle.has(style, CellStyle.STRIKETHROUGH))

        style = CellStyle.withForeground(style, CellStyle.COLOR_DEFAULT)
        assertEquals(0x040506, CellStyle.colorValue(CellStyle.background(style)))
        assertEquals(CellStyle.UNDERLINE_DOUBLE, CellStyle.underline(style))
    }
}

class TerminalLineTest {

    private fun lineOf(text: String, width: Int = 10): TerminalLine =
        TerminalLine(width).apply {
            text.forEachIndexed { column, character -> set(column, character.code, CellStyle.DEFAULT) }
        }

    @Test
    fun `text drops the blanks past the last thing written`() {
        assertEquals("abc", lineOf("abc").text())
        assertEquals("", TerminalLine(10).text())
    }

    @Test
    fun `inserting pushes the tail right and loses what falls off the end`() {
        val line = lineOf("abcdefghij")
        line.insert(at = 3, count = 2, style = CellStyle.DEFAULT)
        assertEquals("abc  defgh", line.text())
    }

    @Test
    fun `deleting pulls the tail left and blanks what it vacated`() {
        val line = lineOf("abcdefghij")
        line.delete(at = 2, count = 3, style = CellStyle.DEFAULT)
        assertEquals("abfghij", line.text())
    }

    /** `EL` and `ECH` blank with the *current* background, so a coloured region stays coloured. */
    @Test
    fun `clearing keeps the background it was given`() {
        val line = lineOf("abcdef")
        val onBlue = CellStyle.withBackground(CellStyle.DEFAULT, CellStyle.indexed(4))
        line.clear(from = 2, to = 4, style = onBlue)
        assertEquals(onBlue, line.styleAt(2))
        assertEquals(onBlue, line.styleAt(3))
        assertEquals(CellStyle.DEFAULT, line.styleAt(4))
    }

    /** The right half of a wide character holds no text of its own. */
    @Test
    fun `a wide character reads as one character across two cells`() {
        val line = TerminalLine(6)
        line.set(0, '中'.code, CellStyle.DEFAULT)
        line.set(1, TerminalLine.CONTINUATION, CellStyle.DEFAULT)
        line.set(2, 'x'.code, CellStyle.DEFAULT)
        assertEquals("中x", line.text())
    }
}

class TerminalBufferTest {

    private fun buffer(columns: Int = 10, rows: Int = 4, scrollback: Int = 100) =
        TerminalBuffer(columns, rows, scrollback)

    private fun TerminalBuffer.write(row: Int, text: String) {
        text.forEachIndexed { column, character -> line(row).set(column, character.code, CellStyle.DEFAULT) }
    }

    @Test
    fun `scrolling the whole screen keeps what fell off the top`() {
        val buffer = buffer()
        (0 until 4).forEach { buffer.write(it, "line$it") }

        buffer.scrollUp(top = 0, bottom = 3, count = 1)

        assertEquals("line1\nline2\nline3\n", buffer.screenText())
        assertEquals(1, buffer.scrollbackSize)
        assertEquals("line0", buffer.scrollbackLine(0).text())
    }

    /**
     * A program that sets a scroll region is managing a pane of its own; its discarded rows are not
     * your shell's history, and putting them there fills the scrollback with progress bars.
     */
    @Test
    fun `scrolling inside a region keeps nothing`() {
        val buffer = buffer()
        (0 until 4).forEach { buffer.write(it, "line$it") }

        buffer.scrollUp(top = 1, bottom = 2, count = 1)

        assertEquals("line0\nline2\n\nline3", buffer.screenText())
        assertEquals(0, buffer.scrollbackSize, "a region's rows must not reach the history")
    }

    @Test
    fun `scrolling down blanks the line that comes in at the top`() {
        val buffer = buffer()
        (0 until 4).forEach { buffer.write(it, "line$it") }

        buffer.scrollDown(top = 0, bottom = 3, count = 1)

        assertEquals("\nline0\nline1\nline2", buffer.screenText())
        assertEquals(0, buffer.scrollbackSize)
    }

    /**
     * The reason quitting `less` does not bury your shell output: the alternate screen keeps no
     * history, and leaving it finds the primary screen exactly as it was.
     */
    @Test
    fun `the alternate screen keeps no history and leaves the primary one alone`() {
        val buffer = buffer()
        (0 until 4).forEach { buffer.write(it, "shell$it") }

        buffer.useAlternateScreen(true)
        assertEquals("\n\n\n", buffer.screenText(), "the alternate screen starts blank")
        (0 until 4).forEach { buffer.write(it, "pager$it") }
        buffer.scrollUp(top = 0, bottom = 3, count = 4)
        assertEquals(0, buffer.scrollbackSize, "the alternate screen must not write history")

        buffer.useAlternateScreen(false)
        assertEquals("shell0\nshell1\nshell2\nshell3", buffer.screenText())
    }

    @Test
    fun `the history has a ceiling`() {
        val buffer = buffer(scrollback = 5)
        // Written to the top row and scrolled at once, so each round puts exactly one known line
        // into the history rather than one that is still three scrolls away from it.
        repeat(20) { round ->
            buffer.write(0, "row$round")
            buffer.scrollUp(top = 0, bottom = 3, count = 1)
        }
        assertEquals(5, buffer.scrollbackSize)
        assertEquals("row19", buffer.scrollbackLine(4).text(), "the newest line must be kept")
    }

    @Test
    fun `clearing the screen leaves the history and clearing the history does not`() {
        val buffer = buffer()
        buffer.write(0, "kept")
        buffer.scrollUp(top = 0, bottom = 3, count = 1)
        buffer.write(0, "visible")

        buffer.clearScreen()
        assertEquals("\n\n\n", buffer.screenText())
        assertEquals(1, buffer.scrollbackSize)

        buffer.clearHistory()
        assertEquals(0, buffer.scrollbackSize)
    }

    /** Narrowing rewraps rather than truncating; the halves stay next to each other. */
    @Test
    fun `resizing narrower rewraps and wider rejoins`() {
        val buffer = buffer(columns = 10, rows = 2)
        buffer.write(0, "abcdefghij")

        buffer.resize(columns = 5, rows = 2)
        val halves = (0 until buffer.documentLines).mapNotNull { buffer.documentLine(it)?.text() }
        assertEquals(listOf("abcde", "fghij"), halves.filter { it.isNotEmpty() })

        buffer.resize(columns = 10, rows = 2)
        val rejoined = (0 until buffer.documentLines).mapNotNull { buffer.documentLine(it)?.text() }
        assertTrue("abcdefghij" in rejoined, "widening should put the halves back: $rejoined")
    }

    /**
     * Shrinking keeps the *newest* rows, not the oldest.
     *
     * The prompt is at the bottom of a shell's screen, so a pane that kept the top rows would hide
     * the thing the user is typing into behind whatever scrolled past an hour ago.
     */
    @Test
    fun `shrinking the screen pushes the oldest rows into the history`() {
        val buffer = buffer(columns = 10, rows = 4)
        (0 until 4).forEach { buffer.write(it, "row$it") }

        buffer.resize(columns = 10, rows = 2)

        assertEquals("row2\nrow3", buffer.screenText())
        assertEquals(2, buffer.scrollbackSize)
        assertEquals("row0", buffer.scrollbackLine(0).text())
    }
}
