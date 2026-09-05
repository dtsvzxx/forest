package io.mainactor.worktree.term

import com.jediterm.core.Color
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Feeds the same bytes to JediTerm and to us, and compares the screens.
 *
 * A free oracle for exactly as long as both engines exist. JediTerm is a **reference, not an
 * authority** — where the two disagree the question is which of them is right, and `xterm` settles
 * it — but a disagreement is always worth looking at, and finding it here costs nothing.
 *
 * Deliberately scaffolding: it goes when JediTerm does, and its dependency is `jvmTest` only, so
 * none of it reaches a shipped bundle or the third-party notices.
 */
class JediTermDifferentialTest {

    private val columns = 80
    private val rows = 24
    private val escape = 27.toChar().toString()
    private val backspace = 8.toChar().toString()

    /** What JediTerm writes into the cell after a double-width character. */
    private val WIDE_PLACEHOLDER = 0xE000.toChar()

    /** The methods a headless JediTerm insists on, none of which this test has any use for. */
    private class HeadlessDisplay : TerminalDisplay {
        override fun setCursor(x: Int, y: Int) = Unit
        override fun setCursorShape(shape: CursorShape?) = Unit
        override fun beep() = Unit
        override fun onResize(size: TermSize, origin: RequestOrigin) = Unit
        override fun scrollArea(top: Int, dy: Int, bottom: Int) = Unit
        override fun setCursorVisible(visible: Boolean) = Unit
        override fun useAlternateScreenBuffer(on: Boolean) = Unit
        override fun getWindowTitle(): String = ""
        override fun setWindowTitle(title: String) = Unit
        override fun getSelection(): TerminalSelection? = null
        override fun terminalMouseModeSet(mode: MouseMode) = Unit
        override fun setMouseFormat(format: MouseFormat) = Unit
        override fun ambiguousCharsAreDoubleWidth(): Boolean = false
        override fun getWindowForeground(): Color = Color(0xDF, 0xE1, 0xE5)
        override fun getWindowBackground(): Color = Color(0x1E, 0x1F, 0x22)
    }

    private fun jeditermScreen(bytes: ByteArray): List<String> {
        val styles = StyleState()
        val buffer = TerminalTextBuffer(columns, rows, styles)
        val terminal = JediTerminal(HeadlessDisplay(), buffer, styles)
        val stream = ArrayTerminalDataStream(bytes.decodeToString().toCharArray())
        val emulator = JediEmulator(stream, terminal)
        while (emulator.hasNext()) {
            val more = runCatching { emulator.next() }
            if (more.isFailure) break
        }
        // JediTerm marks the right half of a wide character with a private-use code point and we
        // leave the cell out of the text entirely. A difference in how a screen is written down,
        // not in what is on it.
        return buffer.getScreenLines().lines().take(rows)
            .map { line -> line.filter { it != WIDE_PLACEHOLDER }.trimEnd() }
    }

    private fun forestScreen(bytes: ByteArray): List<String> {
        val model = TerminalModel(columns, rows, scrollback = 2000)
        model.feed(bytes, bytes.size)
        return (0 until rows).map { model.buffer.line(it).text().trimEnd() }
    }

    /** Reports the first cell that differs, rather than two screens for a human to diff. */
    private fun compare(name: String, bytes: ByteArray) {
        val theirs = jeditermScreen(bytes)
        val ours = forestScreen(bytes)
        for (row in 0 until rows) {
            val mine = ours.getOrElse(row) { "" }
            val other = theirs.getOrElse(row) { "" }
            if (mine == other) continue
            val column = (0..maxOf(mine.length, other.length)).first { index ->
                mine.getOrNull(index) != other.getOrNull(index)
            }
            assertEquals(
                other,
                mine,
                "$name differs at row $row, column $column",
            )
        }
    }

    @Test
    fun `the two emulators agree on what a real program painted`() {
        val fixtures = File("src/jvmTest/resources/term")
        val streams = fixtures.listFiles { file -> file.name.endsWith(".raw") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (streams.isEmpty()) return
        streams
            // Excluded because the two genuinely differ on it, in our favour — see the test below.
            .filterNot { it.name == "colours.raw" }
            .forEach { stream -> compare(stream.name, stream.readBytes()) }
    }

    /**
     * The one place the reference is wrong, and the reason it is a reference rather than an oracle.
     *
     * `CSI 4:3 m` is a curly underline, written with the colon form the 1970s parser table sends
     * straight to its ignore state. JediTerm follows the table and prints the stray `:` onto the
     * screen; we treat a colon as a sub-parameter separator, which is what xterm does and what
     * every program emitting truecolour and underline styles expects. Asserted rather than
     * commented, so a future change to either side has to come and look at this.
     */
    @Test
    fun `a colon in a control sequence is a separator here and a printed character there`() {
        val curly = "${escape}[4:3mcurly${escape}[0m"
        assertEquals("curly", forestScreen(curly.encodeToByteArray()).first())
        assertEquals(":curly", jeditermScreen(curly.encodeToByteArray()).first())
    }

    @Test
    fun `the two emulators agree on the everyday sequences`() {
        val cases = mapOf(
            "wrapping" to "x".repeat(200),
            "cursor" to "$escape[5;10Hhere$escape[1;1Htop",
            "erase" to "one\r\ntwo\r\nthree$escape[2;2H$escape[0J",
            "insert" to "abcdef$escape[1G$escape[3@XYZ",
            "delete" to "abcdef$escape[1G$escape[2P",
            "scroll region" to "$escape[2;5r$escape[5;1Ha\nb\nc\nd",
            "tabs" to "a\tb\tc\td",
            "backspace" to "abc${backspace}X",
            "erase characters" to "abcdef$escape[1G$escape[3X",
            "wide characters" to "中文 mixed with ascii 日本語",
            "reset in the middle" to "before${escape}cafter",
        )
        cases.forEach { (name, input) -> compare(name, input.encodeToByteArray()) }
    }
}
