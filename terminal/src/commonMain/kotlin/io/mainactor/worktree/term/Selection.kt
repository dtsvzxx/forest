package io.mainactor.worktree.term

/**
 * A place in the whole scrollable document, not on the visible screen.
 *
 * [line] counts from the oldest line of history, so a selection survives the screen scrolling
 * underneath it: a position anchored to a screen row would slide up a line every time a build
 * printed something, which is exactly what a user dragging a selection over live output would see.
 */
data class TerminalPosition(val line: Int, val column: Int) : Comparable<TerminalPosition> {
    override fun compareTo(other: TerminalPosition): Int =
        if (line != other.line) line - other.line else column - other.column
}

/**
 * What the pointer has selected, and how to read it back as text.
 *
 * The joining rule is the whole reason [TerminalLine.wrapped] exists. A line that ran off the right
 * edge is half of a longer line, so copying it must not insert a newline in the middle of a path —
 * a wrapped file name pasted back with a break in it is the classic terminal annoyance, and the
 * flag is what avoids it.
 */
class Selection {

    var anchor: TerminalPosition? = null
        private set

    var head: TerminalPosition? = null
        private set

    val isEmpty: Boolean get() = anchor == null || head == null || anchor == head

    fun start(at: TerminalPosition) {
        anchor = at
        head = at
    }

    fun extendTo(at: TerminalPosition) {
        if (anchor == null) anchor = at
        head = at
    }

    fun clear() {
        anchor = null
        head = null
    }

    /** The pair in reading order, whichever way the pointer was dragged. */
    fun range(): Pair<TerminalPosition, TerminalPosition>? {
        val from = anchor ?: return null
        val to = head ?: return null
        if (from == to) return null
        return if (from <= to) from to to else to to from
    }

    /** True when this cell is inside the selection, which is what the painter asks per cell. */
    fun contains(line: Int, column: Int): Boolean {
        val (from, to) = range() ?: return false
        val position = TerminalPosition(line, column)
        return position >= from && position < to
    }
}

/**
 * Reads a document back as text.
 *
 * Trailing blanks are dropped from every line but the last, because a terminal pads what it draws
 * and nobody wants the padding — and lines that wrapped are joined, because they were never
 * separate lines to begin with.
 */
fun TerminalBuffer.textBetween(from: TerminalPosition, to: TerminalPosition): String = buildString {
    for (line in from.line..to.line) {
        val row = documentLine(line) ?: continue
        val first = if (line == from.line) from.column else 0
        val last = if (line == to.line) to.column else row.width
        append(row.textBetween(first, minOf(last, row.width)))
        // A line that ran off the right edge is half of a longer one; a break here would put a
        // newline into the middle of whatever wrapped.
        if (line != to.line && !row.wrapped) append('\n')
    }
}

/** A line of the whole document: history first, then the screen. */
fun TerminalBuffer.documentLine(index: Int): TerminalLine? = when {
    index < 0 -> null
    index < scrollbackSize -> scrollbackLine(index)
    index < scrollbackSize + rows -> line(index - scrollbackSize)
    else -> null
}

/** How many lines there are to scroll through, history and screen together. */
val TerminalBuffer.documentLines: Int get() = scrollbackSize + rows

private fun TerminalLine.textBetween(from: Int, to: Int): String = buildString {
    var lastUsed = from - 1
    for (column in from until to) if (this@textBetween[column] != TerminalLine.EMPTY) lastUsed = column
    for (column in from..lastUsed) {
        when (this@textBetween[column]) {
            TerminalLine.EMPTY -> append(' ')
            TerminalLine.CONTINUATION -> Unit
            // Whatever hangs off the cell is copied with it: an accent left behind on the clipboard
            // turns a pasted word into a different one.
            else -> append(this@textBetween.textAt(column))
        }
    }
}
