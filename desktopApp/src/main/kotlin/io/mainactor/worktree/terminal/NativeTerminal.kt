package io.mainactor.worktree.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.mainactor.worktree.TerminalSession
import io.mainactor.worktree.platform.Os
import io.mainactor.worktree.term.TerminalClipboard
import io.mainactor.worktree.term.TerminalSessions
import io.mainactor.worktree.term.ui.TerminalView
import java.awt.Desktop
import java.net.URI

/**
 * The terminal of our own: an emulator in `:terminal`, drawn by Compose.
 *
 * All this does is translate. The application speaks in [TerminalSession]s — a worktree, a title, an
 * agent's command line — and the module below speaks in an id, a directory and an argument list;
 * keeping the translation here is what lets `:terminal` know nothing about worktrees, and what
 * makes it something that could be lifted out of this repository unchanged.
 *
 * The command and the environment come from exactly the same places the JediTerm engine uses them —
 * `Os.shellRunning` and [terminalEnvironment] — because the two engines differing in what a shell
 * inherits would be a bug that only appears in one of them, which is the worst kind to look for.
 */
class NativeTerminalBackend(
    private val clipboard: TerminalClipboard = AwtTerminalClipboard(),
    private val sessions: TerminalSessions = TerminalSessions(clipboard = clipboard),
    /** Where a hyperlink a program printed goes when it is clicked. */
    private val openLink: (String) -> Unit = { uri -> runCatching { Desktop.getDesktop().browse(URI(uri)) } },
) : TerminalBackend {

    override var onFocusGained: (sessionId: String) -> Unit = {}

    override fun close(id: String) = sessions.close(id)

    override fun closeAll() = sessions.closeAll()

    @Composable
    override fun Pane(session: TerminalSession, focused: Boolean, modifier: Modifier) {
        val pane = remember(session.id) {
            sessions.getOrCreate(
                id = session.id,
                workDir = session.workDir,
                command = Os.shellRunning(session.command),
                env = terminalEnvironment(),
            )
        }
        TerminalView(
            pane = pane,
            focused = focused,
            modifier = modifier,
            clipboard = clipboard,
            onFocusGained = { onFocusGained(session.id) },
            onHyperlink = openLink,
        )
    }
}
