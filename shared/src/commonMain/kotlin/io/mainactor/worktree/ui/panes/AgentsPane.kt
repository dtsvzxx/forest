package io.mainactor.worktree.ui.panes

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mainactor.worktree.AppState
import io.mainactor.worktree.TerminalSession
import io.mainactor.worktree.model.MIN_PANE_FRACTION
import io.mainactor.worktree.model.PaneNode
import io.mainactor.worktree.model.SplitAxis
import io.mainactor.worktree.usage.UsageFormat
import io.mainactor.worktree.ui.AgentShortcuts
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeButton
import io.mainactor.worktree.ui.components.IdeIcon
import io.mainactor.worktree.ui.components.ProportionalSplitter
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.components.Tooltip
import io.mainactor.worktree.ui.theme.Dimens
import io.mainactor.worktree.ui.theme.LocalWorktreeColors
import kotlinx.coroutines.delay

/**
 * The agent wall: many terminals for the same worktree, tiled like a multiplexer.
 *
 * Several agents sharing one checkout is the point rather than an accident — they are working on
 * the same branch — so panes are tiled instead of tabbed, and any one of them can be zoomed to
 * fill the wall while the rest keep running behind it.
 */
@Composable
fun AgentsPane(
    state: AppState,
    terminal: @Composable (session: TerminalSession, focused: Boolean, modifier: Modifier) -> Unit,
    /**
     * Why the panes are detached, or null while they are not.
     *
     * A reason rather than a flag because there are two of them now — a modal, and the wall's own
     * terminal overlay — and a pane that says "paused while a dialog is open" with no dialog on
     * screen is worse than one that says nothing. Detaching at all is the price of the JediTerm
     * engine: a pane is a heavyweight Swing widget, and Compose drawn over one goes underneath it.
     */
    pausedBecause: String?,
    onAddAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    val zoomed = state.agents.firstOrNull { it.id == state.zoomedAgent }

    // Usage is re-read only while the wall is on screen. There is no timer hiding in AppState:
    // the numbers exist to be looked at, and nothing looks at them from the project view.
    LaunchedEffect(Unit) {
        while (true) {
            state.refreshAgentUsage().join()
            delay(USAGE_POLL_MS)
        }
    }

    Column(modifier.fillMaxSize().background(colors.editor)) {
        AgentsToolbar(state, onAddAgent)
        HorizontalDivider()

        Box(Modifier.fillMaxSize()) {
            when {
                state.agents.isEmpty() -> EmptyState(
                    text = if (state.selectedWorktree == null) {
                        "Open a repository first — agents run inside one of its worktrees."
                    } else {
                        "No agents running."
                    },
                    action = {
                        if (state.selectedWorktree != null) {
                            IdeButton(
                                text = "Start an agent",
                                onClick = onAddAgent,
                                icon = IconKind.PLUS,
                                primary = true,
                            )
                        }
                    },
                )

                zoomed != null -> AgentPane(
                    state = state,
                    session = zoomed,
                    terminal = terminal,
                    pausedBecause = pausedBecause,
                    zoomed = true,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> state.agentLayout?.let { layout ->
                    PaneTree(layout, state, terminal, pausedBecause, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * Renders the split tree.
 *
 * Each split measures itself so its divider can work in fractions of the space it actually has,
 * and both halves are weighted so the whole wall keeps its proportions as the window changes.
 */
@Composable
private fun PaneTree(
    node: PaneNode,
    state: AppState,
    terminal: @Composable (session: TerminalSession, focused: Boolean, modifier: Modifier) -> Unit,
    pausedBecause: String?,
    modifier: Modifier = Modifier,
) {
    when (node) {
        is PaneNode.Leaf -> key(node.session.id) {
            AgentPane(
                state = state,
                session = node.session,
                terminal = terminal,
                pausedBecause = pausedBecause,
                zoomed = false,
                modifier = modifier,
            )
        }

        is PaneNode.Split -> {
            var extentPx by remember(node.id) { mutableStateOf(0f) }
            val fraction = node.fraction.coerceIn(MIN_PANE_FRACTION, 1f - MIN_PANE_FRACTION)

            if (node.axis == SplitAxis.ROW) {
                Row(modifier.onSizeChanged { extentPx = it.width.toFloat() }) {
                    PaneTree(node.first, state, terminal, pausedBecause, Modifier.weight(fraction))
                    ProportionalSplitter(
                        vertical = true,
                        fraction = fraction,
                        onFractionChange = { state.resizeAgentSplit(node.id, it) },
                        totalPx = extentPx,
                    )
                    PaneTree(node.second, state, terminal, pausedBecause, Modifier.weight(1f - fraction))
                }
            } else {
                Column(modifier.onSizeChanged { extentPx = it.height.toFloat() }) {
                    PaneTree(node.first, state, terminal, pausedBecause, Modifier.weight(fraction))
                    ProportionalSplitter(
                        vertical = false,
                        fraction = fraction,
                        onFractionChange = { state.resizeAgentSplit(node.id, it) },
                        totalPx = extentPx,
                    )
                    PaneTree(node.second, state, terminal, pausedBecause, Modifier.weight(1f - fraction))
                }
            }
        }
    }
}

@Composable
private fun AgentPane(
    state: AppState,
    session: TerminalSession,
    terminal: @Composable (session: TerminalSession, focused: Boolean, modifier: Modifier) -> Unit,
    pausedBecause: String?,
    zoomed: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    val focused = state.focusedAgent == session.id
    val menuState = remember { ContextMenuState() }

    // ContextMenuArea wraps its content in a box of its own and its modifier overload is internal,
    // so the layout modifier goes on a box around it — a `weight` handed to something *inside*
    // that wrapper is invisible to the grid's Row, and every pane collapses into one column.
    Box(modifier) {
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("Show worktree in project") { state.showWorktreeInProject(session) },
                ContextMenuItem("Split right…") {
                    state.focusAgent(session.id)
                    state.requestNewAgent(SplitAxis.ROW)
                },
                ContextMenuItem("Split down…") {
                    state.focusAgent(session.id)
                    state.requestNewAgent(SplitAxis.COLUMN)
                },
                ContextMenuItem(if (zoomed) "Restore" else "Zoom") { state.toggleAgentZoom(session.id) },
                ContextMenuItem("New agent here") {
                    state.openAgent(state.worktrees.firstOrNull { it.path == session.workDir })
                },
                // The whole reason the tasks pane is worth writing in: an idea is one right-click
                // from the agent that will act on it.
                ContextMenuItem(
                    if (state.openTasks.isEmpty()) "Send task… (none open)" else "Send task…",
                ) {
                    state.focusAgent(session.id)
                    state.requestTask(session.id)
                },
                ContextMenuItem("Copy path") { state.system.copyToClipboard(session.workDir) },
                ContextMenuItem(state.system.revealLabel) { state.system.reveal(session.workDir) },
                ContextMenuItem("Close agent") { state.closeAgent(session.id) },
            )
        },
        state = menuState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.editor)
                .border(
                    width = 1.dp,
                    color = if (focused) colors.accent else colors.separator,
                    shape = RoundedCornerShape(6.dp),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { state.focusAgent(session.id) },
        ) {
            AgentPaneHeader(state, session, focused, zoomed)
            HorizontalDivider()
            Box(
                Modifier.fillMaxSize().onSizeChanged {
                    state.recordAgentPaneSize(session.id, it.width, it.height)
                },
            ) {
                if (pausedBecause != null) {
                    EmptyState(pausedBecause)
                } else {
                    terminal(session, focused, Modifier.fillMaxSize())
                }
            }
        }
    }
    }
}

@Composable
private fun AgentPaneHeader(
    state: AppState,
    session: TerminalSession,
    focused: Boolean,
    zoomed: Boolean,
) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(if (focused) colors.selectionInactive else colors.panel)
            // Double-click to zoom, the way a multiplexer does it.
            .pointerInput(session.id) {
                detectTapGestures(
                    onDoubleTap = { state.toggleAgentZoom(session.id) },
                    onTap = { state.focusAgent(session.id) },
                )
            }
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IdeIcon(IconKind.TERMINAL, if (focused) colors.accent else colors.textDim, size = 11.dp)
        PaneName(session, focused, Modifier.weight(1f))
        state.agentUsage[session.workDir]?.takeIf { !it.isEmpty }?.let { usage ->
            Tooltip(
                text = "Agent usage in ${session.workDir.substringAfterLast('/')}",
                detail = UsageFormat.detail(usage),
            ) {
                // Unweighted: the title above keeps the row's only weight, so a long branch name
                // gives way to the figure rather than the two splitting the row between them.
                Text(
                    text = UsageFormat.badge(usage),
                    color = colors.textDim,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        // Only while something is left to do. A button whose every press could say no more than
        // "nothing on the list" is furniture on the header of everyone who does not keep one, and
        // the context menu still carries the entry that says where tasks come from.
        if (state.openTasks.isNotEmpty()) {
            ToolButton(
                icon = IconKind.TASK,
                tooltip = "Send a task to this agent — it is pasted into the pane and submitted",
                onClick = {
                    state.focusAgent(session.id)
                    state.requestTask(session.id)
                },
                modifier = Modifier.size(20.dp),
                tint = colors.textDim,
            )
        }
        ToolButton(
            icon = IconKind.GOTO,
            tooltip = "Show this agent's worktree in the project view",
            onClick = { state.showWorktreeInProject(session) },
            modifier = Modifier.size(20.dp),
            tint = colors.textDim,
        )
        ToolButton(
            icon = IconKind.SPLIT_RIGHT,
            tooltip = "Split this agent to the right — choose the worktree for the new pane",
            detail = AgentShortcuts.splitRight,
            onClick = {
                state.focusAgent(session.id)
                state.requestNewAgent(SplitAxis.ROW)
            },
            modifier = Modifier.size(20.dp),
            tint = colors.textDim,
        )
        ToolButton(
            icon = IconKind.SPLIT_DOWN,
            tooltip = "Split this agent downwards — choose the worktree for the new pane",
            detail = AgentShortcuts.splitDown,
            onClick = {
                state.focusAgent(session.id)
                state.requestNewAgent(SplitAxis.COLUMN)
            },
            modifier = Modifier.size(20.dp),
            tint = colors.textDim,
        )
        ToolButton(
            icon = if (zoomed) IconKind.RESTORE else IconKind.MAXIMIZE,
            tooltip = if (zoomed) "Restore this pane to the wall" else "Zoom this pane to the whole wall",
            detail = AgentShortcuts.zoomPane,
            onClick = { state.toggleAgentZoom(session.id) },
            modifier = Modifier.size(20.dp),
            tint = colors.textDim,
        )
        ToolButton(
            icon = IconKind.CLOSE,
            tooltip = "Close this agent — its shell is terminated",
            detail = AgentShortcuts.closePane,
            onClick = { state.closeAgent(session.id) },
            modifier = Modifier.size(20.dp),
            tint = colors.textDim,
        )
    }
}

/**
 * Which repository, which worktree, and which agent — in that order.
 *
 * Three pieces rather than one string, because they are not equally important and the header is
 * narrow. A pane used to be labelled "main · 2", which names a branch that half the repositories on
 * the wall also have and an ordinal that says nothing; the repository was missing entirely, and the
 * wall is precisely the place that holds panes from several at once.
 *
 * Only the worktree is weighted, so it is the one that gives way — branch names are the long ones
 * here ("NOTASK-partial-payments-…"), while a repository name and "Claude 2" are short and are the
 * two halves of "which pane is this". The full path is a hover away, the way every other row in
 * this window does it.
 */
@Composable
private fun PaneName(session: TerminalSession, focused: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    // The weight goes on a Box *around* the tooltip, for the same reason it does around a context
    // menu: `TooltipArea` wraps its content in a layout of its own, and a weight handed to
    // something inside that wrapper is invisible to the header's Row.
    Box(modifier) {
        Tooltip(text = session.workDir, detail = session.command) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                session.projectName?.let { project ->
                    Text(
                        text = project,
                        color = colors.textDim,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = PROJECT_NAME_MAX),
                    )
                    Text("/", color = colors.textDisabled, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = session.label,
                    color = if (focused) colors.text else colors.textDim,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // `fill = false`: the branch may shrink to fit, but it must not stretch, or
                    // the pane's number is flung to the far right where it reads as part of the
                    // usage badge instead of as part of the name.
                    modifier = Modifier.weight(1f, fill = false),
                )
                session.agentLabel?.let { agent ->
                    Text("·", color = colors.textDisabled, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = agent,
                        color = colors.textDisabled,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentsToolbar(state: AppState, onAddAgent: () -> Unit) {
    val colors = LocalWorktreeColors.current
    val hasPanes = state.agents.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.tabHeight)
            .background(colors.panel)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Agents", color = colors.text, style = MaterialTheme.typography.titleSmall)
        Text(
            text = "${state.agents.size} running",
            color = colors.textDim,
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.weight(1f))

        if (state.zoomedAgent != null) {
            IdeButton(
                text = "Restore layout",
                onClick = { state.zoomedAgent?.let { state.toggleAgentZoom(it) } },
            )
        }
        ToolButton(
            icon = IconKind.TERMINAL,
            // Named by the worktree it will land in, because on a wall spanning several
            // repositories "a terminal" is not enough to know where the command would run.
            tooltip = state.focusedAgentSession?.let { "Terminal in ${it.label} — hides without stopping" }
                ?: "Terminal",
            onClick = state::toggleTerminal,
            enabled = hasPanes,
        )
        Divider()
        ToolButton(
            icon = IconKind.SPLIT_RIGHT,
            tooltip = "Split the focused agent to the right — choose the worktree for the new pane",
            detail = AgentShortcuts.splitRight,
            onClick = { state.requestNewAgent(SplitAxis.ROW) },
            enabled = hasPanes,
        )
        ToolButton(
            icon = IconKind.SPLIT_DOWN,
            tooltip = "Split the focused agent downwards — choose the worktree for the new pane",
            detail = AgentShortcuts.splitDown,
            onClick = { state.requestNewAgent(SplitAxis.COLUMN) },
            enabled = hasPanes,
        )
        Divider()
        ToolButton(
            icon = IconKind.PLUS,
            tooltip = "New agent — choose the project and worktree",
            detail = AgentShortcuts.newAgent,
            onClick = onAddAgent,
            enabled = state.projects.isNotEmpty(),
        )
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(16.dp)
            .background(LocalWorktreeColors.current.separator),
    )
}

/** How often the wall re-reads the usage transcripts while it is on screen. */
private const val USAGE_POLL_MS = 4_000L

/** A repository name is short; past this it is eating the branch's room rather than saying more. */
private val PROJECT_NAME_MAX = 110.dp
