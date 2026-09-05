package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Collects what the terminal answered, so a query can be asserted on rather than guessed at. */
private class Replies : TerminalHost {
    val sent = mutableListOf<String>()
    val titles = mutableListOf<String>()
    var bells = 0
    var directory: String? = null

    override fun respond(text: String) {
        sent += text
    }

    override fun titleChanged(title: String) {
        titles += title
    }

    override fun bell() {
        bells++
    }

    override fun workingDirectoryChanged(uri: String) {
        directory = uri
    }
}

private val ESC = 27.toChar().toString()
private val CSI = ESC + "["

class TerminalEmulatorTest {

    private fun terminal(columns: Int = 10, rows: Int = 4, host: TerminalHost = object : TerminalHost {}) =
        TerminalModel(columns, rows, scrollback = 100, host = host)

    // ---------------------------------------------------------------- printing and wrapping

    @Test
    fun `text lands on the screen and the cursor follows it`() {
        val terminal = terminal()
        terminal.feed("hello")
        assertEquals("hello\n\n\n", terminal.screenText())
        assertEquals(0, terminal.cursorRow)
        assertEquals(5, terminal.cursorColumn)
    }

    /**
     * The deferred wrap, and the reason every implementation gets it wrong once.
     *
     * Filling a line exactly must leave the cursor on that line. If writing the last column moved
     * the cursor to the next row straight away, a program that fills a line and then writes a
     * carriage return has already scrolled, and every full-width box a TUI draws is one line low.
     */
    @Test
    fun `filling a line exactly does not move to the next one until something else is written`() {
        val terminal = terminal(columns = 5, rows = 3)
        terminal.feed("abcde")
        assertEquals(0, terminal.cursorRow, "writing the last column must not wrap on its own")
        assertEquals(4, terminal.cursorColumn)

        terminal.feed("\r")
        assertEquals(0, terminal.cursorRow, "a carriage return after a full line stays on it")

        terminal.feed("abcde")
        terminal.feed("f")
        assertEquals(1, terminal.cursorRow, "the character after a full line is what wraps")
        assertEquals("abcde\nf\n", terminal.screenText())
    }

    @Test
    fun `wrapping marks the line as continued`() {
        val terminal = terminal(columns = 5, rows = 3)
        terminal.feed("abcdefg")
        assertTrue(terminal.buffer.line(0).wrapped, "a line that ran on must say so")
        assertFalse(terminal.buffer.line(1).wrapped)
    }

    @Test
    fun `with wrapping off the last column is overwritten instead`() {
        val terminal = terminal(columns = 5, rows = 3)
        terminal.feed("${CSI}?7l")
        terminal.feed("abcdefg")
        assertEquals("abcdg\n\n", terminal.screenText())
        assertEquals(0, terminal.cursorRow)
    }

    @Test
    fun `a wide character takes two cells and the cursor skips the second`() {
        val terminal = terminal(columns = 6, rows = 2)
        terminal.feed("中x")
        assertEquals(3, terminal.cursorColumn, "the cursor must be past both halves")
        assertEquals("中x", terminal.buffer.line(0).text())
        assertEquals(TerminalLine.CONTINUATION, terminal.buffer.line(0)[1])
    }

    /** A wide character with one column left has to move to the next line, not be cut in half. */
    @Test
    fun `a wide character will not straddle the right edge`() {
        val terminal = terminal(columns = 5, rows = 3)
        terminal.feed("abcd中")
        assertEquals("abcd\n中\n", terminal.screenText())
    }

    /** Overwriting half of a wide character has to remove the other half, or a ghost is left. */
    @Test
    fun `overwriting half of a wide character removes the other half`() {
        val terminal = terminal(columns = 6, rows = 2)
        terminal.feed("中")
        terminal.feed("${CSI}1G")
        terminal.feed("x")
        assertEquals("x", terminal.buffer.line(0).text())
    }

    // ---------------------------------------------------------------- cursor

