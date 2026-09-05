package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CSI = 27.toChar() + "["

class MouseEncoderTest {

    private fun modes(vararg set: Int) = Modes().apply { set.forEach { setDec(it, true) } }

    /** A terminal reports the mouse only when a program asked, and never otherwise. */
    @Test
    fun `nothing is reported until a program asks`() {
        assertNull(
            MouseEncoder.encode(
                MouseEventKind.PRESS, MouseButton.LEFT, 0, 0, KeyModifiers.NONE, Modes(),
            ),
        )
    }

    @Test
    fun `the modern encoding names the button and says which way it went`() {
        val modes = modes(Modes.MOUSE_CLICK, Modes.MOUSE_SGR)
        assertEquals(
            "$CSI<0;5;3M",
            MouseEncoder.encode(MouseEventKind.PRESS, MouseButton.LEFT, 4, 2, KeyModifiers.NONE, modes),
        )
        // Lower-case `m` is what makes a release distinguishable, which the old form cannot do.
        assertEquals(
            "$CSI<0;5;3m",
            MouseEncoder.encode(MouseEventKind.RELEASE, MouseButton.LEFT, 4, 2, KeyModifiers.NONE, modes),
        )
        assertEquals(
            "$CSI<64;1;1M",
            MouseEncoder.encode(MouseEventKind.PRESS, MouseButton.WHEEL_UP, 0, 0, KeyModifiers.NONE, modes),
        )
    }

    @Test
    fun `modifiers ride along with the button`() {
        val modes = modes(Modes.MOUSE_CLICK, Modes.MOUSE_SGR)
        assertEquals(
            "$CSI<16;1;1M",
            MouseEncoder.encode(
                MouseEventKind.PRESS, MouseButton.LEFT, 0, 0, KeyModifiers(control = true), modes,
            ),
        )
    }

    /**
     * Reporting more than was asked for is not harmless: a program in click mode reads motion
     * reports as gibberish keystrokes.
     */
    @Test
    fun `motion is only reported to a program that asked for motion`() {
        val click = modes(Modes.MOUSE_CLICK, Modes.MOUSE_SGR)
        val drag = modes(Modes.MOUSE_DRAG, Modes.MOUSE_SGR)
        assertNull(
            MouseEncoder.encode(MouseEventKind.MOVE, MouseButton.LEFT, 1, 1, KeyModifiers.NONE, click),
        )
        assertEquals(
            "$CSI<32;2;2M",
            MouseEncoder.encode(MouseEventKind.MOVE, MouseButton.LEFT, 1, 1, KeyModifiers.NONE, drag),
        )
    }

    /** The original form packs coordinates into one byte each and simply cannot say 300. */
    @Test
    fun `the oldest encoding gives up past the column it cannot express`() {
        val modes = modes(Modes.MOUSE_CLICK)
        // Button 0 at column 1, row 1: three bytes, each its value plus 32 — space, '!', '!'.
        assertEquals(
            "${CSI}M !!",
            MouseEncoder.encode(MouseEventKind.PRESS, MouseButton.LEFT, 0, 0, KeyModifiers.NONE, modes),
        )
        assertNull(
            MouseEncoder.encode(MouseEventKind.PRESS, MouseButton.LEFT, 300, 0, KeyModifiers.NONE, modes),
        )
    }
}

class SelectionTest {

    private fun buffer(): TerminalBuffer = TerminalBuffer(10, 3, 10).apply {
        write(0, "hello")
        write(1, "world")
        write(2, "again")
    }

    private fun TerminalBuffer.write(row: Int, text: String) {
        text.forEachIndexed { column, character -> line(row).set(column, character.code, CellStyle.DEFAULT) }
    }

    @Test
    fun `a selection reads back the text it covers`() {
        val buffer = buffer()
        val text = buffer.textBetween(TerminalPosition(0, 2), TerminalPosition(1, 3))
        assertEquals("llo\nwor", text)
    }

    /**
     * The reason `wrapped` exists: a line that ran off the right edge is half of a longer one, and
     * copying a wrapped path with a newline in the middle of it is the classic terminal annoyance.
     */
    @Test
    fun `wrapped lines are joined rather than broken`() {
        val buffer = TerminalBuffer(5, 2, 10)
        "/very".forEachIndexed { i, c -> buffer.line(0).set(i, c.code, CellStyle.DEFAULT) }
        "/long".forEachIndexed { i, c -> buffer.line(1).set(i, c.code, CellStyle.DEFAULT) }
        buffer.line(0).wrapped = true

        assertEquals(
            "/very/long",
            buffer.textBetween(TerminalPosition(0, 0), TerminalPosition(1, 5)),
        )
    }

    @Test
    fun `dragging backwards selects the same text as dragging forwards`() {
        val selection = Selection()
        selection.start(TerminalPosition(2, 4))
        selection.extendTo(TerminalPosition(1, 1))
        val (from, to) = selection.range()!!
        assertEquals(TerminalPosition(1, 1), from)
        assertEquals(TerminalPosition(2, 4), to)
        assertTrue(selection.contains(1, 3))
        assertTrue(!selection.contains(2, 4), "the far end is exclusive")
    }

    /** History first, then the screen: one index over everything a user can scroll to. */
    @Test
    fun `the document runs from the oldest history line to the last screen row`() {
        val buffer = buffer()
        buffer.scrollUp(0, 2, 1)
        assertEquals(4, buffer.documentLines)
        assertEquals("hello", buffer.documentLine(0)?.text())
        assertEquals("world", buffer.documentLine(1)?.text())
        assertNull(buffer.documentLine(4))
    }
}
