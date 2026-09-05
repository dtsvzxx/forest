package io.mainactor.worktree.term

/**
 * How one cell looks, packed into a `Long`.
 *
 * A screen is two parallel arrays — one `IntArray` of code points, one `LongArray` of these — and
 * not an array of objects, because the arithmetic is what decides whether a flood survives. A
 * 200×50 screen over ten thousand lines of scrollback is two million cells; as objects that is two
 * million allocations for the garbage collector to walk, and the terminal spends a `yes` flood in
 * the collector rather than in the parser.
 *
 * The layout, from the bottom:
 *
 * ```
 * bits  0..25   foreground: 2 bits of kind, 24 of value
 * bits 26..51   background: the same
 * bits 52..63   attributes: eight flags and a three-bit underline style
 * ```
 *
 * The one thing that does not fit is SGR 58's underline colour, which would need a third 26-bit
 * field. It is rare enough to belong in a side table keyed by cell rather than in every cell, and
 * that table does not exist yet.
 */
object CellStyle {

    /** Default foreground on default background, no attributes — what a cleared cell holds. */
    const val DEFAULT: Long = 0L

    // ---- colours ----------------------------------------------------------------

    /** The terminal's own foreground or background, whatever the theme says that is. */
    const val COLOR_DEFAULT = 0

    /** One of the 256 palette entries: the 16 ANSI colours, the 6×6×6 cube, the grey ramp. */
    const val COLOR_INDEXED = 1

    /** A literal 24-bit colour, from `CSI 38;2;r;g;b m`. */
    const val COLOR_RGB = 2

    fun indexed(index: Int): Int = (COLOR_INDEXED shl 24) or (index and 0xFF)

    fun rgb(red: Int, green: Int, blue: Int): Int =
        (COLOR_RGB shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)

    fun colorKind(color: Int): Int = (color ushr 24) and 0x3
    fun colorValue(color: Int): Int = color and 0xFFFFFF

    // ---- fields -----------------------------------------------------------------

    fun foreground(style: Long): Int = (style and COLOR_MASK).toInt()
    fun background(style: Long): Int = ((style ushr BACKGROUND_SHIFT) and COLOR_MASK).toInt()

    fun withForeground(style: Long, color: Int): Long =
        (style and COLOR_MASK.inv()) or (color.toLong() and COLOR_MASK)

    fun withBackground(style: Long, color: Int): Long =
        (style and (COLOR_MASK shl BACKGROUND_SHIFT).inv()) or
            ((color.toLong() and COLOR_MASK) shl BACKGROUND_SHIFT)

    // ---- attributes -------------------------------------------------------------

    const val BOLD = 1 shl 0
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val BLINK = 1 shl 3
    const val REVERSE = 1 shl 4
    const val HIDDEN = 1 shl 5
    const val STRIKETHROUGH = 1 shl 6
    const val OVERLINE = 1 shl 7

    fun attributes(style: Long): Int = ((style ushr ATTRIBUTES_SHIFT) and 0xFF).toInt()
    fun has(style: Long, attribute: Int): Boolean = attributes(style) and attribute != 0

    fun with(style: Long, attribute: Int): Long =
        style or (attribute.toLong() shl ATTRIBUTES_SHIFT)

    fun without(style: Long, attribute: Int): Long =
        style and (attribute.toLong() shl ATTRIBUTES_SHIFT).inv()

    // ---- underline --------------------------------------------------------------

    const val UNDERLINE_NONE = 0
    const val UNDERLINE_SINGLE = 1
    const val UNDERLINE_DOUBLE = 2
    const val UNDERLINE_CURLY = 3
    const val UNDERLINE_DOTTED = 4
    const val UNDERLINE_DASHED = 5

    fun underline(style: Long): Int = ((style ushr UNDERLINE_SHIFT) and 0x7).toInt()

    fun withUnderline(style: Long, kind: Int): Long =
        (style and (0x7L shl UNDERLINE_SHIFT).inv()) or ((kind.toLong() and 0x7) shl UNDERLINE_SHIFT)

    private const val COLOR_MASK = 0x3FFFFFFL // 26 bits: two of kind, twenty-four of value
    private const val BACKGROUND_SHIFT = 26
    private const val ATTRIBUTES_SHIFT = 52
    private const val UNDERLINE_SHIFT = 60
}
