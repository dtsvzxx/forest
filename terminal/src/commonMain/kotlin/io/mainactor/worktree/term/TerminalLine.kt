package io.mainactor.worktree.term

/**
 * One line of the screen: the code points and how each of them looks.
 *
 * Two parallel arrays rather than an array of cells — see [CellStyle] for why that is the whole
 * performance story of a terminal.
 *
 * [wrapped] is not decoration. It records that this line ran off the end rather than being ended by
 * a newline, which is what tells a selection to join it to the next one without inserting a break,
 * and what a reflow needs to know to rewrap a paragraph rather than a set of unrelated lines.
 */
class TerminalLine(width: Int) {

    var codePoints: IntArray = IntArray(width) { EMPTY }
        private set

    var styles: LongArray = LongArray(width) { CellStyle.DEFAULT }
        private set

    /** True when the text runs on into the line below. */
    var wrapped: Boolean = false

    /**
     * The marks that hang off a cell, for the few cells that have any.
     *
     * A cell holds one code point, which is all almost every cell needs — so this is a map that is
     * usually null rather than a second array that is usually empty. It is what makes an accent
     * written separately from its letter, or a family emoji built out of four people and three
     * joiners, one thing on the screen instead of several.
     */
    private var marks: HashMap<Int, String>? = null

    /**
     * Which hyperlink each cell belongs to, for the few lines that have any.
     *
     * The same reasoning as [marks], and the same shape: an `IntArray` allocated only once a line
     * actually carries a link. Cells hold an id rather than a URI so that a thousand cells of the
     * same link cost a thousand ints, and so that two runs of the same link can be told apart when
     * a program gives them different ids.
     */
    private var links: IntArray? = null

    val width: Int get() = codePoints.size

    operator fun get(column: Int): Int = codePoints[column]

    fun styleAt(column: Int): Long = styles[column]

    fun set(column: Int, codePoint: Int, style: Long) {
        codePoints[column] = codePoint
        styles[column] = style
        // Overwriting a cell drops whatever was hanging off it, or an accent outlives its letter.
        marks?.remove(column)
        links?.set(column, NO_LINK)
    }

    /** What hangs off [column], or null — which is the answer for almost every cell. */
    fun marksAt(column: Int): String? = marks?.get(column)

    /** The hyperlink this cell belongs to, or 0 for the overwhelming majority that do not. */
    fun linkAt(column: Int): Int =
        if (column in 0 until width) links?.get(column) ?: NO_LINK else NO_LINK

    fun setLink(column: Int, link: Int) {
        if (column < 0 || column >= width) return
        if (link == NO_LINK && links == null) return
        val table = links ?: IntArray(width).also { links = it }
        table[column] = link
    }

    /** Attaches a zero-width character to the cell before the cursor. */
    fun appendMark(column: Int, codePoint: Int) {
        if (column < 0 || column >= width) return
        val table = marks ?: HashMap<Int, String>(2).also { marks = it }
        table[column] = (table[column] ?: "") + buildString { appendCodePoint(codePoint) }
    }

    /** Everything at [column], base and marks together, as it should be drawn and copied. */
    fun textAt(column: Int): String = buildString {
        appendCodePoint(codePoints[column])
        marksAt(column)?.let { append(it) }
    }

    /** Blanks [from] until [to] (exclusive), keeping [style]'s background — which `ECH` and `EL` do. */
    fun clear(from: Int = 0, to: Int = width, style: Long = CellStyle.DEFAULT) {
        for (column in from until to) {
            codePoints[column] = EMPTY
            styles[column] = style
            marks?.remove(column)
            links?.set(column, NO_LINK)
        }
        if (from == 0 && to >= width) wrapped = false
    }

    /** `ICH`: pushes the tail right, losing whatever fell off the end. */
    fun insert(at: Int, count: Int, style: Long) {
        if (count <= 0 || at >= width) return
        val moved = width - at - count
        if (moved > 0) {
            codePoints.copyInto(codePoints, at + count, at, at + moved)
            styles.copyInto(styles, at + count, at, at + moved)
        }
        shiftMarks(at, count)
        clear(at, minOf(at + count, width), style)
    }

    /** Marks move with the cells they hang off, or an accent is left behind on someone else. */
    private fun shiftMarks(at: Int, by: Int) {
        val table = marks ?: return
        val moved = HashMap<Int, String>(table.size)
        table.forEach { (column, text) ->
            val destination = if (column >= at) column + by else column
            if (destination in 0 until width) moved[destination] = text
        }
        marks = if (moved.isEmpty()) null else moved
    }

    /** `DCH`: pulls the tail left and blanks what it vacated. */
    fun delete(at: Int, count: Int, style: Long) {
        if (count <= 0 || at >= width) return
        val moved = width - at - count
        if (moved > 0) {
            codePoints.copyInto(codePoints, at, at + count, width)
            styles.copyInto(styles, at, at + count, width)
        }
        shiftMarks(at + count, -count)
        clear(maxOf(at, width - count), width, style)
    }

    /**
     * Grows or truncates in place.
     *
     * This is the crude half of resizing — the one that keeps the cursor's row intact and loses
     * whatever ran past the new width. Rewrapping a paragraph so nothing is lost is a separate
     * problem, and it is not solved here.
     */
    fun resize(newWidth: Int, style: Long) {
        if (newWidth == width) return
        val points = IntArray(newWidth) { EMPTY }
        val cells = LongArray(newWidth) { style }
        val kept = minOf(width, newWidth)
        codePoints.copyInto(points, 0, 0, kept)
        styles.copyInto(cells, 0, 0, kept)
        codePoints = points
        styles = cells
        marks?.keys?.retainAll { it < newWidth }
        links = links?.let { old -> IntArray(newWidth).also { old.copyInto(it, 0, 0, kept) } }
    }

    /** The text of the line, with the trailing blanks dropped — what a selection or a test reads. */
    fun text(): String = buildString {
        var lastUsed = -1
        for (column in 0 until width) if (codePoints[column] != EMPTY) lastUsed = column
        for (column in 0..lastUsed) {
            when (codePoints[column]) {
                EMPTY -> append(' ')
                CONTINUATION -> Unit // the right half of a wide character has no text of its own
                else -> append(textAt(column))
            }
        }
    }

    companion object {
        /** Nothing has been written here; it draws as a space in the current background. */
        const val EMPTY = 0

        /**
         * The right-hand half of a double-width character.
         *
         * The character itself lives in the cell to the left, and this one exists so that the grid
         * stays a grid: a cursor moving by columns still counts it, and the renderer knows to skip
         * it rather than drawing the same glyph twice.
         */
        const val CONTINUATION = -1

        /** No hyperlink, which is what almost every cell has. */
        const val NO_LINK = 0
    }
}

/** `StringBuilder.appendCodePoint` is JVM-only; common code has to surrogate-pair by hand. */
internal fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint <= 0xFFFF) {
        append(codePoint.toChar())
    } else {
        val offset = codePoint - 0x10000
        append((0xD800 + (offset shr 10)).toChar())
        append((0xDC00 + (offset and 0x3FF)).toChar())
    }
}
