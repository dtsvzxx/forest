package io.mainactor.worktree.term

/**
 * The control strings a terminal is made of, built from their codes.
 *
 * Never written as literals. An escape character in source is invisible, so a file that loses one —
 * to an editor, a patch, a tool that normalises whitespace — still compiles and still reads
 * correctly, and every sequence it produces is silently wrong. That happened twice while this
 * module was being written, and both times the failure was a test comparing a sequence against a
 * stripped copy of itself.
 */
internal object Ascii {
    val ESC: String = 27.toChar().toString()

    /** Control Sequence Introducer: the start of almost everything. */
    val CSI: String = ESC + "["

    /** Single Shift 3, which the keypad and the first four function keys use. */
    val SS3: String = ESC + "O"

    /** Device Control String, and the String Terminator that closes one. */
    val DCS: String = ESC + "P"
    val ST: String = ESC + "\\"

    val DEL: String = 127.toChar().toString()
    val BS: String = 8.toChar().toString()
    val TAB: String = 9.toChar().toString()
    val CR: String = 13.toChar().toString()
    val NUL: String = 0.toChar().toString()
}