    @Test
    fun `the cursor goes where it is put and stops at the edges`() {
        val terminal = terminal(columns = 10, rows = 4)
        terminal.feed("${CSI}3;5H")
        assertEquals(2, terminal.cursorRow)
        assertEquals(4, terminal.cursorColumn)

        terminal.feed("${CSI}99;99H")
        assertEquals(3, terminal.cursorRow)
        assertEquals(9, terminal.cursorColumn)

        terminal.feed("${CSI}H")
        assertEquals(0, terminal.cursorRow)
        assertEquals(0, terminal.cursorColumn)
    }

    /** `CSI ; 5 H` means "row unchanged by default, column 5" — an absent parameter is not a zero. */
    @Test
    fun `an omitted coordinate falls back to its default rather than to zero`() {
        val terminal = terminal()
        terminal.feed("${CSI};5H")
        assertEquals(0, terminal.cursorRow)
        assertEquals(4, terminal.cursorColumn)
    }

    @Test
    fun `saving and restoring brings back the position and the colours`() {
        val terminal = terminal()
        terminal.feed("${CSI}2;3H${CSI}31m")
        terminal.feed("${ESC}7")
        terminal.feed("${CSI}1;1H${CSI}0m")
        terminal.feed("${ESC}8")
        assertEquals(1, terminal.cursorRow)
        assertEquals(2, terminal.cursorColumn)
        assertEquals(CellStyle.indexed(1), CellStyle.foreground(terminal.emulator.style))
    }

    // ---------------------------------------------------------------- erasing

    @Test
    fun `erasing works forwards, backwards and over the whole screen`() {
        val terminal = terminal(columns = 6, rows = 3)
        terminal.feed("abcdef\r\nghijkl\r\nmnopqr")

        terminal.feed("${CSI}2;3H${CSI}0K")
        assertEquals("abcdef\ngh\nmnopqr", terminal.screenText())

        terminal.feed("${CSI}3;3H${CSI}1K")
        assertEquals("abcdef\ngh\n   pqr", terminal.screenText())

        terminal.feed("${CSI}2J")
        assertEquals("\n\n", terminal.screenText())
    }

    /** Background colour erase: a hole in a coloured panel stays the panel's colour. */
    @Test
    fun `erasing keeps the background that was in force`() {
        val terminal = terminal(columns = 6, rows = 2)
        terminal.feed("${CSI}44m")
        terminal.feed("${CSI}2K")
        val expected = CellStyle.withBackground(CellStyle.DEFAULT, CellStyle.indexed(4))
        assertEquals(expected, terminal.buffer.line(0).styleAt(3))
    }

    // ---------------------------------------------------------------- scrolling

    @Test
    fun `a line feed at the bottom scrolls and keeps what fell off`() {
        val terminal = terminal(columns = 6, rows = 3)
        terminal.feed("one\r\ntwo\r\nthree\r\nfour")
        assertEquals("two\nthree\nfour", terminal.screenText())
        assertEquals(1, terminal.buffer.scrollbackSize)
        assertEquals("one", terminal.buffer.scrollbackLine(0).text())
    }

    @Test
    fun `a scroll region confines the scrolling to itself`() {
        val terminal = terminal(columns = 6, rows = 4)
        terminal.feed("a\r\nb\r\nc\r\nd")
        terminal.feed("${CSI}2;3r")   // rows 2 and 3 only
        terminal.feed("${CSI}3;1H")   // the bottom of the region
        terminal.feed("\n")           // and one line past it

        assertEquals("a\nc\n\nd", terminal.screenText())
        assertEquals(0, terminal.buffer.scrollbackSize, "a region's rows are not the shell's history")
    }

    @Test
    fun `an empty scroll region is refused rather than obeyed`() {
        val terminal = terminal(columns = 6, rows = 4)
        terminal.feed("${CSI}3;3r")
        terminal.feed("a\r\nb\r\nc\r\nd\r\ne")
        // The region was ignored, so this scrolled the whole screen as usual.
        assertEquals("b\nc\nd\ne", terminal.screenText())
    }

    @Test
    fun `inserting and deleting lines works inside the region`() {
        val terminal = terminal(columns = 6, rows = 4)
        terminal.feed("a\r\nb\r\nc\r\nd")
        terminal.feed("${CSI}2;1H${CSI}L")
        assertEquals("a\n\nb\nc", terminal.screenText())

        terminal.feed("${CSI}2;1H${CSI}M")
        assertEquals("a\nb\nc\n", terminal.screenText())
    }

