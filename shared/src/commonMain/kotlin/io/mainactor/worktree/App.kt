package io.mainactor.worktree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.mainactor.worktree.model.SplitAxis
import io.mainactor.worktree.model.Worktree
import io.mainactor.worktree.ui.MainToolbar
import io.mainactor.worktree.ui.StatusBar
import io.mainactor.worktree.ui.TabStrip
import io.mainactor.worktree.ui.components.Badge
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.HorizontalSplitter
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeTab
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.components.VerticalDivider
import io.mainactor.worktree.ui.components.VerticalSplitter
import io.mainactor.worktree.ui.dialogs.CloneDialog
import io.mainactor.worktree.ui.dialogs.CommitDialog
import io.mainactor.worktree.ui.dialogs.MergeDialog
import io.mainactor.worktree.ui.dialogs.AgentSettingsDialog
import io.mainactor.worktree.ui.dialogs.NewAgentDialog
import io.mainactor.worktree.ui.dialogs.NewWorktreeDialog
import io.mainactor.worktree.ui.dialogs.RebaseDialog
import io.mainactor.worktree.ui.dialogs.RemoveWorktreeDialog
import io.mainactor.worktree.ui.dialogs.SwitchBranchDialog
import io.mainactor.worktree.ui.panes.AgentsPane
import io.mainactor.worktree.ui.panes.ChangesPane
import io.mainactor.worktree.ui.panes.ConflictsPane
import io.mainactor.worktree.ui.panes.ConsolePane
import io.mainactor.worktree.ui.panes.LogPane
import io.mainactor.worktree.ui.panes.SearchPane
import io.mainactor.worktree.ui.panes.ProjectsPane
import io.mainactor.worktree.ui.panes.WorktreesPane
import io.mainactor.worktree.ui.theme.LocalWorktreeColors
import io.mainactor.worktree.ui.theme.WorktreeTheme

/** Which modal, if any, is on screen. */
private sealed interface Dialog {
    data object NewWorktree : Dialog
    data object Commit : Dialog
    data object Merge : Dialog
    data object Rebase : Dialog
    data object Clone : Dialog
    data class RemoveWorktree(val worktree: Worktree) : Dialog
    data class SwitchBranch(val worktree: Worktree) : Dialog
    data class NewAgent(val axis: SplitAxis?) : Dialog
    data object AgentSettings : Dialog
}

/**
 * The main window.
 *
 * Three vertical panes — projects, worktrees, changes — over a terminal tool window, with a
 * toolbar above and a status bar below. The terminal is supplied by the platform module as a
 * composable slot: it is the one piece of this UI that cannot be written in common code.
 */
