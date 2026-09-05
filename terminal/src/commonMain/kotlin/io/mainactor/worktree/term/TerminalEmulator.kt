package io.mainactor.worktree.term

/**
 * Everything the emulator has to say to the world outside the grid.
 *
 * Every answer to a query goes through [respond] rather than straight to a file descriptor, which
 * is what lets the whole terminal be driven in a test with no process on the other end — and it is
 * also what makes a conformance suite possible at all, since those work by asking the terminal
 * questions and reading the replies.
 */
/**
 * What a cursor looks like, which `DECSCUSR` lets a program choose.
 *
 * Not decoration: a modal editor sets a bar in insert mode and a block in normal mode, and that is
 * often the only thing on screen saying which mode you are in.
 */
enum class CursorShape { BLOCK, UNDERLINE, BAR }

interface TerminalHost {
    /** Sent back to the program, as if typed. */
    fun respond(text: String) {}

    fun titleChanged(title: String) {}

    fun bell() {}

    /** `OSC 7`: the directory the shell says it is in. */
    fun workingDirectoryChanged(uri: String) {}

    /**
     * `OSC 52`: a program asking for text to be put on the system clipboard.
     *
     * Writing is allowed; reading is not, and that is a deliberate refusal rather than an omission
     * - a sequence that lets anything with a pipe to a terminal *read* the clipboard is a way for a
     * `cat` of a hostile file to exfiltrate whatever you last copied.
     */
    fun clipboardWrite(text: String) {}
}

/**
 * The state machine above the parser: what each sequence *does*.
 *
 * The parser decides that `CSI 2 J` is a control sequence with one parameter and the final `J`;
 * this decides that it clears the screen. Everything here operates on [buffer] and a cursor, and
 * nothing here knows how any of it is drawn.
 *
 * The subtlety that catches every implementation is the **deferred wrap**. Writing into the last
 * column does not move the cursor off the line — it leaves it there with a flag set, and only the
 * *next* character wraps. Without it, a program that fills a line exactly and then writes a
 * carriage return has already scrolled, and every full-width box drawn by a TUI ends up one line
 * lower than it should be.
 */
