package io.mainactor.worktree.term.ui

import io.mainactor.worktree.term.Selection
import io.mainactor.worktree.term.TerminalModel
import io.mainactor.worktree.term.TerminalPalette
import io.mainactor.worktree.term.TerminalPosition
import io.mainactor.worktree.term.documentLines
import io.mainactor.worktree.term.textBetween
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paints straight onto a Skia surface, with no Compose in the way.
 *
 * Scrolling back and selecting are decided here — which document line a screen row shows, and which
 * cells are highlighted — so this is where they can be checked without synthesising a wheel event.
 */
class TerminalPainterTest {

    private val glyphs = TerminalGlyphs()
    private val palette = TerminalPalette()
    private val painter = TerminalPainter(glyphs, palette)

    private fun modelWithHistory(): TerminalModel {
        val model = TerminalModel(30, 5, scrollback = 100)
        repeat(40) { model.feed("line $it\r\n") }
        return model
    }

    /** Row zero of a pane scrolled back by `n` is `n` lines earlier in the document. */
    /**
     * The bug this pins: a pane whose text was half the size of the one beside it.
     *
     * Skia takes a size in pixels of the canvas and a quoted font size is in points, so on a
     * retina display the two are a factor of two apart. AWT did that conversion invisibly for the
     * Swing pane, which is exactly why the mistake was invisible until the engines were compared.
     */
    @Test
    fun `a cell is twice the size on a screen with twice the pixels`() {
        val single = TerminalGlyphs(pointSize = 13f, density = 1f)
        val double = TerminalGlyphs(pointSize = 13f, density = 2f)

        assertEquals(single.cellWidth * 2, double.cellWidth, 0.01f)
        assertEquals(single.cellHeight * 2, double.cellHeight, 0.01f)
        assertEquals(single.baseline * 2, double.baseline, 0.01f)
    }

    @Test
    fun `scrolling back moves the window over the document, not the screen`() {
        val model = modelWithHistory()
        val atBottom = painter.firstVisibleLine(model, scrollOffset = 0)
        assertEquals(model.buffer.documentLines - model.rows, atBottom)
        assertEquals(atBottom - 7, painter.firstVisibleLine(model, scrollOffset = 7))
    }

    @Test
    fun `a scrolled pane draws the history it scrolled to`() {
        val model = modelWithHistory()
        val surface = Surface.makeRasterN32Premul(400, 100)

        painter.paint(surface.canvas, model, 400f, 100f, showCursor = true, scrollOffset = 10)

        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty())
        File("build/reports/terminal-scrollback.png").apply { parentFile?.mkdirs() }.writeBytes(png)

        // The line the window is over, read from the model rather than from pixels.
        val first = painter.firstVisibleLine(model, scrollOffset = 10)
        assertEquals("line 26", model.buffer.textBetween(TerminalPosition(first, 0), TerminalPosition(first, 30)))
    }

    /**
     * `DECTCEM`. A full-screen program hides the cursor while it repaints, and a terminal that
     * draws one anyway leaves a block flickering in the middle of whatever is being drawn.
     */
    @Test
    fun `a hidden cursor is not drawn`() {
        val escape = 27.toChar().toString()
        fun cursorPixels(input: String): Int {
            val model = TerminalModel(20, 3, scrollback = 10)
            model.feed(input)
            val surface = Surface.makeRasterN32Premul(200, 60)
            painter.paint(surface.canvas, model, 200f, 60f, showCursor = true)
            val pixels = surface.makeImageSnapshot().peekPixels()!!
            val bytes = pixels.buffer.bytes
            var bright = 0
            for (y in 0 until 20) {
                for (x in 0 until 20) {
                    val index = y * pixels.rowBytes + x * 4
                    if ((bytes[index].toInt() and 0xFF) > 0x80) bright++
                }
            }
            return bright
        }
        assertTrue(cursorPixels("") > 50, "a visible cursor should paint the first cell")
        assertEquals(0, cursorPixels(escape + "[?25l"), "a hidden cursor was drawn anyway")
    }

    @Test
    fun `a selection is drawn over the rows it covers`() {
        val model = TerminalModel(30, 4, scrollback = 10)
        model.feed("first line\r\nsecond line\r\nthird line\r\n")
        val selection = Selection().apply {
            start(TerminalPosition(0, 2))
            extendTo(TerminalPosition(1, 6))
        }
        val surface = Surface.makeRasterN32Premul(400, 80)

        painter.paint(
            surface.canvas, model, 400f, 80f,
            showCursor = false, scrollOffset = 0, selection = selection,
        )

        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty())
        File("build/reports/terminal-selection.png").apply { parentFile?.mkdirs() }.writeBytes(png)

        assertEquals(
            "rst line\nsecond",
            model.buffer.textBetween(TerminalPosition(0, 2), TerminalPosition(1, 6)),
        )

        // Read back rather than eyeballed: the highlight has to start where the drag did, and a
        // selection that quietly covers the whole row looks plausible in a screenshot.
        val image = surface.makeImageSnapshot()
        val pixels = image.peekPixels()!!
        // The corner of the cell, not its middle: the middle is where the glyph is, and a sample
        // that lands on the ink reports the text colour however the background was painted.
        fun colourAt(column: Int, row: Int): Int {
            val x = (column * glyphs.cellWidth).toInt() + 1
            val y = (row * glyphs.cellHeight).toInt() + 1
            val index = y * pixels.rowBytes + x * 4
            val bytes = pixels.buffer.bytes
            return ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or (bytes[index + 2].toInt() and 0xFF)
        }
        assertEquals(TerminalPalette.DEFAULT_BACKGROUND, colourAt(0, 0), "column 0 is before the selection")
        assertEquals(TerminalPainter.SELECTION_BACKGROUND, colourAt(4, 0), "column 4 is inside it")
        assertEquals(TerminalPainter.SELECTION_BACKGROUND, colourAt(2, 1), "the second row carries on")
        assertEquals(TerminalPalette.DEFAULT_BACKGROUND, colourAt(8, 1), "past the far end")
        assertEquals(TerminalPalette.DEFAULT_BACKGROUND, colourAt(2, 2), "the row below is untouched")
    }
}
