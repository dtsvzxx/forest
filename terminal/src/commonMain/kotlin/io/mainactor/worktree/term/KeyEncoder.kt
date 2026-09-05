package io.mainactor.worktree.term

/**
 * The keys that are not characters.
 *
 * An enum of our own rather than the toolkit's codes, so the encoding below is common code with
 * tests and the platform only has to say which of these a key event was.
 */
enum class TerminalKey {
    ARROW_UP, ARROW_DOWN, ARROW_RIGHT, ARROW_LEFT,
    HOME, END, PAGE_UP, PAGE_DOWN,
    INSERT, DELETE, BACKSPACE, TAB, ENTER, ESCAPE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

data class KeyModifiers(
    val shift: Boolean = false,
    val alt: Boolean = false,
    val control: Boolean = false,
    val meta: Boolean = false,
) {
    val none: Boolean get() = !shift && !alt && !control && !meta

    /** xterm's modifier parameter: 1 plus a bit per held key, in the order shift, alt, control, meta. */
    internal val code: Int
        get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) +
            (if (control) 4 else 0) + (if (meta) 8 else 0)

    companion object {
        val NONE = KeyModifiers()
    }
}

/**
 * Turns a key press into the bytes a program expects to read.
 *
 * The half of a terminal nobody thinks about until an arrow key types `^[[A` into a shell prompt.
 * Two mode-dependent details do most of the damage when they are missed:
 *
 * - **Application cursor keys** (`DECCKM`). A shell in its line editor sets it, and then an up
 *   arrow has to be `ESC O A` rather than `CSI A`. Send the wrong one and history recall stops
 *   working in `zsh` while it still works in `cat`.
 * - **Modifiers are a parameter, not a different sequence.** `Ctrl+Right` is `CSI 1;5C` — the same
 *   final letter with a parameter — which is why the modifier code has to be inserted rather than
 *   the sequence swapped.
 *
 * `Alt` is sent as an `ESC` prefix, which is what every Unix terminal does and what `readline` and
 * `zsh` expect from `Alt+B` and friends.
 */
object KeyEncoder {

    fun encode(key: TerminalKey, modifiers: KeyModifiers, modes: Modes): String = when (key) {
        TerminalKey.ARROW_UP -> cursor('A', modifiers, modes)
        TerminalKey.ARROW_DOWN -> cursor('B', modifiers, modes)
        TerminalKey.ARROW_RIGHT -> cursor('C', modifiers, modes)
        TerminalKey.ARROW_LEFT -> cursor('D', modifiers, modes)
        TerminalKey.HOME -> cursor('H', modifiers, modes)
        TerminalKey.END -> cursor('F', modifiers, modes)
        TerminalKey.INSERT -> tilde(2, modifiers)
        TerminalKey.DELETE -> tilde(3, modifiers)
        TerminalKey.PAGE_UP -> tilde(5, modifiers)
        TerminalKey.PAGE_DOWN -> tilde(6, modifiers)

        // Backspace sends DEL, not BS. Every Unix terminal has done so for decades, and a terminal
        // that sends BS makes the delete key insert `^H` into everything.
        TerminalKey.BACKSPACE -> prefix(modifiers, if (modifiers.control) Ascii.BS else Ascii.DEL)
        TerminalKey.TAB -> if (modifiers.shift) "${CSI}Z" else prefix(modifiers, Ascii.TAB)
        TerminalKey.ENTER -> prefix(modifiers, Ascii.CR)
        TerminalKey.ESCAPE -> prefix(modifiers, ESC)

        // F1..F4 are the keypad's own sequences; the rest are numbered.
        TerminalKey.F1 -> function('P', modifiers)
        TerminalKey.F2 -> function('Q', modifiers)
        TerminalKey.F3 -> function('R', modifiers)
        TerminalKey.F4 -> function('S', modifiers)
        TerminalKey.F5 -> tilde(15, modifiers)
        TerminalKey.F6 -> tilde(17, modifiers)
        TerminalKey.F7 -> tilde(18, modifiers)
        TerminalKey.F8 -> tilde(19, modifiers)
        TerminalKey.F9 -> tilde(20, modifiers)
        TerminalKey.F10 -> tilde(21, modifiers)
        TerminalKey.F11 -> tilde(23, modifiers)
        TerminalKey.F12 -> tilde(24, modifiers)
    }

    /**
     * A character key, once the toolkit has worked out which character it is.
     *
     * Control turns a letter into the control code it names — `Ctrl+C` is 0x03 — because that is
     * literally what the caret notation means, and it is how every signal reaches a program.
     */
    fun encodeCharacter(codePoint: Int, modifiers: KeyModifiers): String? {
        if (modifiers.meta) return null // ⌘ belongs to the application, never to the pane
        val text = when {
            // Some toolkits report `Ctrl+C` as the letter and some as the byte it names. Either is
            // fine as long as both arrive as the byte, and a terminal that guesses wrong sends
            // nothing at all for the one chord users press most.
            codePoint in 0x01..0x1F -> codePoint.toChar().toString()
            modifiers.control -> controlCode(codePoint) ?: return null
            else -> buildString { appendCodePoint(codePoint) }
        }
        return prefix(modifiers, text)
    }

    /**
     * Wraps pasted text so a program can tell it from typing.
     *
     * Without this, pasting into an editor runs every newline as a command and every character
     * through the auto-indent — the reason bracketed paste exists at all.
     */
    fun paste(text: String, modes: Modes): String {
        val cleaned = text.replace("\r\n", "\r").replace('\n', '\r')
        return if (modes.bracketedPaste) "${CSI}200~$cleaned${CSI}201~" else cleaned
    }

    private fun controlCode(codePoint: Int): String? {
        val upper = codePoint.toChar().uppercaseChar()
        return when {
            upper in 'A'..'Z' -> (upper - 'A' + 1).toChar().toString()
            upper == ' ' || upper == '@' -> Ascii.NUL
            upper == '[' -> Ascii.ESC
            upper == '\\' -> ""
            upper == ']' -> 29.toChar().toString()
            upper == '^' -> 30.toChar().toString()
            upper == '_' || upper == '?' -> 31.toChar().toString()
            else -> null
        }
    }

    private fun cursor(final: Char, modifiers: KeyModifiers, modes: Modes): String = when {
        !modifiers.none -> "${CSI}1;${modifiers.code}$final"
        modes.applicationCursorKeys -> "${SS3}$final"
        else -> "$CSI$final"
    }

    private fun function(final: Char, modifiers: KeyModifiers): String =
        if (modifiers.none) "${SS3}$final" else "${CSI}1;${modifiers.code}$final"

    private fun tilde(number: Int, modifiers: KeyModifiers): String =
        if (modifiers.none) "$CSI$number~" else "$CSI$number;${modifiers.code}~"

    /** `Alt` is an `ESC` before whatever the key would otherwise have sent. */
    private fun prefix(modifiers: KeyModifiers, text: String): String =
        if (modifiers.alt) "$ESC$text" else text

    private val ESC = Ascii.ESC
    private val CSI = Ascii.CSI
    private val SS3 = Ascii.SS3
}
