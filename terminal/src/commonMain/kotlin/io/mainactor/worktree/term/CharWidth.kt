package io.mainactor.worktree.term

/**
 * How many cells a character takes.
 *
 * A terminal is a grid, so this is not a typographic question but an arithmetic one: get it wrong
 * and the cursor and the program's idea of where it is drift apart, which shows up as a prompt that
 * redraws over itself. Every terminal has to answer it the same way the program on the far end
 * does, and the shared reference is `wcwidth` over the Unicode East Asian Width property.
 *
 * **This is a hand-written range table, not the generated one.** It covers what the panes actually
 * see — CJK, the emoji blocks, combining marks and variation selectors — and it is deliberately a
 * table of ranges rather than a guess per character, so replacing it with tables generated from
 * `EastAsianWidth.txt` is a drop-in rather than a rewrite. Until then, expect disagreement with
 * iTerm on the ragged edges of the emoji blocks, which is where every terminal disagrees with
 * every other one anyway.
 */
object CharWidth {

    /** 0 for a mark that hangs off the character before it, 2 for a wide one, otherwise 1. */
    fun of(codePoint: Int): Int = when {
        codePoint < 0x0300 -> 1                    // the whole of Latin and the controls
        isZeroWidth(codePoint) -> 0
        isWide(codePoint) -> 2
        else -> 1
    }

    private fun isZeroWidth(codePoint: Int): Boolean = when (codePoint) {
        in 0x0300..0x036F -> true                  // combining diacritics
        in 0x0483..0x0489 -> true
        in 0x0591..0x05BD, 0x05BF, in 0x05C1..0x05C2, in 0x05C4..0x05C5, 0x05C7 -> true
        in 0x0610..0x061A, in 0x064B..0x065F, 0x0670 -> true
        in 0x06D6..0x06DC, in 0x06DF..0x06E4, in 0x06E7..0x06E8, in 0x06EA..0x06ED -> true
        in 0x0900..0x0903, in 0x093A..0x094F, in 0x0951..0x0957 -> false // Devanagari marks vary
        in 0x0E31..0x0E31, in 0x0E34..0x0E3A, in 0x0E47..0x0E4E -> true
        in 0x200B..0x200F -> true                  // zero-width space and the direction marks
        in 0x202A..0x202E -> true
        in 0x2060..0x2064 -> true
        in 0x20D0..0x20FF -> true                  // combining marks for symbols
        in 0xFE00..0xFE0F -> true                  // variation selectors, including emoji's VS16
        in 0xFE20..0xFE2F -> true
        0xFEFF -> true                             // byte-order mark
        in 0xE0100..0xE01EF -> true                // variation selectors, supplement
        else -> false
    }

    private fun isWide(codePoint: Int): Boolean = when (codePoint) {
        in 0x1100..0x115F -> true                  // Hangul Jamo, initial consonants
        in 0x2E80..0x303E -> true                  // CJK radicals, Kangxi, symbols
        in 0x3041..0x33FF -> true                  // kana, Bopomofo, Hangul compatibility, CJK marks
        in 0x3400..0x4DBF -> true                  // CJK extension A
        in 0x4E00..0x9FFF -> true                  // CJK unified ideographs
        in 0xA000..0xA4CF -> true                  // Yi
        in 0xA960..0xA97F -> true                  // Hangul Jamo extended A
        in 0xAC00..0xD7A3 -> true                  // Hangul syllables
        in 0xF900..0xFAFF -> true                  // CJK compatibility ideographs
        in 0xFE10..0xFE19 -> true                  // vertical forms
        in 0xFE30..0xFE6F -> true                  // CJK compatibility forms
        in 0xFF00..0xFF60 -> true                  // fullwidth forms
        in 0xFFE0..0xFFE6 -> true                  // fullwidth signs
        0x1F004, 0x1F0CF, 0x1F18E -> true          // mahjong tile, joker, AB button
        in 0x1F191..0x1F19A -> true
        in 0x1F200..0x1F320 -> true
        in 0x1F32D..0x1F335 -> true
        in 0x1F337..0x1F37C -> true
        in 0x1F37E..0x1F393 -> true
        in 0x1F3A0..0x1F3CA -> true
        in 0x1F3CF..0x1F3D3 -> true
        in 0x1F3E0..0x1F3F0 -> true
        0x1F3F4, in 0x1F3F8..0x1F43E, 0x1F440 -> true
        in 0x1F442..0x1F4FC -> true
        in 0x1F4FF..0x1F53D -> true
        in 0x1F54B..0x1F54E -> true
        in 0x1F550..0x1F567 -> true
        in 0x1F57A..0x1F57A, in 0x1F595..0x1F596 -> true
        in 0x1F5A4..0x1F5A4, in 0x1F5FB..0x1F64F -> true
        in 0x1F680..0x1F6C5 -> true
        in 0x1F6CC..0x1F6CC, in 0x1F6D0..0x1F6D2 -> true
        in 0x1F6EB..0x1F6EC, in 0x1F6F4..0x1F6FC -> true
        in 0x1F7E0..0x1F7EB -> true
        in 0x1F90C..0x1F9FF -> true                // supplemental symbols, most modern emoji
        in 0x1FA70..0x1FAFF -> true
        in 0x20000..0x2FFFD -> true                // CJK extensions B and beyond
        in 0x30000..0x3FFFD -> true
        else -> false
    }
}
