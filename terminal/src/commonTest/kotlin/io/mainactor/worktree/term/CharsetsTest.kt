package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals

private val ESCAPE = 27.toChar().toString()
private val SHIFT_OUT = 14.toChar().toString()
private val SHIFT_IN = 15.toChar().toString()

/**
 * The set of tests that decide whether `tmux` draws a box or spells one.
 *
 * A terminal that ignores a character-set designation prints `qqqq` where a rule was wanted and
 * `lqqk` for a corner — the single most recognisable symptom of an emulator that stopped short.
 */
class CharsetsTest {

    private fun terminal(columns: Int = 20, rows: Int = 3) = TerminalModel(columns, rows, scrollback = 10)

    @Test
    fun `the line-drawing set turns letters into lines`() {
        val terminal = terminal()
        terminal.feed("$ESCAPE(0lqqk")
        assertEquals("┌──┐", terminal.buffer.line(0).text())
    }

    @Test
    fun `a whole box comes out as a box`() {
        val terminal = terminal(columns = 6, rows = 3)
        terminal.feed("$ESCAPE(0")
        terminal.feed("lqqqqk\r\nx    x\r\nmqqqqj")
        assertEquals("┌────┐\n│    │\n└────┘", terminal.screenText())
    }

    @Test
    fun `switching back to ascii prints letters again`() {
        val terminal = terminal()
        terminal.feed("$ESCAPE(0qqq${ESCAPE}(Bqqq")
        assertEquals("───qqq", terminal.buffer.line(0).text())
    }

    /** `SO` and `SI` move which slot the printable range comes from, without redesignating one. */
    @Test
    fun `shifting between the slots swaps which set is in use`() {
        val terminal = terminal()
        terminal.feed("$ESCAPE)0")          // G1 becomes the line-drawing set
        terminal.feed("abc")                // still G0, which is ASCII
        terminal.feed("${SHIFT_OUT}qqq")    // shift to G1
        terminal.feed("${SHIFT_IN}xyz")     // and back
        assertEquals("abc───xyz", terminal.buffer.line(0).text())
    }

    /**
     * The part implementations get wrong: a single shift expires after one character, not after
     * the next escape sequence.
     */
    @Test
    fun `a single shift lasts exactly one character`() {
        val terminal = terminal()
        terminal.feed("$ESCAPE*0")          // G2 becomes the line-drawing set
        terminal.feed("a${ESCAPE}Nqq")      // SS2, then two `q`s
        assertEquals("a─q", terminal.buffer.line(0).text(), "the second q should be a letter again")
    }

    @Test
    fun `a reset puts the sets back`() {
        val terminal = terminal()
        terminal.feed("$ESCAPE(0qqq")
        terminal.feed("${ESCAPE}c")
        terminal.feed("qqq")
        assertEquals("qqq", terminal.buffer.line(0).text())
    }

    /** What `REP` repeats is what was drawn, not the letter that was sent. */
    @Test
    fun `repeat repeats the character that reached the screen`() {
        val terminal = terminal()
        terminal.feed("$ESCAPE(0q${ESCAPE}[4b")
        assertEquals("─────", terminal.buffer.line(0).text())
    }
}

class CursorShapeTest {

    private fun terminal() = TerminalModel(20, 3, scrollback = 10)

    /**
     * A modal editor sets a bar in insert mode and a block in normal mode, and that is often the
     * only thing on screen saying which mode you are in.
     */
    @Test
    fun `a program can choose the shape of the cursor`() {
        val terminal = terminal()
        assertEquals(CursorShape.BLOCK, terminal.cursorShape)

        terminal.feed("$ESCAPE[5 q")
        assertEquals(CursorShape.BAR, terminal.cursorShape)
        terminal.feed("$ESCAPE[3 q")
        assertEquals(CursorShape.UNDERLINE, terminal.cursorShape)
        terminal.feed("$ESCAPE[2 q")
        assertEquals(CursorShape.BLOCK, terminal.cursorShape)
    }

    /** Odd numbers blink, even ones hold still, and zero means whatever the terminal prefers. */
    @Test
    fun `the shape carries whether it blinks`() {
        val terminal = terminal()
        terminal.feed("$ESCAPE[2 q")
        assertEquals(false, terminal.emulator.cursorBlinking)
        terminal.feed("$ESCAPE[1 q")
        assertEquals(true, terminal.emulator.cursorBlinking)
        terminal.feed("$ESCAPE[0 q")
        assertEquals(true, terminal.emulator.cursorBlinking)
    }
}