class TerminalEmulator(
    private val buffer: TerminalBuffer,
    val modes: Modes = Modes(),
    /** This pane's colours: a program repainting one with `OSC 4` must not repaint every pane. */
    val palette: TerminalPalette = TerminalPalette(),
    private val host: TerminalHost = object : TerminalHost {},
) : ParserSink {

    var cursorRow: Int = 0
        private set

    var cursorColumn: Int = 0
        private set

    /** Set after writing into the last column; the next character is what actually wraps. */
    private var pendingWrap = false

    /** What `SGR` has accumulated, and what every character is written with. */
    var style: Long = CellStyle.DEFAULT
        private set

    private var scrollTop = 0
    private var scrollBottom = buffer.rows - 1

    private var tabStops = defaultTabStops(buffer.columns)
    private var lastPrinted = -1

    /** Which of G0..G3 the printable range comes from, and what each of them designates. */
    private val charsets = Charsets()

    /**
     * The hyperlinks a program has opened, by the id the cells carry.
     *
     * Bounded, because a program that opens a link per line of a long build log would otherwise
     * grow this without limit; past the cap new links simply are not links, which is what an
     * ordinary terminal without `OSC 8` does anyway.
     */
    private val links = HashMap<Int, String>()
    private var openLink = TerminalLine.NO_LINK
    private var nextLink = 1

    /** The address behind a cell's link id, or null. */
    fun linkTarget(id: Int): String? = links[id]

    private var savedCursor = SavedCursor()
    private val titleStack = ArrayDeque<String>()

    var title: String = ""
        private set

    var cursorShape: CursorShape = CursorShape.BLOCK
        private set

    var cursorBlinking: Boolean = true
        private set

    private val columns: Int get() = buffer.columns
    private val rows: Int get() = buffer.rows

    private class SavedCursor(
        var row: Int = 0,
        var column: Int = 0,
        var style: Long = CellStyle.DEFAULT,
        var originMode: Boolean = false,
    )

    // ---------------------------------------------------------------- printing

    override fun print(codePoint: Int) {
        val translated = charsets.translate(codePoint)
        val width = CharWidth.of(translated)
        // A zero-width character belongs to the cell before it: an accent written separately from
        // its letter, or the joiners a family emoji is built out of. Dropping them would turn one
        // thing on the screen into several.
        if (width == 0) {
            attachMark(translated)
            return
        }

        if (pendingWrap) wrapLine()
        if (cursorColumn + width > columns) {
            if (modes.autoWrap) wrapLine() else cursorColumn = columns - width
        }
        if (cursorColumn < 0) return

        val line = buffer.line(cursorRow)
        if (modes.insertMode) line.insert(cursorColumn, width, eraseStyle())
        splitWideCharacterAt(line, cursorColumn)
        line.set(cursorColumn, translated, style)
        if (openLink != TerminalLine.NO_LINK) line.setLink(cursorColumn, openLink)
        if (width == 2) {
            splitWideCharacterAt(line, cursorColumn + 1)
            line.set(cursorColumn + 1, TerminalLine.CONTINUATION, style)
            if (openLink != TerminalLine.NO_LINK) line.setLink(cursorColumn + 1, openLink)
        }
        // What `REP` repeats is what was drawn, so it is recorded after translation.
        lastPrinted = translated

        cursorColumn += width
        if (cursorColumn >= columns) {
            cursorColumn = columns - 1
            pendingWrap = modes.autoWrap
        }
    }

    /**
     * Hangs a zero-width character off whatever was written last.
     *
     * "The cell before the cursor" is not simply one to the left: after a double-width character
     * the cursor is two columns along, and the mark belongs to the character, not to its
     * continuation cell.
     */
    private fun attachMark(codePoint: Int) {
        val line = buffer.line(cursorRow)
        var column = cursorColumn - 1
        if (column >= 0 && line[column] == TerminalLine.CONTINUATION) column--
        if (column < 0 || line[column] == TerminalLine.EMPTY) return
        line.appendMark(column, codePoint)
    }

    /**
     * Overwriting half of a double-width character has to remove the other half.
     *
     * Otherwise the orphan stays on the grid: either a continuation cell with nothing to continue,
     * which draws as a hole, or a wide glyph whose second cell now holds someone else's letter.
     */
    private fun splitWideCharacterAt(line: TerminalLine, column: Int) {
        if (column > 0 && line[column] == TerminalLine.CONTINUATION) {
            line.set(column - 1, TerminalLine.EMPTY, line.styleAt(column - 1))
        }
        if (column + 1 < line.width && line[column + 1] == TerminalLine.CONTINUATION) {
            line.set(column + 1, TerminalLine.EMPTY, line.styleAt(column + 1))
        }
    }

    private fun wrapLine() {
        buffer.line(cursorRow).wrapped = true
        cursorColumn = 0
        pendingWrap = false
        index()
    }

    // ---------------------------------------------------------------- controls

    override fun execute(control: Int) {
        when (control) {
            0x07 -> host.bell()
            0x08 -> { // BS
                if (cursorColumn > 0) cursorColumn--
                pendingWrap = false
            }
            0x09 -> tabForward(1)
            0x0A, 0x0B, 0x0C -> { // LF, VT, FF
                index()
                if (modes.newLineMode) cursorColumn = 0
                pendingWrap = false
            }
            0x0D -> { // CR
                cursorColumn = 0
                pendingWrap = false
            }
            0x0E -> charsets.lockShift(1) // SO
            0x0F -> charsets.lockShift(0) // SI
            0x85 -> { // NEL
                cursorColumn = 0
                index()
            }
        }
    }

    /** Down one line, scrolling the region when there is nowhere left to go. */
    private fun index() {
        when {
            cursorRow == scrollBottom -> buffer.scrollUp(scrollTop, scrollBottom, 1, eraseStyle())
            cursorRow < rows - 1 -> cursorRow++
        }
    }

    private fun reverseIndex() {
        when {
            cursorRow == scrollTop -> buffer.scrollDown(scrollTop, scrollBottom, 1, eraseStyle())
            cursorRow > 0 -> cursorRow--
        }
    }

    // ---------------------------------------------------------------- escapes

    override fun escape(intermediates: Int, intermediateCount: Int, final: Int) {
        val first = intermediates and 0xFF
        when {
            intermediateCount == 0 -> when (final.toChar()) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'D' -> index()
                'E' -> { cursorColumn = 0; index() }
                'M' -> reverseIndex()
                'H' -> tabStops[cursorColumn] = true
                'c' -> resetToInitialState()
                '=' -> modes.setDec(Modes.APPLICATION_KEYPAD, true)
                '>' -> modes.setDec(Modes.APPLICATION_KEYPAD, false)
                // Single shifts: the *next* character only, which is the part implementations miss.
                'N' -> charsets.singleShift(2)
                'O' -> charsets.singleShift(3)
                'n' -> charsets.lockShift(2)
                'o' -> charsets.lockShift(3)
                // ST arrives here after an OSC or DCS was handed over; there is nothing left to do.
                '\\' -> Unit
                else -> Unit
            }
            // `ESC # 8`: fill the screen with E, which is how a program checks alignment.
            first == '#'.code && final.toChar() == '8' -> {
                for (row in 0 until rows) {
                    val line = buffer.line(row)
                    for (column in 0 until columns) line.set(column, 'E'.code, CellStyle.DEFAULT)
                }
                setCursor(0, 0)
            }
            // `ESC ( 0` and friends: which set each of G0..G3 designates.
            first in DESIGNATORS -> charsets.designate(DESIGNATORS.indexOf(first), final.toChar())
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- control sequences

    override fun csi(params: Params, intermediates: Int, intermediateCount: Int, private: Int, final: Int) {
        val intermediate = if (intermediateCount > 0) intermediates and 0xFF else 0
        when (private.toChar()) {
            '?' -> decPrivate(params, intermediate, final)
            '>' -> when (final.toChar()) {
                // DA2. Deliberately modest: programs that recognise a specific xterm patch level
                // take paths written for xterm, and we are not xterm.
                'c' -> host.respond("${CSI}>1;0;0c")
                'q' -> if (intermediate == 0) host.respond("${DCS}>|Forest(1.0)$ST")
                else -> Unit
            }
            '=' -> Unit
            else -> ansiCsi(params, intermediate, final)
        }
    }

    private fun ansiCsi(params: Params, intermediate: Int, final: Int) {
        val first = params.at(0, ifAbsent = 1)
        when (final.toChar()) {
            '@' -> buffer.line(cursorRow).insert(cursorColumn, first, eraseStyle())
            'A' -> moveUp(first)
            'B' -> moveDown(first)
            'C' -> moveRight(first)
            'D' -> moveLeft(first)
            'E' -> { moveDown(first); cursorColumn = 0 }
            'F' -> { moveUp(first); cursorColumn = 0 }
            'G', '`' -> setCursor(cursorRowForSet(), first - 1)
            'H', 'f' -> setCursor(params.at(0, ifAbsent = 1) - 1, params.at(1, ifAbsent = 1) - 1)
            'I' -> tabForward(first)
            'J' -> eraseInDisplay(params.at(0, ifAbsent = 0))
            'K' -> eraseInLine(params.at(0, ifAbsent = 0))
            'L' -> insertLines(first)
            'M' -> deleteLines(first)
            'P' -> buffer.line(cursorRow).delete(cursorColumn, first, eraseStyle())
            'S' -> buffer.scrollUp(scrollTop, scrollBottom, first, eraseStyle())
            'T' -> buffer.scrollDown(scrollTop, scrollBottom, first, eraseStyle())
            'X' -> eraseCharacters(first)
            'Z' -> tabBackward(first)
            'a' -> moveRight(first)
            'b' -> repeatLastCharacter(first)
            'c' -> if (params.at(0, ifAbsent = 0) == 0) host.respond("$CSI?62;22c")
            'd' -> setCursor(first - 1, cursorColumn, absoluteRow = false)
            'e' -> moveDown(first)
            'g' -> clearTabStops(params.at(0, ifAbsent = 0))
            'h' -> forEachParam(params) { modes.setAnsi(it, true) }
            'l' -> forEachParam(params) { modes.setAnsi(it, false) }
            'm' -> applyGraphicRendition(params)
            'n' -> deviceStatus(params.at(0, ifAbsent = 0))
            'r' -> setScrollRegion(params.at(0, ifAbsent = 1), params.at(1, ifAbsent = rows))
            // Without left/right margins (which this terminal does not advertise), `CSI s` is what
            // it means everywhere else: save the cursor.
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> windowOperation(params)
            'q' -> if (intermediate == ' '.code) setCursorShape(params.at(0, ifAbsent = 1))
            'p' -> if (intermediate == '$'.code) reportMode(params.at(0, ifAbsent = 0), private = false)
            else -> Unit
        }
    }

    private fun decPrivate(params: Params, intermediate: Int, final: Int) {
        when (final.toChar()) {
            'h' -> forEachParam(params) { setDecMode(it, true) }
            'l' -> forEachParam(params) { setDecMode(it, false) }
            'p' -> if (intermediate == '$'.code) reportMode(params.at(0, ifAbsent = 0), private = true)
            'n' -> if (params.at(0, ifAbsent = 0) == 6) {
                host.respond("$CSI?${reportedRow()};${cursorColumn + 1};1R")
            }
            else -> Unit
        }
    }

    private inline fun forEachParam(params: Params, action: (Int) -> Unit) {
        if (params.isEmpty) return
        for (index in 0 until params.size) action(params.at(index, ifAbsent = 0))
    }

    private fun setDecMode(code: Int, on: Boolean) {
        modes.setDec(code, on)
        when (code) {
            Modes.ORIGIN -> setCursor(0, 0)
            Modes.ALT_SCREEN_OLD, Modes.ALT_SCREEN_CLEAR -> buffer.useAlternateScreen(on, eraseStyle())
            Modes.SAVE_CURSOR -> if (on) saveCursor() else restoreCursor()
            Modes.ALT_SCREEN -> {
                // 1049 is the composite everything uses: save the cursor, switch, clear — and on
                // the way back, switch and restore. That pairing is why leaving `less` puts the
                // prompt back where it was rather than one line down.
                if (on) {
                    saveCursor()
                    buffer.useAlternateScreen(true, eraseStyle())
                    setCursor(0, 0)
                } else {
                    buffer.useAlternateScreen(false, eraseStyle())
                    restoreCursor()
                }
            }
        }
    }

    private fun reportMode(code: Int, private: Boolean) {
        val marker = if (private) "?" else ""
        host.respond("$CSI$marker$code;${modes.report(code, private)}\$y")
    }

    private fun deviceStatus(request: Int) {
        when (request) {
            5 -> host.respond("${CSI}0n")
            6 -> host.respond("$CSI${reportedRow()};${cursorColumn + 1}R")
        }
    }

    /** Origin mode makes a program's idea of "row 1" the top of its scroll region, not the screen. */
    private fun reportedRow(): Int =
        if (modes.originMode) cursorRow - scrollTop + 1 else cursorRow + 1

    private fun windowOperation(params: Params) {
        when (params.at(0, ifAbsent = 0)) {
            18 -> host.respond("${CSI}8;$rows;${columns}t")
            22 -> titleStack.addLast(title)
            23 -> titleStack.removeLastOrNull()?.let { setTitle(it) }
        }
    }

    // ---------------------------------------------------------------- cursor

    /** `DECSCUSR`: the odd numbers blink, the even ones do not, and 0 means "back to default". */
    private fun setCursorShape(request: Int) {
        cursorShape = when (request) {
            3, 4 -> CursorShape.UNDERLINE
            5, 6 -> CursorShape.BAR
            else -> CursorShape.BLOCK
        }
        cursorBlinking = request == 0 || request % 2 == 1
    }

    private fun cursorRowForSet(): Int = if (modes.originMode) cursorRow - scrollTop else cursorRow

    /**
     * Puts the cursor somewhere, in whatever coordinates the program is using.
     *
     * With origin mode on, row 0 is the top of the scroll region and the cursor cannot leave it —
     * which is how a program that owns the middle of the screen keeps from writing over the rest.
     */
    private fun setCursor(row: Int, column: Int, absoluteRow: Boolean = false) {
        cursorRow = when {
            modes.originMode && !absoluteRow -> (row + scrollTop).coerceIn(scrollTop, scrollBottom)
            else -> row.coerceIn(0, rows - 1)
        }
        cursorColumn = column.coerceIn(0, columns - 1)
        pendingWrap = false
    }

    // Movement stops at the scroll region's edge when the cursor is inside it, and at the screen's
    // edge when it is not — a program that parked the cursor outside its region can still get back.
    private fun moveUp(count: Int) {
        val limit = if (cursorRow >= scrollTop) scrollTop else 0
        cursorRow = maxOf(cursorRow - count, limit)
        pendingWrap = false
    }

    private fun moveDown(count: Int) {
        val limit = if (cursorRow <= scrollBottom) scrollBottom else rows - 1
        cursorRow = minOf(cursorRow + count, limit)
        pendingWrap = false
    }

    private fun moveLeft(count: Int) {
        cursorColumn = maxOf(cursorColumn - count, 0)
        pendingWrap = false
    }

    private fun moveRight(count: Int) {
        cursorColumn = minOf(cursorColumn + count, columns - 1)
        pendingWrap = false
    }

    private fun saveCursor() {
        savedCursor = SavedCursor(cursorRow, cursorColumn, style, modes.originMode)
    }

    private fun restoreCursor() {
        style = savedCursor.style
        modes.setDec(Modes.ORIGIN, savedCursor.originMode)
        cursorRow = savedCursor.row.coerceIn(0, rows - 1)
        cursorColumn = savedCursor.column.coerceIn(0, columns - 1)
        pendingWrap = false
    }

    // ---------------------------------------------------------------- erasing

    /**
     * Blank cells keep the current background, which is what `BCE` means.
     *
     * A program that paints a coloured panel and then clears part of it expects the hole to stay
     * the panel's colour; erasing to the terminal's default background puts a white gash in it.
     */
    private fun eraseStyle(): Long =
        CellStyle.withBackground(CellStyle.DEFAULT, CellStyle.background(style))

    private fun eraseInDisplay(mode: Int) {
        val erase = eraseStyle()
        when (mode) {
            0 -> {
                buffer.line(cursorRow).clear(cursorColumn, columns, erase)
                for (row in cursorRow + 1 until rows) buffer.line(row).clear(style = erase)
            }
            1 -> {
                buffer.line(cursorRow).clear(0, minOf(cursorColumn + 1, columns), erase)
                for (row in 0 until cursorRow) buffer.line(row).clear(style = erase)
            }
            2 -> buffer.clearScreen(erase)
            3 -> buffer.clearHistory()
        }
        pendingWrap = false
    }

    private fun eraseInLine(mode: Int) {
        val line = buffer.line(cursorRow)
        val erase = eraseStyle()
        when (mode) {
            0 -> line.clear(cursorColumn, columns, erase)
            1 -> line.clear(0, minOf(cursorColumn + 1, columns), erase)
            2 -> line.clear(style = erase)
        }
        pendingWrap = false
    }

    private fun eraseCharacters(count: Int) {
        val end = minOf(cursorColumn + count, columns)
        buffer.line(cursorRow).clear(cursorColumn, end, eraseStyle())
    }

    // ---------------------------------------------------------------- lines and scrolling

    /** `IL` and `DL` act inside the scroll region, and do nothing when the cursor is outside it. */
    private fun insertLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        buffer.scrollDown(cursorRow, scrollBottom, count, eraseStyle())
        cursorColumn = 0
    }

    private fun deleteLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        buffer.scrollUp(cursorRow, scrollBottom, count, eraseStyle())
        cursorColumn = 0
    }

    private fun setScrollRegion(top: Int, bottom: Int) {
        val newTop = (top - 1).coerceIn(0, rows - 1)
        val newBottom = (bottom - 1).coerceIn(0, rows - 1)
        // A region has to have room for a line; a program asking for an empty one is ignored
        // rather than obeyed, or the next line feed has nowhere to go.
        if (newTop >= newBottom) return
        scrollTop = newTop
        scrollBottom = newBottom
        setCursor(0, 0)
    }

    private fun repeatLastCharacter(count: Int) {
        if (lastPrinted < 0) return
        val character = lastPrinted
        repeat(count) { print(character) }
    }

    // ---------------------------------------------------------------- tabs

    private fun tabForward(count: Int) {
        repeat(count) {
            var column = cursorColumn + 1
            while (column < columns - 1 && !tabStops[column]) column++
            cursorColumn = minOf(column, columns - 1)
        }
        pendingWrap = false
    }

    private fun tabBackward(count: Int) {
        repeat(count) {
            var column = cursorColumn - 1
            while (column > 0 && !tabStops[column]) column--
            cursorColumn = maxOf(column, 0)
        }
        pendingWrap = false
    }

    private fun clearTabStops(mode: Int) {
        when (mode) {
            0 -> tabStops[cursorColumn] = false
            3 -> tabStops.fill(false)
        }
    }

    // ---------------------------------------------------------------- colours and attributes

    /**
     * `SGR`, the sequence that does most of the work in a coloured terminal.
     *
     * Extended colours come in two spellings and both are in the wild: `38;5;n` and `38;2;r;g;b`
     * with semicolons, and `38:5:n` / `38:2::r:g:b` with colons — the colon form carries an
     * optional colour-space id, which is the empty field in the middle that a naive reader offsets
     * itself by.
     */
    private fun applyGraphicRendition(params: Params) {
        if (params.isEmpty) {
            style = CellStyle.DEFAULT
            return
        }
        var index = 0
        while (index < params.size) {
            when (val code = params.at(index, ifAbsent = 0)) {
                0 -> style = CellStyle.DEFAULT
                1 -> style = CellStyle.with(style, CellStyle.BOLD)
                2 -> style = CellStyle.with(style, CellStyle.DIM)
                3 -> style = CellStyle.with(style, CellStyle.ITALIC)
                4 -> style = CellStyle.withUnderline(style, underlineStyleAt(params, index))
                5, 6 -> style = CellStyle.with(style, CellStyle.BLINK)
                7 -> style = CellStyle.with(style, CellStyle.REVERSE)
                8 -> style = CellStyle.with(style, CellStyle.HIDDEN)
                9 -> style = CellStyle.with(style, CellStyle.STRIKETHROUGH)
                21 -> style = CellStyle.withUnderline(style, CellStyle.UNDERLINE_DOUBLE)
                22 -> style = CellStyle.without(CellStyle.without(style, CellStyle.BOLD), CellStyle.DIM)
                23 -> style = CellStyle.without(style, CellStyle.ITALIC)
                24 -> style = CellStyle.withUnderline(style, CellStyle.UNDERLINE_NONE)
                25 -> style = CellStyle.without(style, CellStyle.BLINK)
                27 -> style = CellStyle.without(style, CellStyle.REVERSE)
                28 -> style = CellStyle.without(style, CellStyle.HIDDEN)
                29 -> style = CellStyle.without(style, CellStyle.STRIKETHROUGH)
                in 30..37 -> style = CellStyle.withForeground(style, CellStyle.indexed(code - 30))
                38 -> index = extendedColour(params, index) { style = CellStyle.withForeground(style, it) }
                39 -> style = CellStyle.withForeground(style, CellStyle.COLOR_DEFAULT)
                in 40..47 -> style = CellStyle.withBackground(style, CellStyle.indexed(code - 40))
                48 -> index = extendedColour(params, index) { style = CellStyle.withBackground(style, it) }
                49 -> style = CellStyle.withBackground(style, CellStyle.COLOR_DEFAULT)
                53 -> style = CellStyle.with(style, CellStyle.OVERLINE)
                55 -> style = CellStyle.without(style, CellStyle.OVERLINE)
                // The underline colour, which has nowhere to live in a packed cell. Parsed so the
                // values it carries are consumed rather than read back as more attributes.
                58 -> index = extendedColour(params, index) { }
                59 -> Unit
                in 90..97 -> style = CellStyle.withForeground(style, CellStyle.indexed(code - 90 + 8))
                in 100..107 -> style = CellStyle.withBackground(style, CellStyle.indexed(code - 100 + 8))
            }
            index++
        }
    }

    /** `4:3` is a curly underline; a bare `4` is a plain one. */
    private fun underlineStyleAt(params: Params, index: Int): Int {
        if (index + 1 < params.size && params.continues(index + 1)) {
            return when (params.at(index + 1, ifAbsent = 1)) {
                0 -> CellStyle.UNDERLINE_NONE
                2 -> CellStyle.UNDERLINE_DOUBLE
                3 -> CellStyle.UNDERLINE_CURLY
                4 -> CellStyle.UNDERLINE_DOTTED
                5 -> CellStyle.UNDERLINE_DASHED
                else -> CellStyle.UNDERLINE_SINGLE
            }
        }
        return CellStyle.UNDERLINE_SINGLE
    }

    /**
     * Reads one extended colour and returns the index of its last parameter.
     *
     * The colon form is one parameter with sub-values; the semicolon form is several parameters in
     * a row. Telling them apart is what [Params.continues] is for.
     */
    private inline fun extendedColour(params: Params, at: Int, apply: (Int) -> Unit): Int {
        val joined = at + 1 < params.size && params.continues(at + 1)
        var last = at
        val values = IntArray(6)
        var count = 0
        var index = at + 1
        while (index < params.size && count < values.size) {
            if (joined && !params.continues(index)) break
            values[count++] = params.at(index, ifAbsent = 0)
            last = index
            index++
            if (!joined && count >= neededForKind(values[0])) break
        }
        if (count == 0) return last

        when (values[0]) {
            5 -> if (count >= 2) apply(CellStyle.indexed(values[1]))
            2 -> when {
                // `38:2::r:g:b` — the empty field is the colour space, which nobody sets.
                joined && count >= 5 -> apply(CellStyle.rgb(values[2], values[3], values[4]))
                count >= 4 -> apply(CellStyle.rgb(values[1], values[2], values[3]))
            }
        }
        return last
    }

    private fun neededForKind(kind: Int): Int = when (kind) {
        2 -> 4
        5 -> 2
        else -> 1
    }

    // ---------------------------------------------------------------- strings

    override fun osc(data: String) {
        val separator = data.indexOf(';')
        val command = (if (separator < 0) data else data.substring(0, separator)).toIntOrNull() ?: return
        val payload = if (separator < 0) "" else data.substring(separator + 1)
        when (command) {
            0, 2 -> setTitle(payload)
            1 -> Unit // the icon name, which no window manager we target shows separately
            4 -> setPaletteEntries(payload)
            7 -> host.workingDirectoryChanged(payload)
            8 -> setHyperlink(payload)
            52 -> clipboard(payload)
            104 -> palette.reset()
            else -> Unit
        }
    }

    /**
     * `OSC 4 ; index ; spec`, repeated: a program setting entries of its own palette.
     *
     * A query (`?` in place of the colour) is answered rather than ignored - a program that asks
     * what colour 4 is and hears nothing usually decides the terminal has no colours at all.
     */
    private fun setPaletteEntries(payload: String) {
        val fields = payload.split(';')
        var index = 0
        while (index + 1 < fields.size) {
            val entry = fields[index].toIntOrNull()
            val spec = fields[index + 1]
            if (entry != null) {
                if (spec == "?") {
                    host.respond(Ascii.ESC + "]4;" + entry + ";" + xParseColor(palette.indexed(entry)) + Ascii.ST)
                } else {
                    parseColor(spec)?.let { palette.set(entry, it) }
                }
            }
            index += 2
        }
    }

    /** `rgb:RR/GG/BB`, the form every program asks for one back in. */
    private fun xParseColor(rgb: Int): String {
        fun channel(value: Int) = (value and 0xFF).toString(16).padStart(2, '0').repeat(2)
        return "rgb:" + channel(rgb shr 16) + "/" + channel(rgb shr 8) + "/" + channel(rgb)
    }

    /** Accepts what programs actually write: `rgb:r/g/b` at any width, and `#rrggbb`. */
    private fun parseColor(spec: String): Int? {
        if (spec.startsWith("#")) {
            val digits = spec.drop(1)
            if (digits.length != 6) return null
            return digits.toIntOrNull(16)
        }
        if (!spec.startsWith("rgb:")) return null
        val parts = spec.removePrefix("rgb:").split('/')
        if (parts.size != 3) return null
        val channels = parts.map { part ->
            val value = part.toIntOrNull(16) ?: return null
            // Widths vary - `ff`, `ffff` and `ffffffff` all mean full - so scale to eight bits.
            when (part.length) {
                1 -> value * 17
                2 -> value
                3 -> value shr 4
                4 -> value shr 8
                else -> return null
            }
        }
        return (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    /**
     * `OSC 52 ; <targets> ; <base64>`: put this on the clipboard.
     *
     * A `?` in place of the data is a *read* request, and it is refused. Answering it would let
     * anything that can write to a terminal - a `cat` of a file someone sent you - read back
     * whatever you last copied.
     */
    private fun clipboard(payload: String) {
        val separator = payload.indexOf(';')
        if (separator < 0) return
        val data = payload.substring(separator + 1)
        if (data == "?") return
        decodeBase64(data)?.let { host.clipboardWrite(it) }
    }

    private fun decodeBase64(text: String): String? {
        val bytes = ArrayList<Byte>(text.length * 3 / 4)
        var accumulator = 0
        var bits = 0
        for (character in text) {
            if (character == '=') break
            if (character == '\n' || character == '\r') continue
            val value = BASE64_ALPHABET.indexOf(character)
            if (value < 0) return null
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes += ((accumulator shr bits) and 0xFF).toByte()
            }
        }
        if (bytes.isEmpty()) return null
        return runCatching { bytes.toByteArray().decodeToString() }.getOrNull()
    }

    /**
     * `OSC 8 ; params ; URI`: everything printed from here on belongs to that address.
     *
     * An empty URI closes the link, which is how a program says "the underlined part is over".
     * Nothing is drawn differently by this alone — the cells simply remember where they point, and
     * what the pane does about it is the view's business.
     */
    private fun setHyperlink(payload: String) {
        val separator = payload.indexOf(';')
        val uri = if (separator < 0) "" else payload.substring(separator + 1)
        if (uri.isEmpty()) {
            openLink = TerminalLine.NO_LINK
            return
        }
        if (links.size >= MAX_LINKS) return
        openLink = nextLink++
        links[openLink] = uri
    }

    private fun setTitle(value: String) {
        title = value
        host.titleChanged(value)
    }

    override fun dcsHook(params: Params, intermediates: Int, intermediateCount: Int, final: Int) = Unit

    override fun dcsPut(codePoint: Int) = Unit

    override fun dcsUnhook() = Unit

    // ---------------------------------------------------------------- lifecycle

    fun resize(columns: Int, rows: Int) {
        // The cursor is handed over in document coordinates and comes back in them, because the
        // rewrap moves it: it has to keep the *character* it was on, or a prompt jumps out from
        // under the person typing into it.
        val before = TerminalPosition(buffer.scrollbackSize + cursorRow, cursorColumn)
        val after = buffer.resize(columns, rows, eraseStyle(), before)

        tabStops = defaultTabStops(columns)
        // A program's scroll region describes a screen that no longer exists; xterm drops it, and a
        // program that cared will set it again when it redraws for the new size.
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = (after.line - buffer.scrollbackSize).coerceIn(0, rows - 1)
        cursorColumn = after.column.coerceIn(0, columns - 1)
        pendingWrap = false
    }

    /** `RIS`: everything back to how a pane opens. */
    fun resetToInitialState() {
        modes.reset()
        charsets.reset()
        style = CellStyle.DEFAULT
        buffer.useAlternateScreen(false)
        buffer.clearScreen()
        buffer.clearHistory()
        scrollTop = 0
        scrollBottom = rows - 1
        tabStops = defaultTabStops(columns)
        savedCursor = SavedCursor()
        lastPrinted = -1
        cursorShape = CursorShape.BLOCK
        cursorBlinking = true
        links.clear()
        openLink = TerminalLine.NO_LINK
        setCursor(0, 0)
    }

    private companion object {
        val CSI = Ascii.CSI
        val DCS = Ascii.DCS
        val ST = Ascii.ST

        /** Every eight columns, which is what every terminal has done since the teletype. */
        fun defaultTabStops(columns: Int) = BooleanArray(columns) { it % 8 == 0 && it != 0 }

        /** Enough for any screen anyone reads; a program past this gets a terminal without links. */
        const val MAX_LINKS = 4096

        /** `(`, `)`, `*`, `+` — the intermediates that name G0 through G3. */
        val DESIGNATORS = listOf('('.code, ')'.code, '*'.code, '+'.code)

        const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
}
