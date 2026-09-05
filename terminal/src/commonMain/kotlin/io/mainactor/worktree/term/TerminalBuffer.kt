package io.mainactor.worktree.term

/**
 * The screen, its scrollback, and the alternate screen beside them.
 *
 * Two grids, because that is what a terminal is. The **primary** one is where a shell prints, and
 * what falls off its top is kept: that history is the reason anyone scrolls up. The **alternate**
 * one is what full-screen programs run on — `vim`, `less`, `htop` switch to it, paint, and switch
 * back — and it keeps no history at all, which is exactly why quitting `less` leaves your shell
 * output where it was instead of burying it under a screenful of pager.
 *
 * Nothing here knows about the cursor. Scrolling a region and moving the cursor are separate
 * operations that the emulator combines; keeping them apart is what makes the grid testable by
 * writing into it and reading it back.
 */
class TerminalBuffer(
    columns: Int,
    rows: Int,
    private val maxScrollback: Int = DEFAULT_SCROLLBACK,
) {

    var columns: Int = columns
        private set

    var rows: Int = rows
        private set

    private var primary = MutableList(rows) { TerminalLine(columns) }
    private var alternate = MutableList(rows) { TerminalLine(columns) }

    /**
     * The history, oldest first.
     *
     * An `ArrayDeque` rather than a ring of our own: dropping from the head is what it is for, and
     * the cap is enforced on every push, so the memory a pane holds has a ceiling that does not
     * depend on how much a build printed.
     */
    private val history = ArrayDeque<TerminalLine>()

    var onAlternateScreen: Boolean = false
        private set

    private val lines: MutableList<TerminalLine> get() = if (onAlternateScreen) alternate else primary

    val scrollbackSize: Int get() = history.size

    /** A visible row, 0 at the top. */
    fun line(row: Int): TerminalLine = lines[row]

    /** A line of history, 0 being the oldest still kept. */
    fun scrollbackLine(index: Int): TerminalLine = history[index]

    /**
     * Switches to the alternate screen and back.
     *
     * The alternate screen is cleared on the way in, never on the way out — a program that leaves
     * it expects to find the primary screen exactly as it left it, and that is the whole contract.
     */
    fun useAlternateScreen(on: Boolean, style: Long = CellStyle.DEFAULT) {
        if (on == onAlternateScreen) return
        onAlternateScreen = on
        if (on) alternate.forEach { it.clear(style = style) }
    }

    /**
     * Moves the region up by [count] lines, blanking what comes in at the bottom.
     *
     * Lines leaving the top reach the history only when the region is the whole primary screen.
     * That is not an optimisation: a program that sets a scroll region is managing a pane of its
     * own, and putting its discarded rows into your shell's history would fill the scrollback with
     * the middle of somebody's progress bar.
     */
    fun scrollUp(top: Int, bottom: Int, count: Int, style: Long = CellStyle.DEFAULT) {
        if (count <= 0 || top > bottom) return
        val keepHistory = !onAlternateScreen && top == 0 && bottom == rows - 1
        val moved = minOf(count, bottom - top + 1)
        repeat(moved) {
            val leaving = lines.removeAt(top)
            if (keepHistory) remember(leaving) else leaving.clear(style = style)
            lines.add(bottom, if (keepHistory) TerminalLine(columns) else leaving)
            if (keepHistory) lines[bottom].clear(style = style)
        }
    }

    /** Moves the region down, blanking what comes in at the top. Nothing is ever kept. */
    fun scrollDown(top: Int, bottom: Int, count: Int, style: Long = CellStyle.DEFAULT) {
        if (count <= 0 || top > bottom) return
        val moved = minOf(count, bottom - top + 1)
        repeat(moved) {
            val leaving = lines.removeAt(bottom)
            leaving.clear(style = style)
            leaving.wrapped = false
            lines.add(top, leaving)
        }
    }

    /** Blanks every visible row. History is untouched — `CSI 2 J` clears the screen, not the past. */
    fun clearScreen(style: Long = CellStyle.DEFAULT) {
        lines.forEach { it.clear(style = style) }
    }

    /** `CSI 3 J`: throws the history away as well. */
    fun clearHistory() {
        history.clear()
    }

    /**
     * Changes the size, rewrapping what a narrower pane had to break.
     *
     * The primary screen and its history are rewrapped **together**, because a wrapped line can
     * straddle the boundary between them and rewrapping the two separately would leave a break in
     * the middle of a line that has just become joinable.
     *
     * The alternate screen is not rewrapped, and that is not a shortcut: it belongs to a
     * full-screen program which will redraw it for the new size, and rewrapping it would fight
     * that redraw with a stale copy of the old one.
     *
     * @param cursor where the cursor is in the document, so it can keep the character it is on
     * @return where that character ended up
     */
    fun resize(
        columns: Int,
        rows: Int,
        style: Long = CellStyle.DEFAULT,
        cursor: TerminalPosition = TerminalPosition(scrollbackSize + this.rows - 1, 0),
    ): TerminalPosition {
        require(columns > 0 && rows > 0) { "a terminal cannot be ${columns}x$rows" }
        if (columns == this.columns && rows == this.rows) return cursor

        // Every line the buffer holds is exactly `columns` wide, and everything above it — the
        // painter walking a row, the emulator writing a cell — relies on that without checking.
        // The width is therefore passed to each of the three collections explicitly rather than
        // read from the property, which is only true again at the end of this method.
        alternate.forEach { it.resize(columns, style) }
        alternate = fit(alternate, columns, rows, style)

        val document = history.toMutableList().apply { addAll(primary) }
        // The blank rows below the cursor are padding rather than content, and dropping them
        // before the rewrap is what keeps the extra lines it produces on screen instead of
        // scrolling the top of the document away. They are put back at the end.
        trimPaddingBelow(document, cursor.line, style)
        val result = Reflow.rewrap(document, columns, cursor)
        this.columns = columns
        this.rows = rows

        val lines = result.lines
        val screenFrom = maxOf(lines.size - rows, 0)
        primary = lines.drop(screenFrom).toMutableList()
        while (primary.size < rows) primary.add(TerminalLine(columns).also { it.clear(style = style) })

        history.clear()
        lines.take(screenFrom).forEach { remember(it) }

        // The reflow moved a position in the *primary* document. A program on the alternate screen
        // is somewhere else entirely, and will redraw itself for the new size anyway, so its cursor
        // is only clamped.
        if (onAlternateScreen) {
            return TerminalPosition(
                cursor.line.coerceIn(0, scrollbackSize + rows - 1),
                cursor.column.coerceIn(0, columns - 1),
            )
        }
        return TerminalPosition(result.cursor.line - screenFrom + scrollbackSize, result.cursor.column)
    }

    /**
     * Drops the blank rows a screen carries below its cursor.
     *
     * Never the cursor's own row, and never anything above it: those are where the program is
     * working. Everything below is the terminal's own padding, and counting it as content is what
     * made a rewrap eat the top of the screen.
     */
    private fun trimPaddingBelow(document: MutableList<TerminalLine>, cursorLine: Int, style: Long) {
        if (document.isEmpty()) return
        val cursorIndex = cursorLine.coerceIn(0, document.lastIndex)
        var end = document.size
        while (end > cursorIndex + 1 && document[end - 1].isPadding(style)) end--
        if (end < document.size) document.subList(end, document.size).clear()
    }

    /** Only the alternate screen is fitted this way; it keeps no history to move rows into. */
    private fun fit(
        current: MutableList<TerminalLine>,
        columns: Int,
        rows: Int,
        style: Long,
    ): MutableList<TerminalLine> {
        while (current.size > rows) current.removeAt(current.size - 1)
        while (current.size < rows) current.add(TerminalLine(columns).also { it.clear(style = style) })
        return current
    }

    /**
     * Every line, everywhere, is exactly [columns] wide.
     *
     * The invariant the rest of the terminal is written against, and the one a resize is capable of
     * breaking silently: a screen with a line of the old width in it keeps working until the cursor
     * or the painter reaches past that line's end, which is a crash somewhere else entirely and
     * minutes later. Checked by a test rather than asserted at runtime.
     */
    internal fun everyLineMatchesWidth(): Boolean =
        primary.all { it.width == columns } &&
            alternate.all { it.width == columns } &&
            history.all { it.width == columns }

    private fun remember(line: TerminalLine) {
        history.addLast(line)
        while (history.size > maxScrollback) history.removeFirst()
    }

    /** The visible screen as text, trailing blanks trimmed — how nearly every test reads it. */
    fun screenText(): String = (0 until rows).joinToString("\n") { line(it).text() }

    companion object {
        /** What the JediTerm pane has always kept, so switching engines does not change what you can scroll back to. */
        const val DEFAULT_SCROLLBACK = 10_000
    }
}
