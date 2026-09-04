package io.mainactor.worktree.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import io.mainactor.worktree.TerminalSession

/**
 * Hosts a [TerminalSessionHandle]'s Swing widget inside Compose.
 *
 * The widget is fetched from the manager rather than created here, so re-entering a tab reattaches
 * the *running* shell instead of starting a fresh one. Closing the tab is what ends the process,
 * and that is driven from [io.mainactor.worktree.AppState], not from composition being discarded.
 */
@Composable
fun EmbeddedTerminal(
    session: TerminalSession,
    manager: TerminalSessionManager,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val handle = remember(session.id) {
        manager.getOrCreate(session.id, session.workDir, session.title)
    }

    // Selecting a pane anywhere else — its header, a shortcut — has to move the caret too, or the
    // highlighted pane and the one receiving keystrokes drift apart.
    LaunchedEffect(handle, focused) {
        if (focused && !handle.hasKeyboardFocus) handle.requestFocus()
    }

    SwingPanel(
        background = Color(0xFF1E1F22),
        factory = { handle.widget },
        modifier = modifier,
    )

    DisposableEffect(handle) {
        onDispose {
            // Deliberately does not stop the process: the tab may just be inactive.
        }
    }
}
