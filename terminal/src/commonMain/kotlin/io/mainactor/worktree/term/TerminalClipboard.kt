package io.mainactor.worktree.term

/**
 * The system clipboard, as an interface.
 *
 * Injected rather than reached for, the way everything that leaves the window is in this
 * application — and here it buys something specific: `OSC 52` lets a *program* ask the terminal to
 * put text on the clipboard, and whether that is allowed becomes a decision with a test rather than
 * a side effect of whichever API was to hand.
 */
interface TerminalClipboard {
    fun read(): String?
    fun write(text: String)

    /** A pane with nowhere to copy to, which is what a test and an off-screen render get. */
    object None : TerminalClipboard {
        override fun read(): String? = null
        override fun write(text: String) = Unit
    }
}
