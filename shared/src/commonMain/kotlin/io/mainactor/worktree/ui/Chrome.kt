package io.mainactor.worktree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mainactor.worktree.AppMode
import io.mainactor.worktree.AppState
import io.mainactor.worktree.label
import io.mainactor.worktree.model.RepoOperation
import io.mainactor.worktree.ui.components.Badge
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeIcon
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.theme.Dimens
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/** Main toolbar: repository identity on the left, the git verbs on the right. */
@Composable
fun MainToolbar(
    state: AppState,
    onCommit: () -> Unit,
    onMerge: () -> Unit,
    onRebase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    val worktree = state.selectedWorktree
    val status = state.status
    val hasWorktree = worktree != null && !worktree.isBare

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.toolbarHeight)
            .background(colors.toolbar)
            // The toolbar is the top of the window, so where the title bar is hidden it is also
            // what the window is dragged by — and what has to keep clear of the buttons the
            // platform still draws over it.
            .windowHandle()
            .padding(start = maxOf(8.dp, WindowChrome.controlsWidth), end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeSwitch(state)
        Divider()

        Text(
            text = state.project?.name ?: "Forest",
            color = colors.text,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (worktree != null && state.mode == AppMode.PROJECT) {
            Text("/", color = colors.textDisabled)
            IdeIcon(IconKind.BRANCH, colors.textDim, size = 12.dp)
            Text(
                text = worktree.label,
                color = colors.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status.ahead > 0) Badge("↑${status.ahead}", colors.added)
            if (status.behind > 0) Badge("↓${status.behind}", colors.warning)
            if (!status.hasUpstream && status.branch != null) Badge("no upstream", colors.textDim)
            if (status.operation != RepoOperation.NONE) {
                Badge(status.operation.label, colors.conflicted)
            }
        }

        Spacer(Modifier.weight(1f))

        // The git verbs act on the selected worktree, which the agent wall does not have a notion
        // of; they stay visible but idle so the toolbar does not reshuffle when the mode changes.
        val gitActionsEnabled = hasWorktree && state.mode == AppMode.PROJECT

        ToolButton(
            icon = IconKind.FETCH,
            tooltip = "Fetch — update remote branches without touching your files",
            detail = "git fetch --all --prune",
            onClick = state::fetch,
            enabled = gitActionsEnabled,
        )
        PullButton(state, enabled = gitActionsEnabled)
        ToolButton(
            icon = IconKind.ARROW_UP,
            tooltip = if (status.hasUpstream) {
                "Push this branch to its upstream"
            } else {
                "Push — publishes this branch, which has no upstream yet"
            },
            detail = if (status.hasUpstream) "git push" else "git push -u origin ${status.branch.orEmpty()}",
            onClick = { state.push() },
            enabled = gitActionsEnabled,
        )
        Divider()
        ToolButton(
            icon = IconKind.COMMIT,
            tooltip = "Commit staged changes",
            detail = "git commit",
            onClick = onCommit,
            enabled = gitActionsEnabled && !status.isClean,
        )
        ToolButton(
            icon = IconKind.MERGE,
            tooltip = "Merge another branch into this worktree",
            detail = "git merge <branch>",
            onClick = onMerge,
            enabled = gitActionsEnabled,
        )
        ToolButton(
            icon = IconKind.REBASE,
            tooltip = "Replay this branch's commits on top of another",
            detail = "git rebase <branch>",
            onClick = onRebase,
            enabled = gitActionsEnabled,
        )
        Divider()
        ToolButton(
            icon = IconKind.TERMINAL,
            tooltip = if (state.terminalVisible) "Hide the terminal" else "Show the terminal",
            onClick = state::toggleTerminal,
            enabled = hasWorktree && state.mode == AppMode.PROJECT,
        )
        ToolButton(
            icon = IconKind.REFRESH,
            tooltip = "Reload worktrees, status and history",
            onClick = state::refresh,
            enabled = state.project != null,
        )
    }
}

/** Project or agents: the two things the window can be showing. */
@Composable
private fun ModeSwitch(state: AppState) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.arc))
            .background(colors.editor)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ModeChip("Project", state.mode == AppMode.PROJECT, state.project != null) {
            state.switchTo(AppMode.PROJECT)
        }
        ModeChip("Agents", state.mode == AppMode.AGENTS, state.project != null, state.agents.size) {
            state.switchTo(AppMode.AGENTS)
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            color = when {
                selected -> Color.White
                enabled -> colors.textDim
                else -> colors.textDisabled
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
        if (badge > 0 && !selected) {
            Text("$badge", color = colors.accent, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(16.dp)
            .background(LocalWorktreeColors.current.selectionInactive),
    )
}

/** Pull offers merge or rebase explicitly rather than silently following the user's config. */
@Composable
private fun PullButton(state: AppState, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val colors = LocalWorktreeColors.current
    Box {
        ToolButton(
            icon = IconKind.ARROW_DOWN,
            tooltip = "Pull — fetch and integrate the upstream branch",
            detail = "choose merge or rebase",
            onClick = { open = true },
            enabled = enabled,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.panelAlt),
        ) {
            DropdownMenuItem(
                text = { Text("Pull (merge)", color = colors.text, style = MaterialTheme.typography.bodySmall) },
                onClick = { open = false; state.pull(rebase = false) },
            )
            DropdownMenuItem(
                text = { Text("Pull (rebase)", color = colors.text, style = MaterialTheme.typography.bodySmall) },
                onClick = { open = false; state.pull(rebase = true) },
            )
        }
    }
}

/** Bottom strip: current activity, the last message, and where we are on disk. */
@Composable
fun StatusBar(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    val notice = state.notice

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.statusBarHeight)
            .background(colors.statusBar)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A background command has no terminal to look at, so the status bar is the only sign it is
        // running at all.
        val busy = state.busy ?: state.backgroundRuns.firstOrNull()?.let { first ->
            if (state.backgroundRuns.size > 1) "$first (+${state.backgroundRuns.size - 1} more)" else first
        }

        // Exactly one weighted child. Several of them would share the row evenly regardless of
        // what they contain, which truncates both halves long before they run out of room.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                busy != null -> {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(colors.accent))
                    Text(
                        text = "$busy…",
                        color = colors.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                notice != null -> {
                    IdeIcon(
                        icon = if (notice.isError) IconKind.WARNING else IconKind.CHECK,
                        tint = if (notice.isError) colors.error else colors.success,
                        size = 12.dp,
                    )
                    Text(
                        text = notice.text,
                        color = if (notice.isError) colors.error else colors.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { state.notice = null },
                    )
                }

                else -> Text("Ready", color = colors.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }

        state.selectedWorktree?.let { worktree ->
            Text(
                text = worktree.path,
                color = colors.textDisabled,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                // Unweighted, so it keeps its natural width and stays flush right — but capped,
                // so a deeply nested worktree cannot squeeze the message on the left to nothing.
                modifier = Modifier.widthIn(max = 420.dp),
            )
            worktree.shortHead?.let {
                Text(
                    text = it,
                    color = colors.textDisabled,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Header of a pane that hosts tabs (right-hand pane, terminal tool window). */
@Composable
fun TabStrip(
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    tabs: @Composable () -> Unit,
) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = modifier.fillMaxWidth().height(Dimens.tabHeight).background(colors.panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) { tabs() }
        Row(
            modifier = Modifier.padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) { trailing() }
    }
}

