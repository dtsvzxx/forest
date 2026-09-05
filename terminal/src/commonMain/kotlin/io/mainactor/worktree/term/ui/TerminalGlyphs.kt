package io.mainactor.worktree.term.ui

import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface

/**
 * The font a pane is drawn with, and the cell it implies.
 *
 * A terminal is a fixed grid, so the type is never laid out: one advance is measured once, every
 * glyph is placed by arithmetic, and the renderer never asks the shaper where anything goes. That
 * is the difference between drawing sixty rows in a millisecond and in twenty.
 *
 * The families are the same list the JediTerm pane has always probed, in the same order, so
 * switching engines does not change what a pane looks like. Skia's own font manager resolves them
 * rather than AWT's, which keeps the renderer free of the toolkit.
 */
class TerminalGlyphs(
    /** The size a person would name, in the units a font dialog uses. */
    val pointSize: Float = DEFAULT_SIZE,
    val lineSpacing: Float = DEFAULT_LINE_SPACING,
    /**
     * How many pixels a point is on this screen.
     *
     * Skia is told a size in **pixels of the canvas**, while a font size anybody quotes is in
     * points — so on a retina display, handing Skia the point size directly draws everything at
     * half the size it should be. AWT did the conversion invisibly for the Swing pane, which is
     * why the two engines disagreed about how big 13 is until this was passed in.
     */
    val density: Float = 1f,
) {

    private val size = pointSize * density

    private val manager = FontMgr.default

    private val regular: Typeface = resolve(FontStyle.NORMAL)
    private val bold: Typeface = resolve(FontStyle.BOLD)
    private val italic: Typeface = resolve(FontStyle.ITALIC)
    private val boldItalic: Typeface = resolve(FontStyle.BOLD_ITALIC)

    private val regularFont = Font(regular, size)
    private val boldFont = Font(bold, size)
    private val italicFont = Font(italic, size)
    private val boldItalicFont = Font(boldItalic, size)

    /**
     * The width of one cell.
     *
     * Measured from `M` because every family in the list is monospaced, so any character would do —
     * and if one of them is not, `M` is the one that keeps text from overlapping rather than the
     * one that makes it look right.
     */
    val cellWidth: Float = regularFont.measureTextWidth("M")

    private val metrics = regularFont.metrics

    val cellHeight: Float = (metrics.descent - metrics.ascent + metrics.leading) * lineSpacing

    /** Where a glyph's baseline sits inside its cell. */
    val baseline: Float = -metrics.ascent + (cellHeight - (metrics.descent - metrics.ascent)) / 2f

    fun font(bold: Boolean, italic: Boolean): Font = when {
        bold && italic -> boldItalicFont
        bold -> boldFont
        italic -> italicFont
        else -> regularFont
    }

    /**
     * A face that has [codePoint], for the characters a monospaced font does not carry.
     *
     * AWT did this invisibly for the Swing pane; here it is our job, and it is the hidden cost of
     * "emoji and CJK work". Null means nothing on the system has it, and the renderer draws the
     * replacement box rather than nothing at all.
     */
    fun fallbackFor(codePoint: Int, bold: Boolean, italic: Boolean): Font? {
        val face = fallbackCache.getOrPut(codePoint) {
            manager.matchFamilyStyleCharacter(null, style(bold, italic), null, codePoint)
        } ?: return null
        return fontCache.getOrPut(face to size) { Font(face, size) }
    }

    fun hasGlyph(codePoint: Int, bold: Boolean, italic: Boolean): Boolean =
        font(bold, italic).getUTF32Glyph(codePoint) != 0.toShort()

    private fun resolve(style: FontStyle): Typeface =
        FAMILIES.firstNotNullOfOrNull { manager.matchFamilyStyle(it, style) }
            ?: manager.matchFamilyStyle(null, style)
            ?: Typeface.makeEmpty()

    private fun style(bold: Boolean, italic: Boolean): FontStyle = when {
        bold && italic -> FontStyle.BOLD_ITALIC
        bold -> FontStyle.BOLD
        italic -> FontStyle.ITALIC
        else -> FontStyle.NORMAL
    }

    private val fallbackCache = HashMap<Int, Typeface?>()
    private val fontCache = HashMap<Pair<Typeface, Float>, Font>()

    companion object {
        /** The same list, in the same order, the JediTerm pane has always probed. */
        val FAMILIES = listOf(
            "JetBrains Mono", "SF Mono", "Menlo", "DejaVu Sans Mono",
            "Cascadia Mono", "Consolas", "Liberation Mono",
        )

        const val DEFAULT_SIZE = 13f
        const val DEFAULT_LINE_SPACING = 1.05f
    }
}
