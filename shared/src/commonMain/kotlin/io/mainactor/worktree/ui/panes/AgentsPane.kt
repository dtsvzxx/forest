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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.mainactor.worktree.ui.AgentShortcuts
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeButton
import io.mainactor.worktree.ui.components.IdeIcon
import io.mainactor.worktree.ui.components.ProportionalSplitter
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.theme.Dimens
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

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
    suspended: Boolean,
    onAddAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    val zoomed = state.agents.firstOrNull { it.id == state.zoomedAgent }

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
                    suspended = suspended,
                    zoomed = true,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> state.agentLayout?.let { layout ->
                    PaneTree(layout, state, terminal, suspended, Modifier.fillMaxSize())
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
    suspended: Boolean,
    modifier: Modifier = Modifier,
) {
    when (node) {
        is PaneNode.Leaf -> key(node.session.id) {
            AgentPane(
                state = state,
                session = node.session,
                terminal = terminal,
                suspended = suspended,
                zoomed = false,
                modifier = modifier,
            )
        }

        is PaneNode.Split -> {
            var extentPx by remember(node.id) { mutableStateOf(0f) }
            val fraction = node.fraction.coerceIn(MIN_PANE_FRACTION, 1f - MIN_PANE_FRACTION)

            if (node.axis == SplitAxis.ROW) {
                Row(modifier.onSizeChanged { extentPx = it.width.toFloat() }) {
                    PaneTree(node.first, state, terminal, suspended, Modifier.weight(fraction))
                    ProportionalSplitter(
                        vertical = true,
                        fraction = fraction,
                        onFractionChange = { state.resizeAgentSplit(node.id, it) },
                        totalPx = extentPx,
                    )
                    PaneTree(node.second, state, terminal, suspended, Modifier.weight(1f - fraction))
                }
            } else {
                Column(modifier.onSizeChanged { extentPx = it.height.toFloat() }) {
                    PaneTree(node.first, state, terminal, suspended, Modifier.weight(fraction))
                    ProportionalSplitter(
                        vertical = false,
                        fraction = fraction,
                        onFractionChange = { state.resizeAgentSplit(node.id, it) },
                        totalPx = extentPx,
                    )
                    PaneTree(node.second, state, terminal, suspended, Modifier.weight(1f - fraction))
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
    suspended: Boolean,
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
                if (suspended) {
                    EmptyState("Paused while a dialog is open — the agent keeps running.")
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
        Text(
            text = session.title,
            color = if (focused) colors.text else colors.textDim,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
            icon = if (zoomed) IconKind.MINUS else IconKind.PLUS,
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

