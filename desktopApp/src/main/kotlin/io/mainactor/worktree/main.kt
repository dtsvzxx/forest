package io.mainactor.worktree

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.WindowPlacement
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
import io.mainactor.worktree.platform.TreeSitterHighlighter
import io.mainactor.worktree.model.SplitAxis
import io.mainactor.worktree.terminal.AgentKeyBindings
import io.mainactor.worktree.terminal.defaultAgentBindings
import io.mainactor.worktree.ui.components.ForestIconPainter
import io.mainactor.worktree.ui.AgentShortcuts
import io.mainactor.worktree.ui.WindowChrome
import io.mainactor.worktree.window.WindowDrag
import io.mainactor.worktree.window.hideTitleBar
import io.mainactor.worktree.terminal.NativeTerminalBackend
import io.mainactor.worktree.terminal.TerminalEngine
import io.mainactor.worktree.ui.TerminalEngines
import io.mainactor.worktree.terminal.TerminalBackend
import io.mainactor.worktree.terminal.TerminalBackends
import io.mainactor.worktree.terminal.TerminalEngineSetting
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
        // Both engines, side by side, with the setting deciding what the next pane opens on. A
        // running pane cannot change engine — its process is attached to one — so this routes by
        // session rather than switching globally.
        val terminals: TerminalBackend = remember {
            val setting = TerminalEngineSetting(JvmFileSystemAccess())
            // The menu lives in the projects pane and knows nothing about engines; it is filled in
            // from here, the way the agent shortcuts and the window chrome are.
            TerminalEngines.options = listOf(
                TerminalEngines.Option(
                    id = TerminalEngine.JEDITERM.name,
                    label = "JediTerm terminal",
                    detail = "The IntelliJ emulator, hosted from Swing",
                ),
                TerminalEngines.Option(
                    id = TerminalEngine.NATIVE.name,
                    label = "Forest terminal",
                    detail = "Ours, drawn by Compose",
                ),
            )
            TerminalEngines.selected = setting.read().name
            TerminalEngines.onSelect = { id ->
                TerminalEngine.of(id)?.let(setting::write)
            }
            TerminalBackends(
                jediterm = TerminalSessionManager(),
                native = NativeTerminalBackend(),
                engine = { TerminalEngine.of(TerminalEngines.selected) ?: setting.read() },
            )
        }

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
            // The window keeps its buttons but loses its title bar, so the toolbar reaches into
            // that strip — and takes over what the bar did with it.
            DisposableEffect(window) {
                hideTitleBar(window.rootPane)
                if (Os.isMac) {
                    val drag = WindowDrag(window)
                    // A maximised or full-screen window does not follow the pointer, and neither
                    // does a title bar's drag.
                    WindowChrome.onDragStart = {
                        if (windowState.placement == WindowPlacement.Floating) drag.start()
                    }
                    WindowChrome.onToggleZoom = {
                        windowState.placement = if (windowState.placement == WindowPlacement.Floating) {
                            WindowPlacement.Maximized
                        } else {
                            WindowPlacement.Floating
                        }
                    }
                }
                onDispose {
                    WindowChrome.onDragStart = null
                    WindowChrome.onToggleZoom = null
                }
            }

            // Full screen takes the buttons away with the rest of the frame, and the toolbar gets
            // the corner back.
            LaunchedEffect(windowState.placement) {
                WindowChrome.controlsWidth = if (Os.isMac &&
                    windowState.placement != WindowPlacement.Fullscreen
                ) {
                    MAC_WINDOW_CONTROLS_WIDTH
                } else {
                    0.dp
                }
            }

            App(
                state = state,
                terminal = { session, focused, modifier ->
                    terminals.Pane(session = session, focused = focused, modifier = modifier)
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
private fun rememberAppState(terminals: TerminalBackend): AppState {
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
            highlighter = TreeSitterHighlighter(),
            // How a task reaches an agent. `:shared` knows what a task is and nothing about
            // terminals; the backend knows the opposite.
            onSendPrompt = terminals::sendPrompt,
            system = DesktopSystemIntegration(),
            scope = scope,
            onTerminalDisposed = terminals::close,
        ).also { state -> sink = state::recordGitLog }
    }
}

/** Large enough for a retina Dock; the mark is vector, so the number only sets the raster size. */
private const val DOCK_ICON_SIDE = 512f

/**
 * How far the macOS close/minimise/zoom buttons reach from the window's left edge.
 *
 * They are laid out by the window server, not by us — 12pt wide, 20pt apart, starting at 20pt —
 * and this leaves the same margin after the last of them that they start with.
 */
private val MAC_WINDOW_CONTROLS_WIDTH = 78.dp