@Composable
fun App(
    state: AppState,
    terminal: @Composable (session: TerminalSession, focused: Boolean, modifier: Modifier) -> Unit,
) {
    WorktreeTheme {
        val colors = LocalWorktreeColors.current
        var dialog by remember { mutableStateOf<Dialog?>(null) }

        // Absolute sizes, so a wider window widens the changes pane and a taller one the panes
        // above the terminal — the side panels and the terminal keep the size you gave them.
        var projectsWidth by remember { mutableStateOf(240.dp) }
        var worktreesWidth by remember { mutableStateOf(330.dp) }
        var terminalHeight by remember { mutableStateOf(280.dp) }
        var contentSize by remember { mutableStateOf(DpSize.Zero) }
        val density = LocalDensity.current

        LaunchedEffect(Unit) { state.start() }

        LaunchedEffect(state.agentRequest) {
            state.agentRequest?.let { request ->
                dialog = Dialog.NewAgent(request.axis)
                state.clearAgentRequest()
            }
        }

        Column(Modifier.fillMaxSize().background(colors.editor)) {
            MainToolbar(
                state = state,
                onCommit = { dialog = Dialog.Commit },
                onMerge = { dialog = Dialog.Merge },
                onRebase = { dialog = Dialog.Rebase },
            )
            HorizontalDivider()

            if (state.mode == AppMode.AGENTS) {
                // The wall takes the whole content area: the panes are what you are working in.
                AgentsPane(
                    state = state,
                    terminal = terminal,
                    suspended = dialog != null,
                    onAddAgent = { state.requestNewAgent() },
                    modifier = Modifier.weight(1f),
                )
                HorizontalDivider()
                StatusBar(state)
                // No Dialogs() here: `return@Column` leaves the column, not the theme block, so
                // the call below still runs and a second copy of the modal would stack on top.
                return@Column
            }

            Column(
                modifier = Modifier.weight(1f).onSizeChanged {
                    contentSize = with(density) { DpSize(it.width.toDp(), it.height.toDp()) }
                },
            ) {
                // Shrinking the window must not push the flexible panes out of existence, but it
                // must not forget the sizes either: the stored values are squeezed for layout and
                // come back untouched when there is room again.
                val sidesCap = contentSize.width * SIDE_PANES_MAX_SHARE
                val sidesWanted = projectsWidth + worktreesWidth
                val squeeze = if (contentSize.width > 0.dp && sidesWanted > sidesCap) {
                    sidesCap / sidesWanted
                } else {
                    1f
                }
                val shownTerminalHeight = if (contentSize.height > 0.dp) {
                    minOf(terminalHeight, contentSize.height * TERMINAL_MAX_SHARE)
                } else {
                    terminalHeight
                }

                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    ProjectsPane(
                        state = state,
                        onClone = { dialog = Dialog.Clone },
                        onConfigureAgents = { dialog = Dialog.AgentSettings },
                        modifier = Modifier.width(projectsWidth * squeeze),
                    )
                    VerticalSplitter(
                        size = projectsWidth,
                        onSizeChange = { projectsWidth = it },
                        min = 170.dp,
                        max = 520.dp,
                    )
                    WorktreesPane(
                        state = state,
                        onCreate = { dialog = Dialog.NewWorktree },
                        onRemove = { dialog = Dialog.RemoveWorktree(it) },
                        onSwitchBranch = { dialog = Dialog.SwitchBranch(it) },
                        modifier = Modifier.width(worktreesWidth * squeeze),
                    )
                    VerticalSplitter(
                        size = worktreesWidth,
                        onSizeChange = { worktreesWidth = it },
                        min = 200.dp,
                        max = 640.dp,
                    )
                    // The only flexible pane: everything the window gains goes here.
                    RightPane(
                        state = state,
                        onCommit = { dialog = Dialog.Commit },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (state.terminalVisible) {
                    HorizontalSplitter(
                        size = terminalHeight,
                        onSizeChange = { terminalHeight = it },
                        min = 100.dp,
                        max = 720.dp,
                        sizesTrailingPane = true,
                    )
                    TerminalToolWindow(
                        state = state,
                        terminal = terminal,
                        // A modal must not be painted under the terminal: on desktop the terminal
                        // is a heavyweight Swing component, so it is detached while a dialog is up.
                        suspended = dialog != null,
                        modifier = Modifier.fillMaxWidth().height(shownTerminalHeight),
                    )
                }
            }

            HorizontalDivider()
            StatusBar(state)
        }

        Dialogs(state, dialog, onDismiss = { dialog = null })
    }
}

@Composable
private fun RightPane(state: AppState, onCommit: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    val conflicts = state.status.conflicts.size

    Column(modifier.fillMaxHeight().background(colors.panel)) {
        TabStrip(
            trailing = {
                ToolButton(
                    icon = IconKind.TERMINAL,
                    tooltip = if (state.terminalVisible) "Hide the terminal" else "Show the terminal",
                    onClick = state::toggleTerminal,
                )
            },
        ) {
            IdeTab("Changes", state.rightTab == RightTab.CHANGES, { state.rightTab = RightTab.CHANGES })
            IdeTab(
                text = "Conflicts",
                selected = state.rightTab == RightTab.CONFLICTS,
                onClick = { state.rightTab = RightTab.CONFLICTS },
                badge = { if (conflicts > 0) Badge("$conflicts", colors.conflicted) },
            )
            IdeTab("Log", state.rightTab == RightTab.LOG, { state.rightTab = RightTab.LOG })
            IdeTab("Search", state.rightTab == RightTab.SEARCH, { state.rightTab = RightTab.SEARCH })
            IdeTab("Console", state.rightTab == RightTab.CONSOLE, { state.rightTab = RightTab.CONSOLE })
        }
        HorizontalDivider()

        Box(Modifier.weight(1f)) {
            when {
                state.selectedWorktree == null ->
                    EmptyState("Select a worktree to see its changes.")

                else -> when (state.rightTab) {
                    RightTab.CHANGES -> ChangesPane(state, onCommit)
                    RightTab.CONFLICTS -> ConflictsPane(state)
                    RightTab.LOG -> LogPane(state)
                    RightTab.SEARCH -> SearchPane(state)
                    RightTab.CONSOLE -> ConsolePane(state)
                }
            }
        }
    }
}

/**
 * Bottom tool window hosting the embedded terminals — one tab per worktree by default, and as
 * many extra tabs as the user opens.
 */
@Composable
private fun TerminalToolWindow(
    state: AppState,
    terminal: @Composable (session: TerminalSession, focused: Boolean, modifier: Modifier) -> Unit,
    suspended: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    val active = state.terminals.firstOrNull { it.id == state.activeTerminal }

    Column(modifier.background(colors.editor)) {
        HorizontalDivider()
        TabStrip(
            trailing = {
                ToolButton(
                    icon = IconKind.PLUS,
                    tooltip = "New shell tab in the selected worktree",
                    onClick = { state.openTerminal() },
                    enabled = state.selectedWorktree != null,
                )
                ToolButton(
                    icon = IconKind.MINUS,
                    tooltip = "Hide the terminal — running shells keep going",
                    onClick = state::toggleTerminal,
                )
            },
        ) {
            state.terminals.forEach { session ->
                IdeTab(
                    text = session.title,
                    selected = session.id == state.activeTerminal,
                    onClick = { state.selectTerminal(session.id) },
                    onClose = { state.closeTerminal(session.id) },
                )
            }
        }
        HorizontalDivider()

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (active == null) {
                EmptyState("No terminal open.")
            } else if (suspended) {
                EmptyState("Terminal paused while a dialog is open — the shell keeps running.")
            } else {
                // Only the active tab is composed. The shell behind an inactive tab keeps running:
                // the platform layer owns the process and hands the same widget back on return.
                terminal(active, false, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun Dialogs(state: AppState, dialog: Dialog?, onDismiss: () -> Unit) {
    when (dialog) {
        null -> Unit

        Dialog.NewWorktree -> NewWorktreeDialog(
            branches = state.branches,
            defaultParent = state.defaultWorktreeParent(),
            suggestedBase = state.selectedWorktree?.branch ?: state.baseRef,
            onBrowse = { onPicked -> state.browseForDirectory("Choose parent folder", null, onPicked) },
            onDismiss = onDismiss,
            onConfirm = { request ->
                onDismiss()
                state.createWorktree(
                    path = request.path,
                    newBranch = request.newBranch,
                    existingBranch = request.existingBranch,
                    baseRef = request.baseRef,
                    force = request.force,
                )
            },
        )

        Dialog.Commit -> CommitDialog(
            stagedCount = state.status.staged.size,
            unstagedCount = state.status.unstaged.size,
            onDismiss = onDismiss,
            onCommit = { message, amend, stageAll ->
                onDismiss()
                state.commit(message, amend, stageAll)
            },
        )

        Dialog.Merge -> MergeDialog(
            branches = state.branches,
            currentBranch = state.status.branch,
            onDismiss = onDismiss,
            onConfirm = { ref, noFf ->
                onDismiss()
                state.merge(ref, noFf)
            },
        )

        Dialog.Rebase -> RebaseDialog(
            branches = state.branches,
            currentBranch = state.status.branch,
            onDismiss = onDismiss,
            onConfirm = { onto ->
                onDismiss()
                state.rebase(onto)
            },
        )

        Dialog.Clone -> CloneDialog(
            defaultParent = state.defaultWorktreeParent(),
            onBrowse = { onPicked -> state.browseForDirectory("Choose parent folder", null, onPicked) },
            onDismiss = onDismiss,
            onConfirm = { url, parent, folder ->
                onDismiss()
                state.cloneProject(url, parent, folder)
            },
        )

        is Dialog.NewAgent -> {
            val focused = state.focusedAgentSession
            NewAgentDialog(
                title = when (dialog.axis) {
                    SplitAxis.ROW -> "Split right"
                    SplitAxis.COLUMN -> "Split down"
                    null -> "New agent"
                },
                projects = state.projects,
                // Start from where the pane being split is running, so keeping the same worktree
                // is one confirmation while another repository stays one selection away.
                initialProject = state.projects.firstOrNull { it.path == focused?.projectPath }
                    ?: state.project,
                initialWorktreePath = focused?.workDir
                    ?: (state.agentWorktree ?: state.selectedWorktree)?.path,
                agents = state.availableAgents,
                // Splitting a pane keeps its agent by default: the reason to split is usually more
                // of the same, and the other agents are one click away.
                initialAgentId = focused?.agentId,
                loadWorktrees = state::worktreesOf,
                onDismiss = onDismiss,
                onConfirm = { project, worktree, agent ->
                    onDismiss()
                    state.openAgent(worktree, dialog.axis, project.path, agent)
                },
            )
        }

        Dialog.AgentSettings -> AgentSettingsDialog(
            projectName = state.project?.name.orEmpty(),
            agents = state.projectAgents,
            isInstalled = state::isAgentInstalled,
            onDismiss = onDismiss,
            onConfirm = { agents ->
                onDismiss()
                state.saveProjectAgents(agents)
            },
        )

        is Dialog.SwitchBranch -> SwitchBranchDialog(
            worktreeFolder = dialog.worktree.name,
            worktreePath = dialog.worktree.path,
            branches = state.branches,
            currentBranch = dialog.worktree.branch,
            dirtyFiles = state.worktreeStatuses[dialog.worktree.path]?.files?.size ?: 0,
            onDismiss = onDismiss,
            onSwitch = { branch ->
                onDismiss()
                state.switchBranch(dialog.worktree, branch)
            },
            onCreate = { name, startPoint ->
                onDismiss()
                state.switchToNewBranch(dialog.worktree, name, startPoint)
            },
        )

        is Dialog.RemoveWorktree -> RemoveWorktreeDialog(
            name = dialog.worktree.label,
            path = dialog.worktree.path,
            dirtyFiles = state.worktreeStatuses[dialog.worktree.path]?.files?.size ?: 0,
            onDismiss = onDismiss,
            onConfirm = { force ->
                onDismiss()
                state.removeWorktree(dialog.worktree, force)
            },
        )
    }
}

/** How much of the window the two fixed side panes may take when it gets narrow. */
private const val SIDE_PANES_MAX_SHARE = 0.7f

/** Likewise for the terminal when the window gets short. */
private const val TERMINAL_MAX_SHARE = 0.7f