    // ---------------------------------------------------------------- attributes

    @Test
    fun `colours arrive in every spelling`() {
        val terminal = terminal()

        terminal.feed("${CSI}31m")
        assertEquals(CellStyle.indexed(1), CellStyle.foreground(terminal.emulator.style))

        terminal.feed("${CSI}91m")
        assertEquals(CellStyle.indexed(9), CellStyle.foreground(terminal.emulator.style))

        terminal.feed("${CSI}38;5;200m")
        assertEquals(CellStyle.indexed(200), CellStyle.foreground(terminal.emulator.style))

        terminal.feed("${CSI}38;2;10;20;30m")
        assertEquals(CellStyle.rgb(10, 20, 30), CellStyle.foreground(terminal.emulator.style))

        // The colon form, with the empty colour-space field a naive reader offsets itself by.
        terminal.feed("${CSI}38:2::40:50:60m")
        assertEquals(CellStyle.rgb(40, 50, 60), CellStyle.foreground(terminal.emulator.style))

        terminal.feed("${CSI}48:5:17m")
        assertEquals(CellStyle.indexed(17), CellStyle.background(terminal.emulator.style))
    }

    @Test
    fun `an extended colour does not swallow what follows it`() {
        val terminal = terminal()
        terminal.feed("${CSI}38;2;1;2;3;1;4m")
        assertEquals(CellStyle.rgb(1, 2, 3), CellStyle.foreground(terminal.emulator.style))
        assertTrue(CellStyle.has(terminal.emulator.style, CellStyle.BOLD), "the bold after the colour was lost")
        assertEquals(CellStyle.UNDERLINE_SINGLE, CellStyle.underline(terminal.emulator.style))
    }

    @Test
    fun `underline styles come from the sub-parameter`() {
        val terminal = terminal()
        terminal.feed("${CSI}4:3m")
        assertEquals(CellStyle.UNDERLINE_CURLY, CellStyle.underline(terminal.emulator.style))
        terminal.feed("${CSI}4m")
        assertEquals(CellStyle.UNDERLINE_SINGLE, CellStyle.underline(terminal.emulator.style))
        terminal.feed("${CSI}24m")
        assertEquals(CellStyle.UNDERLINE_NONE, CellStyle.underline(terminal.emulator.style))
    }

    @Test
    fun `a reset clears everything at once`() {
        val terminal = terminal()
        terminal.feed("${CSI}1;3;4;31;44m")
        terminal.feed("${CSI}m")
        assertEquals(CellStyle.DEFAULT, terminal.emulator.style)
    }

    // ---------------------------------------------------------------- the alternate screen

    @Test
    fun `leaving the alternate screen puts the shell back exactly as it was`() {
        val terminal = terminal(columns = 8, rows = 3)
        terminal.feed("prompt\r\n$ ")
        val before = terminal.screenText()
        val row = terminal.cursorRow
        val column = terminal.cursorColumn

        terminal.feed("${CSI}?1049h")
        terminal.feed("a pager")
        assertTrue("pager" in terminal.screenText())

        terminal.feed("${CSI}?1049l")
        assertEquals(before, terminal.screenText())
        assertEquals(row, terminal.cursorRow)
        assertEquals(column, terminal.cursorColumn)
    }

    // ---------------------------------------------------------------- queries

    @Test
    fun `the terminal answers where the cursor is`() {
        val replies = Replies()
        val terminal = terminal(host = replies)
        terminal.feed("${CSI}2;4H")
        terminal.feed("${CSI}6n")
        assertEquals(listOf("${CSI}2;4R"), replies.sent)
    }

    /**
     * The answer that decides whether an agent's output is drawn as one frame or in pieces you can
     * watch tear: 2 means "supported but off", 0 means "never heard of it".
     */
    @Test
    fun `synchronized output is reported as supported`() {
        val replies = Replies()
        val terminal = terminal(host = replies)
        terminal.feed("${CSI}?2026\$p")
        assertEquals(listOf("${CSI}?2026;2\$y"), replies.sent)

        terminal.feed("${CSI}?2026h")
        replies.sent.clear()
        terminal.feed("${CSI}?2026\$p")
        assertEquals(listOf("${CSI}?2026;1\$y"), replies.sent)
    }

