package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The control characters are built from their codes rather than written as literals.
 *
 * A test whose expected value is an invisible byte is one nobody can read and one that anything
 * touching the file can silently mangle — which is exactly how this file failed the first time it
 * was written, with every assertion comparing an escape sequence against a stripped copy of itself.
 */
private val ESC = 27.toChar().toString()
private val CSI = ESC + "["
private val SS3 = ESC + "O"
private val DEL = 127.toChar().toString()
private val BS = 8.toChar().toString()
private val NUL = 0.toChar().toString()

/** The byte `Ctrl+<letter>` actually sends: the caret notation is not a metaphor. */
private fun ctrl(letter: Char): String = (letter.uppercaseChar() - 'A' + 1).toChar().toString()

class KeyEncoderTest {

    private val plain = Modes()
    private val application = Modes().apply { setDec(Modes.APPLICATION_CURSOR_KEYS, true) }
    private val bracketing = Modes().apply { setDec(Modes.BRACKETED_PASTE, true) }

    private fun key(key: TerminalKey, modifiers: KeyModifiers = KeyModifiers.NONE, modes: Modes = plain) =
        KeyEncoder.encode(key, modifiers, modes)

    /**
     * The mode that breaks history recall when it is missed: a shell's line editor turns it on, and
     * an up arrow then has to be `ESC O A`. Send `CSI A` and `zsh` stops recalling while `cat`
     * still shows an arrow working — a bug report that reads as nonsense.
     */
    @Test
    fun `arrow keys change shape with application cursor mode`() {
        assertEquals("${CSI}A", key(TerminalKey.ARROW_UP))
        assertEquals("${SS3}A", key(TerminalKey.ARROW_UP, modes = application))
        assertEquals("${CSI}D", key(TerminalKey.ARROW_LEFT))
        assertEquals("${SS3}D", key(TerminalKey.ARROW_LEFT, modes = application))
    }

    /** A modifier is a parameter on the same sequence, not a different sequence. */
    @Test
    fun `modifiers are carried as a parameter`() {
        assertEquals("${CSI}1;5C", key(TerminalKey.ARROW_RIGHT, KeyModifiers(control = true)))
        assertEquals("${CSI}1;2D", key(TerminalKey.ARROW_LEFT, KeyModifiers(shift = true)))
        assertEquals("${CSI}1;3A", key(TerminalKey.ARROW_UP, KeyModifiers(alt = true)))
        // Even in application mode: a modified arrow goes back to the CSI form.
        assertEquals("${CSI}1;5A", key(TerminalKey.ARROW_UP, KeyModifiers(control = true), application))
        assertEquals("${CSI}5;5~", key(TerminalKey.PAGE_UP, KeyModifiers(control = true)))
    }

    @Test
    fun `the editing keys send their numbered sequences`() {
        assertEquals("${CSI}2~", key(TerminalKey.INSERT))
        assertEquals("${CSI}3~", key(TerminalKey.DELETE))
        assertEquals("${CSI}5~", key(TerminalKey.PAGE_UP))
        assertEquals("${CSI}6~", key(TerminalKey.PAGE_DOWN))
        assertEquals("${CSI}H", key(TerminalKey.HOME))
        assertEquals("${CSI}F", key(TerminalKey.END))
    }

    @Test
    fun `function keys use the keypad forms for the first four`() {
        assertEquals("${SS3}P", key(TerminalKey.F1))
        assertEquals("${SS3}S", key(TerminalKey.F4))
        assertEquals("${CSI}15~", key(TerminalKey.F5))
        assertEquals("${CSI}24~", key(TerminalKey.F12))
        assertEquals("${CSI}1;2P", key(TerminalKey.F1, KeyModifiers(shift = true)))
    }

    /** Backspace sends DEL. A terminal that sends BS puts `^H` into everything anyone types. */
    @Test
    fun `backspace sends delete, and control backspace sends the other one`() {
        assertEquals(DEL, key(TerminalKey.BACKSPACE))
        assertEquals(BS, key(TerminalKey.BACKSPACE, KeyModifiers(control = true)))
        assertEquals("$ESC$DEL", key(TerminalKey.BACKSPACE, KeyModifiers(alt = true)))
    }

    @Test
    fun `shift and tab is a sequence of its own`() {
        assertEquals("\t", key(TerminalKey.TAB))
        assertEquals("${CSI}Z", key(TerminalKey.TAB, KeyModifiers(shift = true)))
    }

    /** What `Ctrl+C` means: the caret notation is not a metaphor, it is the byte. */
    @Test
    fun `control turns a letter into the code it names`() {
        assertEquals(ctrl('c'), KeyEncoder.encodeCharacter('c'.code, KeyModifiers(control = true)))
        assertEquals(ctrl('d'), KeyEncoder.encodeCharacter('d'.code, KeyModifiers(control = true)))
        assertEquals(ctrl('a'), KeyEncoder.encodeCharacter('a'.code, KeyModifiers(control = true)))
        assertEquals(ESC, KeyEncoder.encodeCharacter('['.code, KeyModifiers(control = true)))
        assertEquals(NUL, KeyEncoder.encodeCharacter(' '.code, KeyModifiers(control = true)))
        // And the same chord when the toolkit already resolved it to the byte.
        assertEquals(ctrl('c'), KeyEncoder.encodeCharacter(3, KeyModifiers(control = true)))
    }

    @Test
    fun `alt is an escape before whatever the key would have sent`() {
        assertEquals("${ESC}b", KeyEncoder.encodeCharacter('b'.code, KeyModifiers(alt = true)))
        assertEquals("$ESC\r", key(TerminalKey.ENTER, KeyModifiers(alt = true)))
    }

    /** The command key belongs to the application — a pane must not eat the window's shortcuts. */
    @Test
    fun `the command key is left to the application`() {
        assertNull(KeyEncoder.encodeCharacter('t'.code, KeyModifiers(meta = true)))
    }

    @Test
    fun `ordinary characters are sent as themselves`() {
        assertEquals("a", KeyEncoder.encodeCharacter('a'.code, KeyModifiers.NONE))
        assertEquals("中", KeyEncoder.encodeCharacter('中'.code, KeyModifiers.NONE))
        assertEquals("🌲", KeyEncoder.encodeCharacter(0x1F332, KeyModifiers.NONE))
    }

    /**
     * Without the brackets, pasting into an editor runs every newline as a command and puts every
     * line through the auto-indent — which is the entire reason the mode exists.
     */
    @Test
    fun `pasted text is bracketed when the program asked for it`() {
        assertEquals("one\rtwo", KeyEncoder.paste("one\ntwo", plain))
        assertEquals("${CSI}200~one\rtwo${CSI}201~", KeyEncoder.paste("one\ntwo", bracketing))
        // Windows line endings must not arrive as two newlines.
        assertEquals("one\rtwo", KeyEncoder.paste("one\r\ntwo", plain))
    }
}
