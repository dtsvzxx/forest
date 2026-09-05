package io.mainactor.worktree.term

/**
 * What a colour in a cell actually looks like, as `0xRRGGBB`.
 *
 * The sixteen ANSI entries are the IDE console's own (`CONSOLE_*_OUTPUT` in the dark scheme), so a
 * coloured `git` or build log reads the same here as it does in the terminal beside it. The rest is
 * xterm's arithmetic: a 6×6×6 cube and a 24-step grey ramp, which is not a matter of taste — a
 * program computing `16 + 36r + 6g + b` expects exactly those numbers back.
 *
 * A palette is a value, not a global: a program can repaint an entry with `OSC 4`, and a pane that
 * did should not repaint every other pane.
 */
class TerminalPalette(
    private val ansi: IntArray = DEFAULT_ANSI.copyOf(),
    val defaultForeground: Int = DEFAULT_FOREGROUND,
    val defaultBackground: Int = DEFAULT_BACKGROUND,
) {

    /** The RGB behind a 256-colour index. */
    fun indexed(index: Int): Int = when {
        index < 0 -> defaultForeground
        index < ansi.size -> ansi[index]
        index < 232 -> cube(index - 16)
        index < 256 -> grey(index - 232)
        else -> defaultForeground
    }

    /** `OSC 4`: a program repainting one entry of the palette. */
    fun set(index: Int, rgb: Int) {
        if (index in ansi.indices) ansi[index] = rgb and 0xFFFFFF
    }

    fun reset() {
        DEFAULT_ANSI.copyInto(ansi)
    }

    /**
     * Resolves what a cell says into what to draw with.
     *
     * `reverse` is applied here rather than by the renderer because it has to swap *resolved*
     * colours: a cell that is reversed with a default foreground has to become the background's
     * colour, and the renderer does not know what that is.
     */
    fun foregroundOf(style: Long): Int = when (CellStyle.colorKind(CellStyle.foreground(style))) {
        CellStyle.COLOR_DEFAULT -> defaultForeground
        CellStyle.COLOR_INDEXED -> indexed(CellStyle.colorValue(CellStyle.foreground(style)))
        else -> CellStyle.colorValue(CellStyle.foreground(style))
    }

    fun backgroundOf(style: Long): Int = when (CellStyle.colorKind(CellStyle.background(style))) {
        CellStyle.COLOR_DEFAULT -> defaultBackground
        CellStyle.COLOR_INDEXED -> indexed(CellStyle.colorValue(CellStyle.background(style)))
        else -> CellStyle.colorValue(CellStyle.background(style))
    }

    private fun cube(offset: Int): Int {
        val steps = CUBE_STEPS
        return (steps[offset / 36 % 6] shl 16) or (steps[offset / 6 % 6] shl 8) or steps[offset % 6]
    }

    private fun grey(step: Int): Int {
        val level = 8 + step * 10
        return (level shl 16) or (level shl 8) or level
    }

    companion object {
        /** Gray1 in the New UI theme, which is what the editor and the terminal are painted on. */
        const val DEFAULT_BACKGROUND = 0x1E1F22
        const val DEFAULT_FOREGROUND = 0xDFE1E5

        private val CUBE_STEPS = intArrayOf(0, 95, 135, 175, 215, 255)

        val DEFAULT_ANSI = intArrayOf(
            0x000000, // black
            0xF0524F, // red
            0x5C962C, // green
            0xA68A0D, // yellow
            0x3993D4, // blue
            0xA771BF, // magenta
            0x00A3A3, // cyan
            0x808080, // white
            0x595959, // bright black
            0xFF4050, // bright red
            0x4FC414, // bright green
            0xE5BF00, // bright yellow
            0x1FB0FF, // bright blue
            0xED7EED, // bright magenta
            0x00E5E5, // bright cyan
            0xFFFFFF, // bright white
        )
    }
}
