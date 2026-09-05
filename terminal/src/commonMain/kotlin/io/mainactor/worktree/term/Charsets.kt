package io.mainactor.worktree.term

/**
 * The character sets a program can map into the printable range, and the shifts between them.
 *
 * This is how a terminal drew boxes before Unicode, and it is not history: `tmux`, `dialog` and
 * anything built on `ncurses` still reach for it, because a terminfo entry says the terminal has
 * it. A terminal that ignores the designation prints the letters instead of the lines — `qqqq`
 * where a rule was wanted, `lqqk` for a corner — which is the single most recognisable symptom of
 * an emulator that stopped short.
 *
 * Four slots (G0–G3) hold designations; `SI`/`SO` lock one of the first two into the printable
 * range, and `SS2`/`SS3` borrow a slot for exactly one character. The single shift is what catches
 * implementations out: it has to expire after the next character, not after the next escape.
 */
class Charsets {

    /** What each of G0–G3 currently designates. */
    private val slots = charArrayOf(ASCII, ASCII, ASCII, ASCII)

    /** Which slot the printable range maps to now — `SI` and `SO` move this. */
    private var locked = 0

    /** Set by a single shift, and cleared by the very next character. */
    private var shifted: Int? = null

    fun reset() {
        slots.fill(ASCII)
        locked = 0
        shifted = null
    }

    /** `ESC ( B`, `ESC ) 0` and friends. */
    fun designate(slot: Int, charset: Char) {
        if (slot in slots.indices) slots[slot] = charset
    }

    /** `SI` (to G0) and `SO` (to G1). */
    fun lockShift(slot: Int) {
        if (slot in slots.indices) locked = slot
    }

    /** `SS2` and `SS3`: the next character, and only the next one, comes from this slot. */
    fun singleShift(slot: Int) {
        if (slot in slots.indices) shifted = slot
    }

    /**
     * Translates one code point, and expires a pending single shift.
     *
     * Called for every printable character, so it is deliberately a lookup and a range check
     * rather than a map.
     */
    fun translate(codePoint: Int): Int {
        val slot = shifted ?: locked
        shifted = null
        if (slots[slot] != DEC_SPECIAL_GRAPHICS) return codePoint
        if (codePoint < FIRST_GRAPHIC || codePoint > LAST_GRAPHIC) return codePoint
        return GRAPHICS[codePoint - FIRST_GRAPHIC]
    }

    companion object {
        const val ASCII = 'B'
        const val DEC_SPECIAL_GRAPHICS = '0'

        private const val FIRST_GRAPHIC = 0x5F // `_`
        private const val LAST_GRAPHIC = 0x7E // `~`

        /**
         * DEC's line-drawing set, from `_` to `~`.
         *
         * The box-drawing half is what everything still uses; the control pictures and the maths
         * symbols round it out and cost nothing to carry.
         */
        private val GRAPHICS = intArrayOf(
            0x00A0, // _ no-break space
            0x25C6, // ` diamond
            0x2592, // a checkerboard
            0x2409, // b HT symbol
            0x240C, // c FF symbol
            0x240D, // d CR symbol
            0x240A, // e LF symbol
            0x00B0, // f degree
            0x00B1, // g plus-minus
            0x2424, // h NL symbol
            0x240B, // i VT symbol
            0x2518, // j lower right corner
            0x2510, // k upper right corner
            0x250C, // l upper left corner
            0x2514, // m lower left corner
            0x253C, // n crossing lines
            0x23BA, // o horizontal line, scan 1
            0x23BB, // p horizontal line, scan 3
            0x2500, // q horizontal line, scan 5 — the one a rule is made of
            0x23BC, // r horizontal line, scan 7
            0x23BD, // s horizontal line, scan 9
            0x251C, // t left tee
            0x2524, // u right tee
            0x2534, // v bottom tee
            0x252C, // w top tee
            0x2502, // x vertical line
            0x2264, // y less than or equal
            0x2265, // z greater than or equal
            0x03C0, // { pi
            0x2260, // | not equal
            0x00A3, // } sterling
            0x00B7, // ~ centre dot
        )
    }
}
