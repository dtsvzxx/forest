package io.mainactor.worktree.term

/**
 * The switches a program flips to change how the terminal behaves.
 *
 * Two families with separate namespaces: the handful of ANSI modes (`CSI 4 h`) and the DEC private
 * ones (`CSI ? 25 h`), which is where everything anyone still uses lives.
 *
 * **Answering `DECRQM` correctly is not a formality.** A program asks whether a mode exists before
 * using it, and the difference between "reset" (2) and "not recognised" (0) is the difference
 * between a feature being available and being absent. Claude Code and Codex both ask about
 * synchronized output this way: answer 0 and they redraw the screen in pieces you can watch tear.
 * So [KNOWN] is not a list of what is implemented — it is a promise, and adding to it without
 * implementing the mode is worse than leaving it out.
 */
class Modes {

    private val dec = HashSet<Int>()
    private val ansi = HashSet<Int>()

    init {
        reset()
    }

    /** Back to a fresh terminal: what `RIS` and a new pane start from. */
    fun reset() {
        dec.clear()
        ansi.clear()
        dec += AUTO_WRAP
        dec += CURSOR_VISIBLE
    }

    fun setDec(code: Int, on: Boolean) {
        if (on) dec += code else dec -= code
    }

    fun setAnsi(code: Int, on: Boolean) {
        if (on) ansi += code else ansi -= code
    }

    fun isDecSet(code: Int): Boolean = code in dec
    fun isAnsiSet(code: Int): Boolean = code in ansi

    /** The `DECRPM` answer: 1 set, 2 reset, 0 for a mode we do not implement. */
    fun report(code: Int, private: Boolean): Int = when {
        !private -> if (code in KNOWN_ANSI) (if (code in ansi) 1 else 2) else 0
        code in KNOWN -> if (code in dec) 1 else 2
        else -> 0
    }

    // The handful the rest of the emulator reads by name.
    val autoWrap: Boolean get() = AUTO_WRAP in dec
    val originMode: Boolean get() = ORIGIN in dec
    val cursorVisible: Boolean get() = CURSOR_VISIBLE in dec
    val reverseVideo: Boolean get() = REVERSE_VIDEO in dec
    val applicationCursorKeys: Boolean get() = APPLICATION_CURSOR_KEYS in dec
    val applicationKeypad: Boolean get() = APPLICATION_KEYPAD in dec
    val bracketedPaste: Boolean get() = BRACKETED_PASTE in dec
    val synchronizedOutput: Boolean get() = SYNCHRONIZED_OUTPUT in dec
    val focusReporting: Boolean get() = FOCUS_REPORTING in dec
    val insertMode: Boolean get() = INSERT in ansi
    val newLineMode: Boolean get() = NEW_LINE in ansi

    companion object {
        // ---- ANSI ----
        /** `IRM`: printing pushes what is already there to the right instead of overwriting. */
        const val INSERT = 4

        /** `LNM`: a line feed also returns the carriage. */
        const val NEW_LINE = 20

        // ---- DEC private ----
        const val APPLICATION_CURSOR_KEYS = 1
        const val REVERSE_VIDEO = 5
        const val ORIGIN = 6
        const val AUTO_WRAP = 7
        const val BLINKING_CURSOR = 12
        const val CURSOR_VISIBLE = 25
        const val APPLICATION_KEYPAD = 66
        const val ALT_SCREEN_OLD = 47
        const val MOUSE_CLICK = 1000
        const val MOUSE_DRAG = 1002
        const val MOUSE_ANY = 1003
        const val FOCUS_REPORTING = 1004
        const val MOUSE_UTF8 = 1005
        const val MOUSE_SGR = 1006
        const val MOUSE_URXVT = 1015
        const val MOUSE_SGR_PIXELS = 1016
        const val ALT_SCREEN_CLEAR = 1047
        const val SAVE_CURSOR = 1048
        const val ALT_SCREEN = 1049
        const val BRACKETED_PASTE = 2004

        /**
         * Synchronized output: everything until the matching reset is one frame.
         *
         * The cheapest fidelity win there is — both agent CLIs use it when it is advertised, and
         * without it their spinners and diffs are drawn in visible pieces.
         */
        const val SYNCHRONIZED_OUTPUT = 2026

        /** Every private mode this terminal will admit to understanding. */
        private val KNOWN = setOf(
            APPLICATION_CURSOR_KEYS, REVERSE_VIDEO, ORIGIN, AUTO_WRAP, BLINKING_CURSOR,
            CURSOR_VISIBLE, APPLICATION_KEYPAD, ALT_SCREEN_OLD,
            MOUSE_CLICK, MOUSE_DRAG, MOUSE_ANY, FOCUS_REPORTING,
            MOUSE_UTF8, MOUSE_SGR, MOUSE_URXVT, MOUSE_SGR_PIXELS,
            ALT_SCREEN_CLEAR, SAVE_CURSOR, ALT_SCREEN, BRACKETED_PASTE, SYNCHRONIZED_OUTPUT,
        )

        private val KNOWN_ANSI = setOf(INSERT, NEW_LINE)
    }
}
