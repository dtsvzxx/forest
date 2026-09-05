package io.mainactor.worktree.term

/**
 * Rewraps the screen and its history when the pane changes width.
 *
 * Without this, making a window narrower throws away everything past the new edge and making it
 * wider leaves the old breaks in place, so a paragraph of build output ends up ragged for the rest
 * of the session. With it, a line that ran off the right edge is understood for what it is — half
 * of a longer line — and put back together before being broken again in the new place.
 *
 * [TerminalLine.wrapped] is the whole mechanism. It is the only record of the difference between
 * "the program printed a newline here" and "the terminal ran out of room here", and that difference
 * is exactly what may and may not be rejoined.
 *
 * Four rules, each of which a test names:
 *
 * - only the primary buffer is rewrapped; the alternate screen belongs to a full-screen program
 *   that will redraw it for the new size, and rewrapping it would fight that redraw;
 * - the cursor keeps the *character* it was on, not the row and column, or a prompt jumps out from
 *   under the person typing into it;
 * - a double-width character is never split across the new edge;
 * - narrow, wide and narrow again gives back what was there, for any line that was not truncated
 *   the first time.
 */
internal object Reflow {

    class Result(
        val lines: List<TerminalLine>,
        val cursor: TerminalPosition,
    )

    /**
     * Rewraps [lines] to [columns], keeping [cursor] on the character it was on.
     *
     * [lines] is the whole document — history first, then the screen — because a wrapped line can
     * straddle the boundary between them, and rewrapping the two separately would leave a break in
     * the middle of a line that has just become joinable.
     */
    fun rewrap(lines: List<TerminalLine>, columns: Int, cursor: TerminalPosition): Result {
        if (lines.isEmpty()) return Result(lines, cursor)

        val logical = groupIntoLogicalLines(lines)
        val anchor = locate(logical, cursor)

        val rewrapped = mutableListOf<TerminalLine>()
        var newCursor = TerminalPosition(0, 0)
        logical.forEachIndexed { index, group ->
            val start = rewrapped.size
            val produced = wrap(group, columns)
            rewrapped += produced
            if (index == anchor.first) {
                newCursor = positionOf(produced, anchor.second, columns).let { (row, column) ->
                    TerminalPosition(start + row, column)
                }
            }
        }
        return Result(rewrapped, newCursor)
    }

    /** A logical line: the cells of every physical line that a wrap joined, end to end. */
    private class Logical {
        val codePoints = ArrayList<Int>()
        val styles = ArrayList<Long>()

        /** Sparse in practice: almost every entry is null. */
        val marks = ArrayList<String?>()

        /** Where each physical line began, so a cursor on the third of them can be found. */
        val starts = ArrayList<Int>()
    }

    private fun groupIntoLogicalLines(lines: List<TerminalLine>): List<Logical> {
        val groups = ArrayList<Logical>()
        var current = Logical()
        lines.forEachIndexed { index, line ->
            current.starts += current.codePoints.size
            // A line that wrapped is full by definition; only the last of a group has blanks worth
            // dropping, and dropping them is what stops every line becoming full width forever.
            val used = if (line.wrapped) line.width else usedWidth(line)
            for (column in 0 until used) {
                current.codePoints += line[column]
                current.styles += line.styleAt(column)
                current.marks += line.marksAt(column)
            }
            if (!line.wrapped) {
                groups += current
                current = Logical()
            } else if (index == lines.lastIndex) {
                // The document ended mid-wrap, which a program can leave it in.
                groups += current
                current = Logical()
            }
        }
        return groups
    }

    private fun usedWidth(line: TerminalLine): Int {
        var last = -1
        for (column in 0 until line.width) if (line[column] != TerminalLine.EMPTY) last = column
        return last + 1
    }

    /** Which logical line the cursor is in, and how far along it. */
    private fun locate(groups: List<Logical>, cursor: TerminalPosition): Pair<Int, Int> {
        var physical = 0
        groups.forEachIndexed { index, group ->
            val lines = group.starts.size
            if (cursor.line < physical + lines) {
                val within = cursor.line - physical
                return index to (group.starts[within] + cursor.column)
            }
            physical += lines
        }
        return groups.lastIndex.coerceAtLeast(0) to 0
    }

    private fun wrap(group: Logical, columns: Int): List<TerminalLine> {
        val produced = ArrayList<TerminalLine>()
        var line = TerminalLine(columns)
        var column = 0
        var index = 0
        while (index < group.codePoints.size) {
            val codePoint = group.codePoints[index]
            // The continuation cell travels with the character it belongs to, never on its own.
            val width = if (index + 1 < group.codePoints.size &&
                group.codePoints[index + 1] == TerminalLine.CONTINUATION
            ) 2 else 1

            if (column + width > columns) {
                line.wrapped = true
                produced += line
                line = TerminalLine(columns)
                column = 0
            }
            line.set(column, codePoint, group.styles[index])
            group.marks[index]?.forEach { line.appendMark(column, it.code) }
            if (width == 2) line.set(column + 1, TerminalLine.CONTINUATION, group.styles[index])
            column += width
            index += width
        }
        produced += line
        return produced
    }

    /** Where an offset along a logical line lands once it has been broken up again. */
    private fun positionOf(produced: List<TerminalLine>, offset: Int, columns: Int): Pair<Int, Int> {
        var remaining = offset
        produced.forEachIndexed { row, line ->
            val used = if (line.wrapped) columns else columns
            if (remaining < used) return row to remaining
            remaining -= used
        }
        return produced.lastIndex.coerceAtLeast(0) to minOf(remaining, columns - 1)
    }
}