    @Test
    fun `a mode we do not implement is reported as unknown rather than as off`() {
        val replies = Replies()
        val terminal = terminal(host = replies)
        terminal.feed("${CSI}?7777\$p")
        assertEquals(listOf("${CSI}?7777;0\$y"), replies.sent)
    }

    @Test
    fun `the terminal identifies itself as itself`() {
        val replies = Replies()
        val terminal = terminal(host = replies)
        terminal.feed("${CSI}c")
        terminal.feed("${CSI}>c")
        terminal.feed("${CSI}>0q")
        assertEquals(
            listOf("${CSI}?62;22c", "${CSI}>1;0;0c", "${ESC}P>|Forest(1.0)$ESC\\"),
            replies.sent,
        )
    }

    @Test
    fun `the window can be asked how big it is`() {
        val replies = Replies()
        val terminal = terminal(columns = 80, rows = 24, host = replies)
        terminal.feed("${CSI}18t")
        assertEquals(listOf("${CSI}8;24;80t"), replies.sent)
    }

    // ---------------------------------------------------------------- strings and controls

    @Test
    fun `the title arrives through either terminator`() {
        val replies = Replies()
        val terminal = terminal(host = replies)
        terminal.feed("$ESC]0;first")
        terminal.feed("$ESC]2;second$ESC\\")
        assertEquals(listOf("first", "second"), replies.titles)
        assertEquals("second", terminal.title)
    }

    @Test
    fun `the shell can say where it is`() {
        val replies = Replies()
        val terminal = terminal(host = replies)
        terminal.feed("$ESC]7;file://host/tmp/work")
        assertEquals("file://host/tmp/work", replies.directory)
    }

    @Test
    fun `a bell rings without printing anything`() {
        val replies = Replies()
        val terminal = terminal(host = replies)
        terminal.feed("ab")
        assertEquals(1, replies.bells)
        assertEquals("ab\n\n\n", terminal.screenText())
    }

    @Test
    fun `tabs land on every eighth column`() {
        val terminal = terminal(columns = 30, rows = 2)
        terminal.feed("a\tb\tc")
        assertEquals(17, terminal.cursorColumn)
        assertEquals("a       b       c", terminal.buffer.line(0).text())
    }

    @Test
    fun `backspace moves without erasing, which is what a shell relies on`() {
        val terminal = terminal()
        terminal.feed("abcx")
        assertEquals("axc\n\n\n", terminal.screenText())
    }

    @Test
    fun `repeat writes the last character again`() {
        val terminal = terminal(columns = 10, rows = 2)
        terminal.feed("-${CSI}5b")
        assertEquals("------", terminal.buffer.line(0).text())
    }

    @Test
    fun `insert mode pushes what is there to the right`() {
        val terminal = terminal(columns = 8, rows = 2)
        terminal.feed("abcdef")
        terminal.feed("${CSI}1G${CSI}4h")
        terminal.feed("XY")
        assertEquals("XYabcdef", terminal.buffer.line(0).text())
    }

    // ---------------------------------------------------------------- resilience

    @Test
    fun `resizing keeps the cursor on the screen`() {
        val terminal = terminal(columns = 20, rows = 6)
        terminal.feed("${CSI}6;20H")
        terminal.resize(10, 3)
        assertTrue(terminal.cursorRow < 3 && terminal.cursorColumn < 10)
    }

    /**
     * The property the whole parser exists for, checked at the level anyone cares about: whatever a
     * broken program printed, the next prompt has to appear.
     */
    @Test
    fun `a megabyte of random bytes leaves the terminal usable`() {
        val terminal = terminal(columns = 40, rows = 10)
        var seed = 0x5EED
        val noise = ByteArray(1 shl 20) {
            seed = seed * 1103515245 + 12345
            ((seed ushr 16) and 0xFF).toByte()
        }
        terminal.feed(noise)

        terminal.feed("${CSI}0m${CSI}2J${CSI}H")
        terminal.feed("still here")
        assertEquals("still here", terminal.buffer.line(0).text())
        assertTrue(terminal.cursorRow in 0 until terminal.rows)
        assertTrue(terminal.cursorColumn in 0 until terminal.columns)
    }
}
