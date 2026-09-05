package io.mainactor.worktree.term

/**
 * A whole terminal: bytes in at one end, a screen you can read at the other.
 *
 * The three pieces below it each have one job — [Utf8Decoder] turns bytes into code points across
 * chunk boundaries, [EscapeParser] decides what each one is, [TerminalEmulator] decides what it
 * does — and this is what wires them together and hands the result out.
 *
 * **Not thread-safe, on purpose.** A pane reads from its pty on one thread and draws on another,
 * and the lock belongs to whoever owns both; putting one here would make every cell access pay for
 * it. [generation] is the signal a renderer watches: it moves whenever something changed, so a
 * frame can be skipped without comparing the screen.
 */
class TerminalModel(
    columns: Int,
    rows: Int,
    scrollback: Int = TerminalBuffer.DEFAULT_SCROLLBACK,
    host: TerminalHost = object : TerminalHost {},
) {

    val buffer = TerminalBuffer(columns, rows, scrollback)

    /** This pane's own colours, which `OSC 4` lets a program repaint. */
    val palette = TerminalPalette()

    val emulator = TerminalEmulator(buffer, palette = palette, host = host)

    private val decoder = Utf8Decoder()
    private val parser = EscapeParser()
    private val sink = Utf8Decoder.Sink { parser.advance(it, emulator) }

    /** Moves whenever the screen might have changed. Nothing else about the number means anything. */
    var generation: Int = 0
        private set

    val columns: Int get() = buffer.columns
    val rows: Int get() = buffer.rows
    val cursorRow: Int get() = emulator.cursorRow
    val cursorColumn: Int get() = emulator.cursorColumn
    val title: String get() = emulator.title
    val cursorShape: CursorShape get() = emulator.cursorShape
    val modes: Modes get() = emulator.modes

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        if (length <= 0) return
        decoder.decode(bytes, length, sink)
        generation++
    }

    /** For tests, and for anything that has a string rather than what a pty handed over. */
    fun feed(text: String) = feed(text.encodeToByteArray())

    fun resize(columns: Int, rows: Int) {
        if (columns == this.columns && rows == this.rows) return
        emulator.resize(columns, rows)
        generation++
    }

    /** The visible screen as text, trailing blanks trimmed. Almost every test reads this. */
    fun screenText(): String = buffer.screenText()
}
