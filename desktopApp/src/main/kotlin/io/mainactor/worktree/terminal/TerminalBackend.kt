package io.mainactor.worktree.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.mainactor.worktree.TerminalSession

/**
 * A terminal implementation the window can run its panes on.
 *
 * The window has always talked to the terminal through a composable slot — `App` takes
 * `terminal: @Composable (session, focused, modifier) -> Unit` and `main.kt` fills it in — and this
 * is the other half of that seam: everything the window needs *besides* drawing a pane, which is
 * the focus report and the session lifecycle. Together they are the whole surface, and it is small
 * enough that a second implementation is a matter of writing one rather than of unpicking the app.
 *
 * That second implementation is the point. Forest's terminal is a heavyweight Swing widget
 * (JediTerm) hosted in a `SwingPanel`, and the app carries a set of workarounds for exactly that —
 * `compose.interop.blending`, detaching the pane while a modal is open, a global AWT key hook.
 * A Compose-native terminal would need none of them, but it is not something one lands in an
 * afternoon; it has to be able to grow beside the working one, which is what this interface is for.
 */
interface TerminalBackend {

    /**
     * Called when a pane takes the keyboard focus.
     *
     * The app cannot work this out for itself from the Compose side: a terminal consumes the
     * pointer press — Swing eats it today, and a Compose terminal will want it for selection and
     * mouse reporting — so a `clickable` wrapped around a pane never fires. Whatever owns the
     * keyboard has to say so, or the highlighted pane and the one receiving keystrokes drift apart.
     */
    var onFocusGained: (sessionId: String) -> Unit

    /**
     * Hands [text] to a running pane as if it had been pasted, and submits it.
     *
     * Pasted rather than typed, and that is the whole of the contract: a prompt is several lines,
     * and a program receiving them as ordinary input submits the first and runs the rest as
     * separate commands. Bracketed paste is what stops that, and only the engine knows whether the
     * program asked for it — which is why this is here rather than at the call site.
     */
    fun sendPrompt(id: String, text: String)

    /** Ends the session's process. Composition being discarded must not do this — a tab may just be inactive. */
    fun close(id: String)

    fun closeAll()

    /**
     * Draws the pane, starting or reattaching its session.
     *
     * Reattaching rather than restarting is the contract: leaving a tab and coming back must find
     * the same shell, with whatever it was running still running.
     */
    @Composable
    fun Pane(session: TerminalSession, focused: Boolean, modifier: Modifier)
}
