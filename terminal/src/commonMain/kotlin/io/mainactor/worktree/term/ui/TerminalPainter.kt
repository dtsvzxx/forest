package io.mainactor.worktree.term.ui

import io.mainactor.worktree.term.CellStyle
import io.mainactor.worktree.term.CursorShape
import io.mainactor.worktree.term.Selection
import io.mainactor.worktree.term.TerminalLine
import io.mainactor.worktree.term.TerminalModel
import io.mainactor.worktree.term.TerminalPalette
import io.mainactor.worktree.term.documentLine
import io.mainactor.worktree.term.documentLines
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.TextBlob

/**
 * Draws a screen onto a Skia canvas.
 *
 * The grid is fixed, so nothing here is laid out: every glyph is placed at `column * cellWidth`,
 * and the type system is only asked which glyph, never where. Drawing is in three passes because
 * that is what keeps the number of calls proportional to the *runs* on a row rather than to its
 * cells — a full-screen editor is two to five rectangles and a handful of text blobs per row, not
 * two hundred of each.
 *
 * Reused across frames and not thread-safe: it belongs to one pane, and the caller already holds
 * that pane's lock while the screen is being read.
 */
class TerminalPainter(
    private val glyphs: TerminalGlyphs,
    /** Only a fallback: [paint] uses the palette of the model it is given, which `OSC 4` can repaint. */
    private val defaultPalette: TerminalPalette = TerminalPalette(),
) {

    private var palette: TerminalPalette = defaultPalette

    private val fill = Paint()
    private val text = Paint()

    private val runGlyphs = ShortArray(MAX_RUN)
    private val runPositions = FloatArray(MAX_RUN)

    /**
     * @param scrollOffset how many lines of history are showing above the screen; 0 is the bottom.
     * @param selection highlighted cells, in document coordinates, or null.
     */
    fun paint(
        canvas: Canvas,
        model: TerminalModel,
        width: Float,
        height: Float,
        showCursor: Boolean,
        scrollOffset: Int = 0,
        selection: Selection? = null,
        /** The hyperlink under the pointer, underlined so it looks like one. */
        hoveredLink: Int = TerminalLine.NO_LINK,
    ) {
        palette = model.palette
        fill.color = opaque(palette.defaultBackground)
        canvas.drawRect(Rect(0f, 0f, width, height), fill)

        val cellWidth = glyphs.cellWidth
        val cellHeight = glyphs.cellHeight
        val firstLine = firstVisibleLine(model, scrollOffset)
        for (row in 0 until model.rows) {
            val line = model.buffer.documentLine(firstLine + row) ?: continue
            val top = row * cellHeight
            paintBackgrounds(canvas, line, model.columns, top, cellWidth, cellHeight)
            paintSelection(canvas, selection, firstLine + row, model.columns, top, cellWidth, cellHeight)
            paintText(canvas, line, model.columns, top, cellWidth)
            paintDecorations(canvas, line, model.columns, top, cellWidth, cellHeight)
            paintHoveredLink(canvas, line, model.columns, top, cellWidth, cellHeight, hoveredLink)
        }

        // Three separate reasons not to draw one, and each is a real case: the pane is not focused,
        // it is scrolled back into history where the cursor is not (drawing it on a row of old
        // output would claim the shell is waiting there), or the program asked for it to be hidden
        // — which `DECTCEM` is for, and which a full-screen program does while it repaints.
        if (showCursor && scrollOffset == 0 && model.modes.cursorVisible) {
            paintCursor(canvas, model, cellWidth, cellHeight)
        }
    }

    /** The document index of the top visible row. */
    fun firstVisibleLine(model: TerminalModel, scrollOffset: Int): Int =
        model.buffer.documentLines - model.rows - scrollOffset

    private fun paintSelection(
        canvas: Canvas,
        selection: Selection?,
        line: Int,
        columns: Int,
        top: Float,
        cellWidth: Float,
        cellHeight: Float,
    ) {
        if (selection == null || selection.isEmpty) return
        var column = 0
        while (column < columns) {
            if (!selection.contains(line, column)) {
                column++
                continue
            }
            var end = column + 1
            while (end < columns && selection.contains(line, end)) end++
            fill.color = opaque(SELECTION_BACKGROUND)
            canvas.drawRect(Rect(column * cellWidth, top, end * cellWidth, top + cellHeight), fill)
            column = end
        }
    }

    /** One rectangle per run of equal background, skipping the default one the page is already. */
    private fun paintBackgrounds(
        canvas: Canvas,
        line: TerminalLine,
        columns: Int,
        top: Float,
        cellWidth: Float,
        cellHeight: Float,
    ) {
        var column = 0
        while (column < columns) {
            val colour = backgroundOf(line.styleAt(column))
            var end = column + 1
            while (end < columns && backgroundOf(line.styleAt(end)) == colour) end++
            if (colour != palette.defaultBackground) {
                fill.color = opaque(colour)
                canvas.drawRect(
                    Rect(column * cellWidth, top, end * cellWidth, top + cellHeight),
                    fill,
                )
            }
            column = end
        }
    }

    private fun paintText(canvas: Canvas, line: TerminalLine, columns: Int, top: Float, cellWidth: Float) {
        val baseline = top + glyphs.baseline
        var column = 0
        while (column < columns) {
            val codePoint = line[column]
            if (codePoint == TerminalLine.EMPTY || codePoint == TerminalLine.CONTINUATION) {
                column++
                continue
            }
            val style = line.styleAt(column)
            if (CellStyle.has(style, CellStyle.HIDDEN)) {
                column++
                continue
            }
            val bold = CellStyle.has(style, CellStyle.BOLD)
            val italic = CellStyle.has(style, CellStyle.ITALIC)
            val font = fontFor(codePoint, bold, italic)
            val colour = foregroundOf(style)

            // A cell with marks hanging off it is a cluster, not a glyph: the accent has to be
            // placed against its letter, which is the shaper's job and not arithmetic's.
            val marks = line.marksAt(column)
            if (marks != null) {
                text.color = opaque(colour)
                canvas.drawString(line.textAt(column), column * cellWidth, baseline, font, text)
                column++
                continue
            }

            // A run is as long as the cells agree on everything the draw call carries.
            var count = 0
            var end = column
            while (end < columns && count < MAX_RUN) {
                val point = line[end]
                if (point == TerminalLine.CONTINUATION) {
                    end++
                    continue
                }
                if (point == TerminalLine.EMPTY) break
                if (line.marksAt(end) != null) break
                val cellStyle = line.styleAt(end)
                if (foregroundOf(cellStyle) != colour ||
                    CellStyle.has(cellStyle, CellStyle.BOLD) != bold ||
                    CellStyle.has(cellStyle, CellStyle.ITALIC) != italic ||
                    CellStyle.has(cellStyle, CellStyle.HIDDEN) ||
                    fontFor(point, bold, italic) !== font
                ) {
                    break
                }
                runGlyphs[count] = font.getUTF32Glyph(point)
                runPositions[count] = end * cellWidth
                count++
                end++
            }
            if (count > 0) {
                text.color = opaque(colour)
                if (CellStyle.has(style, CellStyle.DIM)) text.alpha = DIM_ALPHA
                val blob = TextBlob.makeFromPosH(
                    runGlyphs.copyOf(count),
                    runPositions.copyOf(count),
                    baseline,
                    font,
                )
                blob?.use { canvas.drawTextBlob(it, 0f, 0f, text) }
                text.alpha = 255
            }
            column = if (end > column) end else column + 1
        }
    }

    /** Underlines and strikethroughs, which are lines rather than glyphs. */
    private fun paintDecorations(
        canvas: Canvas,
        line: TerminalLine,
        columns: Int,
        top: Float,
        cellWidth: Float,
        cellHeight: Float,
    ) {
        var column = 0
        while (column < columns) {
            val style = line.styleAt(column)
            val underlined = CellStyle.underline(style) != CellStyle.UNDERLINE_NONE
            val struck = CellStyle.has(style, CellStyle.STRIKETHROUGH)
            val overlined = CellStyle.has(style, CellStyle.OVERLINE)
            if (!underlined && !struck && !overlined) {
                column++
                continue
            }
            var end = column + 1
            while (end < columns && line.styleAt(end) == style) end++

            fill.color = opaque(foregroundOf(style))
            val left = column * cellWidth
            val right = end * cellWidth
            if (underlined) rule(canvas, left, right, top + cellHeight - UNDERLINE_INSET)
            if (struck) rule(canvas, left, right, top + cellHeight / 2f)
            if (overlined) rule(canvas, left, right, top + 1f)
            column = end
        }
    }

    /**
     * Underlines the link under the pointer, and only that one.
     *
     * Underlining every link would put a rule under half of a coloured build log; a terminal shows
     * a link when you are about to click it, which is also the only moment it matters.
     */
    private fun paintHoveredLink(
        canvas: Canvas,
        line: TerminalLine,
        columns: Int,
        top: Float,
        cellWidth: Float,
        cellHeight: Float,
        hovered: Int,
    ) {
        if (hovered == TerminalLine.NO_LINK) return
        var column = 0
        while (column < columns) {
            if (line.linkAt(column) != hovered) {
                column++
                continue
            }
            var end = column + 1
            while (end < columns && line.linkAt(end) == hovered) end++
            fill.color = opaque(foregroundOf(line.styleAt(column)))
            rule(canvas, column * cellWidth, end * cellWidth, top + cellHeight - UNDERLINE_INSET)
            column = end
        }
    }

    private fun rule(canvas: Canvas, left: Float, right: Float, y: Float) {
        canvas.drawRect(Rect(left, y, right, y + RULE_THICKNESS), fill)
    }

    private fun paintCursor(canvas: Canvas, model: TerminalModel, cellWidth: Float, cellHeight: Float) {
        val left = model.cursorColumn * cellWidth
        val top = model.cursorRow * cellHeight
        fill.color = opaque(palette.defaultForeground)
        when (model.cursorShape) {
            CursorShape.BLOCK -> canvas.drawRect(Rect(left, top, left + cellWidth, top + cellHeight), fill)
            CursorShape.UNDERLINE ->
                canvas.drawRect(Rect(left, top + cellHeight - CURSOR_THICKNESS, left + cellWidth, top + cellHeight), fill)
            CursorShape.BAR ->
                canvas.drawRect(Rect(left, top, left + CURSOR_THICKNESS, top + cellHeight), fill)
        }
        // Only a block covers the character underneath; the thin shapes sit beside it.
        if (model.cursorShape != CursorShape.BLOCK) return

        // The character under a block cursor is redrawn in the background colour, or it disappears.
        val line = model.buffer.line(model.cursorRow)
        val codePoint = line[model.cursorColumn]
        if (codePoint != TerminalLine.EMPTY && codePoint != TerminalLine.CONTINUATION) {
            val style = line.styleAt(model.cursorColumn)
            val font = fontFor(
                codePoint,
                CellStyle.has(style, CellStyle.BOLD),
                CellStyle.has(style, CellStyle.ITALIC),
            )
            text.color = opaque(palette.defaultBackground)
            val blob = TextBlob.makeFromPosH(
                shortArrayOf(font.getUTF32Glyph(codePoint)),
                floatArrayOf(left),
                top + glyphs.baseline,
                font,
            )
            blob?.use { canvas.drawTextBlob(it, 0f, 0f, text) }
        }
    }

    /**
     * Reverse video is resolved here rather than in the cell.
     *
     * It has to swap what the colours *became*: a reversed cell with a default foreground takes the
     * background's actual colour, and only the palette knows what that is.
     */
    private fun foregroundOf(style: Long): Int =
        if (CellStyle.has(style, CellStyle.REVERSE)) palette.backgroundOf(style)
        else palette.foregroundOf(style)

    private fun backgroundOf(style: Long): Int =
        if (CellStyle.has(style, CellStyle.REVERSE)) palette.foregroundOf(style)
        else palette.backgroundOf(style)

    private fun fontFor(codePoint: Int, bold: Boolean, italic: Boolean): Font =
        if (glyphs.hasGlyph(codePoint, bold, italic)) glyphs.font(bold, italic)
        else glyphs.fallbackFor(codePoint, bold, italic) ?: glyphs.font(bold, italic)

    private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or rgb

    companion object {
        /** Long enough for any row anyone opens; a wider pane simply draws more runs. */
        internal const val MAX_RUN = 1024
        internal const val RULE_THICKNESS = 1f
        internal const val UNDERLINE_INSET = 2f
        internal const val DIM_ALPHA = 140
        internal const val CURSOR_THICKNESS = 2f

        /** The IDE's own selection colour, so a pane matches the editor beside it. */
        const val SELECTION_BACKGROUND = 0x2E436E
    }
}
