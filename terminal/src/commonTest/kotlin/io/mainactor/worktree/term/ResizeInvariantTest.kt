package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariant a resize is capable of breaking silently.
 *
 * Every line the buffer holds — screen, alternate screen and history — is exactly `columns` wide,
 * and everything above it relies on that without checking: the painter walks a row to
 * `model.columns`, the emulator writes at `cursorColumn` without asking the line how wide it is.
 * A resize that leaves one line at the old width therefore keeps working until something reaches
 * past that line's end, and then throws somewhere else entirely and minutes later.
 *
 * That is not hypothetical. `fit` created its padding lines from the property rather than from the
 * new width, so growing a pane while a full-screen program was running left short lines on the
 * alternate screen; the crash arrived as `Index 348 out of bounds for length 80`, on the reader
 * thread, inside a method that had nothing to do with resizing.
 */
class ResizeInvariantTest {

    private val escape = 27.toChar().toString()

    private fun terminal(columns: Int = 80, rows: Int = 24) =
        TerminalModel(columns, rows, scrollback = 100)

    private fun assertConsistent(terminal: TerminalModel, what: String) {
        assertTrue(
            terminal.buffer.everyLineMatchesWidth(),
            "$what left a line of the wrong width in a ${terminal.columns}-column buffer",
        )
    }

    @Test
    fun `every line matches the width after growing and shrinking`() {
        val terminal = terminal()
        terminal.feed("some output\r\nand more\r\n")

        listOf(120 to 30, 40 to 10, 348 to 60, 20 to 5, 80 to 24).forEach { (columns, rows) ->
            terminal.resize(columns, rows)
            assertConsistent(terminal, "resizing to ${columns}x$rows")
            assertEquals(columns, terminal.columns)
        }
    }

    /** The case that actually crashed: a full-screen program on screen while the pane grows. */
    @Test
    fun `every line matches the width while a program holds the alternate screen`() {
        val terminal = terminal()
        terminal.feed("shell output\r\n")
        terminal.feed("$escape[?1049h")
        terminal.feed("a pager")

        terminal.resize(348, 60)
        assertConsistent(terminal, "growing on the alternate screen")

        // And the program can then write to the far edge of its new screen without falling off it.
        terminal.feed("$escape[60;1H" + "x".repeat(348))
        assertEquals(347, terminal.cursorColumn)

        terminal.resize(80, 24)
        assertConsistent(terminal, "shrinking on the alternate screen")

        terminal.feed("$escape[?1049l")
        assertConsistent(terminal, "leaving the alternate screen")
    }

    /**
     * The shape of the crash, reproduced: output arriving at the old size and a resize arriving
     * between two chunks of it, which is what a pane opening at 80 columns and then being laid out
     * actually does.
     */
    @Test
    fun `output that straddles a resize does not fall off the end of a line`() {
        val terminal = terminal(columns = 80, rows = 24)
        repeat(40) { round ->
            terminal.feed("line $round " + "=".repeat(60) + "\r\n")
            terminal.resize(80 + round * 7, 24)
            terminal.feed("more output that reaches the new right edge " + "#".repeat(40) + "\r\n")
            assertConsistent(terminal, "round $round")
        }
    }

    /** Widths change under a program that is painting a box, which is what `tmux` does constantly. */
    @Test
    fun `a resize between two halves of a wide character leaves the grid consistent`() {
        val terminal = terminal(columns = 20, rows = 4)
        terminal.feed("中文中文中文")
        terminal.resize(9, 4)
        assertConsistent(terminal, "narrowing onto a wide character")
        terminal.feed("more")
        terminal.resize(40, 4)
        assertConsistent(terminal, "widening again")
    }
}
