package io.mainactor.worktree.term

/**
 * What a view needs from a running terminal, and nothing more.
 *
 * The view is common code and the session that implements this is not — it owns threads, a process
 * and a pty — so this is the seam between them. It is also what lets the view be rendered in a test
 * against a model with no process behind it at all.
 */
interface TerminalPane {

    /**
     * Reads the screen under the session's lock.
     *
     * Keep the block short: the thread draining the pty waits behind it, and a slow reader here is
     * backpressure applied to the program on the far end.
     */
    fun <T> withModel(block: (TerminalModel) -> T): T

    /** Sends input, as if typed. Never blocks the caller. */
    fun send(text: String)

    /** Tells the emulator and then the child that the pane is a different size. */
    fun resize(columns: Int, rows: Int)
}
