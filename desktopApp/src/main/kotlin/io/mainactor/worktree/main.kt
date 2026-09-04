package io.mainactor.worktree

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.awt.Taskbar
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.mainactor.worktree.git.Git
import io.mainactor.worktree.git.GitLogEntry
import io.mainactor.worktree.platform.DesktopSystemIntegration
import io.mainactor.worktree.platform.GitLocator
import io.mainactor.worktree.platform.JvmFileSystemAccess
import io.mainactor.worktree.platform.JvmShellRunner
import io.mainactor.worktree.platform.Os
import io.mainactor.worktree.platform.ProcessCommandRunner
import io.mainactor.worktree.platform.SwingDirectoryChooser
import io.mainactor.worktree.model.SplitAxis
import io.mainactor.worktree.terminal.AgentKeyBindings
import io.mainactor.worktree.terminal.EmbeddedTerminal
import io.mainactor.worktree.terminal.defaultAgentBindings
import io.mainactor.worktree.ui.components.ForestIconPainter
import io.mainactor.worktree.ui.AgentShortcuts
import io.mainactor.worktree.terminal.TerminalSessionManager

fun main() {
    // Lets Compose content (modals, menus) paint over the heavyweight Swing terminal.
    System.setProperty("compose.interop.blending", "true")

    // macOS takes the Dock icon from the bundle's .icns, which only exists in a packaged build —
    // so when run from Gradle the Dock would otherwise show a generic Java mark.
    runCatching {
        Taskbar.getTaskbar().iconImage = ForestIconPainter().toAwtImage(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            size = Size(DOCK_ICON_SIDE, DOCK_ICON_SIDE),
        )
    }

    val bindings = defaultAgentBindings()
    AgentShortcuts.describe(
        splitRight = bindings.splitRight.label,
        splitDown = bindings.splitDown.label,
        newAgent = bindings.newAgent.label,
        closePane = bindings.closePane.label,
        zoomPane = bindings.zoomPane.label,
    )

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val terminals = remember { TerminalSessionManager() }

        val state = rememberAppState(terminals)

        // A click inside a pane's terminal is consumed by Swing, so the wall learns about focus
        // from the widget itself rather than from a Compose click handler it never receives.
        DisposableEffect(state) {
            terminals.onFocusGained = state::focusAgent
            onDispose { terminals.onFocusGained = {} }
        }

        DisposableEffect(state) {
            val hotkeys = AgentKeyBindings(
                bindings = bindings,
                enabled = { state.mode == AppMode.AGENTS },
                // Splitting asks where the new pane runs: agents are spread across repositories,
                // so a split is how one starts on a different worktree.
                onSplitRight = { state.requestNewAgent(SplitAxis.ROW) },
                onSplitDown = { state.requestNewAgent(SplitAxis.COLUMN) },
                onNewAgent = { state.requestNewAgent() },
                onClosePane = state::closeFocusedAgent,
                onZoomPane = state::toggleFocusedAgentZoom,
            )
            hotkeys.install()
            onDispose { hotkeys.uninstall() }
        }

        DisposableEffect(Unit) {
            onDispose { terminals.closeAll() }
        }

        Window(
            onCloseRequest = {
                terminals.closeAll()
                exitApplication()
            },
            state = windowState,
            icon = remember { ForestIconPainter() },
            title = state.project?.let { "${it.name} — Forest" } ?: "Forest",
        ) {
            App(
                state = state,
                terminal = { session, focused, modifier ->
                    EmbeddedTerminal(
                        session = session,
                        manager = terminals,
                        focused = focused,
                        modifier = modifier,
                    )
                },
            )
        }
    }
}

/**
 * Wires the platform implementations into [AppState].
 *
 * [Git] needs a log sink and [AppState] needs a [Git]; the indirection through a mutable holder
 * breaks that cycle without making either of them nullable at the point of use.
 */
@androidx.compose.runtime.Composable
private fun rememberAppState(terminals: TerminalSessionManager): AppState {
    val scope = rememberCoroutineScope()
    return remember {
        val fs = JvmFileSystemAccess()
        var sink: ((GitLogEntry) -> Unit)? = null
        val git = Git(
            runner = ProcessCommandRunner(),
            fs = fs,
            gitPath = GitLocator.locate(),
            nullDevice = Os.nullDevice,
            onLog = { entry -> sink?.invoke(entry) },
        )
        AppState(
            git = git,
            fs = fs,
            store = ProjectStore(fs),
            chooser = SwingDirectoryChooser(),
            shell = JvmShellRunner(),
            system = DesktopSystemIntegration(),
            scope = scope,
            onTerminalDisposed = terminals::close,
        ).also { state -> sink = state::recordGitLog }
    }
}

/** Large enough for a retina Dock; the mark is vector, so the number only sets the raster size. */
private const val DOCK_ICON_SIDE = 512f
