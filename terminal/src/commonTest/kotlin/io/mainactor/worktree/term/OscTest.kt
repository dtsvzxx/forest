package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ESC = 27.toChar().toString()

private class RecordingHost : TerminalHost {
    val sent = mutableListOf<String>()
    var copied: String? = null

    override fun respond(text: String) {
        sent += text
    }

    override fun clipboardWrite(text: String) {
        copied = text
    }
}

class OscTest {

    private val host = RecordingHost()
    private val terminal = TerminalModel(20, 4, scrollback = 10, host = host)

    // ---------------------------------------------------------------- OSC 4, the palette

    @Test
    fun `a program can repaint an entry of its own palette`() {
        terminal.feed("$ESC]4;1;rgb:00/ff/00$ESC\\")
        assertEquals(0x00FF00, terminal.palette.indexed(1))
        // And in the other spelling programs use.
        terminal.feed("$ESC]4;2;#123456$ESC\\")
        assertEquals(0x123456, terminal.palette.indexed(2))
    }

    /** Widths vary: `f`, `ff`, `ffff` all mean the channel is full. */
    @Test
    fun `colour channels are accepted at any width`() {
        terminal.feed("$ESC]4;3;rgb:f/8/0$ESC\\")
        assertEquals(0xFF8800, terminal.palette.indexed(3))
        terminal.feed("$ESC]4;4;rgb:ffff/0000/8080$ESC\\")
        assertEquals(0xFF0080, terminal.palette.indexed(4))
    }

    /** A program that asks what a colour is and hears nothing decides there are no colours. */
    @Test
    fun `a palette query is answered`() {
        terminal.feed("$ESC]4;1;?$ESC\\")
        assertEquals(listOf("$ESC]4;1;rgb:f0f0/5252/4f4f$ESC\\"), host.sent)
    }

    @Test
    fun `the palette can be put back`() {
        terminal.feed("$ESC]4;1;#000000$ESC\\")
        terminal.feed("$ESC]104$ESC\\")
        assertEquals(TerminalPalette.DEFAULT_ANSI[1], terminal.palette.indexed(1))
    }

    /** One pane repainting its colours must not repaint the pane beside it. */
    @Test
    fun `a palette belongs to its own pane`() {
        val other = TerminalModel(20, 4)
        terminal.feed("$ESC]4;1;#000000$ESC\\")
        assertEquals(TerminalPalette.DEFAULT_ANSI[1], other.palette.indexed(1))
    }

    // ---------------------------------------------------------------- OSC 52, the clipboard

    @Test
    fun `a program can put text on the clipboard`() {
        // "hello" in base64.
        terminal.feed("$ESC]52;c;aGVsbG8=$ESC\\")
        assertEquals("hello", host.copied)
    }

    @Test
    fun `text with anything in it survives the round trip`() {
        // "путь/中/🌲" in base64.
        terminal.feed("$ESC]52;c;0L/Rg9GC0Ywv5LitL/CfjLI=$ESC\\")
        assertEquals("путь/中/🌲", host.copied)
    }

    /**
     * The refusal that matters. A terminal that answers a clipboard *read* lets anything able to
     * write to it — a `cat` of a file someone sent you — exfiltrate whatever you last copied.
     */
    @Test
    fun `a program cannot read the clipboard`() {
        terminal.feed("$ESC]52;c;?$ESC\\")
        assertTrue(host.sent.isEmpty(), "the terminal answered a clipboard read: ${host.sent}")
        assertNull(host.copied)
    }

    @Test
    fun `rubbish where the data should be is ignored rather than pasted`() {
        terminal.feed("$ESC]52;c;not valid base64!!$ESC\\")
        assertNull(host.copied)
    }
}
