package io.mainactor.worktree.ui.panes

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mainactor.worktree.AppState
import io.mainactor.worktree.model.RepoStatus
import io.mainactor.worktree.model.Worktree
import io.mainactor.worktree.ui.components.Badge
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeIcon
import io.mainactor.worktree.ui.components.IdeTextField
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.components.Tooltip
import io.mainactor.worktree.ui.components.ToolWindowHeader
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * Middle pane: every working tree of the current repository.
 *
 * Each row carries the state you actually need to decide where to go next — which branch is
 * checked out, how dirty it is, and how far it has drifted from its upstream.
 */
@Composable
fun WorktreesPane(
    state: AppState,
    onCreate: () -> Unit,
    onRemove: (Worktree) -> Unit,
    onSwitchBranch: (Worktree) -> Unit,
    onRename: (Worktree) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    val selected = state.selectedWorktree
    var filter by remember { mutableStateOf("") }

    // Hoisted above the branches below: a momentarily empty list must not throw the scroll
    // position away.
    val listState = rememberLazyListState()

    val shown = remember(state.worktrees, filter) {
        if (filter.isBlank()) {
            state.worktrees
        } else {
            state.worktrees.filter {
                it.label.contains(filter, ignoreCase = true) || it.name.contains(filter, ignoreCase = true)
            }
        }
    }

    Column(modifier.fillMaxSize().background(colors.panel)) {
        ToolWindowHeader("Worktrees") {
            ToolButton(
                icon = IconKind.PLUS,
                tooltip = "Add a worktree — a second checkout of this repository",
                detail = "git worktree add",
                onClick = onCreate,
                enabled = state.project != null,
            )
            ToolButton(
                icon = IconKind.MINUS,
                tooltip = "Remove the selected worktree — deletes its folder, keeps the branch",
                detail = "git worktree remove",
                onClick = { selected?.let(onRemove) },
                enabled = selected != null && !selected.isMain,
            )
            ToolButton(
                icon = IconKind.BRANCH,
                tooltip = "Check out a different branch in the selected worktree",
                detail = "git checkout <branch>",
                onClick = { selected?.let(onSwitchBranch) },
                enabled = selected != null && !selected.isBare,
            )
            ToolButton(
                icon = IconKind.TERMINAL,
                tooltip = "Open a shell in the selected worktree",
                onClick = { selected?.let { state.openTerminal(it) } },
                enabled = selected != null,
            )
            ToolButton(
                icon = IconKind.LOCK,
                tooltip = if (selected?.isLocked == true) {
                    "Unlock — allow this worktree to be pruned again"
                } else {
                    "Lock — keep this worktree even when its folder is unreachable"
                },
                detail = if (selected?.isLocked == true) "git worktree unlock" else "git worktree lock",
                onClick = { selected?.let { state.toggleLock(it) } },
                enabled = selected != null,
                tint = if (selected?.isLocked == true) colors.warning else null,
            )
            ToolButton(
                icon = IconKind.REVERT,
                tooltip = "Forget worktrees whose folders no longer exist",
                detail = "git worktree prune",
                onClick = state::pruneWorktrees,
                enabled = state.project != null,
            )
            ToolButton(
                icon = IconKind.REFRESH,
                tooltip = "Reload worktrees, status and history",
                onClick = state::refresh,
                enabled = state.project != null,
            )
        }
        HorizontalDivider()

        // Past a handful of worktrees, filtering beats scrolling — which is the whole reason a
        // repository gets dozens of them in the first place.
        if (state.worktrees.size > FILTER_THRESHOLD) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IdeTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = "Filter ${state.worktrees.size} worktrees…",
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (filter.isNotEmpty()) {
                    ToolButton(
                        icon = IconKind.CLOSE,
                        tooltip = "Clear the filter",
                        onClick = { filter = "" },
                        modifier = Modifier.size(18.dp),
                        tint = colors.textDim,
                    )
                }
            }
            HorizontalDivider()
        }

        when {
            state.project == null -> EmptyState("Select a repository on the left.")
            state.worktrees.isEmpty() -> EmptyState("No worktrees found.")
            shown.isEmpty() -> EmptyState("No worktree matches \"$filter\".")
            else -> Box(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { worktree ->
                        WorktreeRow(
                            state = state,
                            worktree = worktree,
                            onRemove = { onRemove(worktree) },
                            onSwitchBranch = { onSwitchBranch(worktree) },
                            onRename = { onRename(worktree) },
                            status = state.worktreeStatuses[worktree.path],
                            selected = selected?.path == worktree.path,
                            onClick = { state.selectWorktree(worktree) },
                            onLockToggle = { state.toggleLock(worktree) },
                            // Keep the badges clear of the overlay scrollbar on a long list.
                            endPadding = if (shown.size > 1) SCROLLBAR_WIDTH else 4.dp,
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** Below this many worktrees the list fits on screen and a filter is just clutter. */
private const val FILTER_THRESHOLD = 8

private val SCROLLBAR_WIDTH = 12.dp

@Composable
private fun WorktreeRow(
    state: AppState,
    worktree: Worktree,
    status: RepoStatus?,
    selected: Boolean,
    onClick: () -> Unit,
    onLockToggle: () -> Unit,
    onRemove: () -> Unit,
    onSwitchBranch: () -> Unit,
    onRename: () -> Unit,
    endPadding: androidx.compose.ui.unit.Dp,
) {
    val colors = LocalWorktreeColors.current
    val dirty = status?.files?.count { !it.ignored } ?: 0
    val conflicts = status?.conflicts?.size ?: 0

    ContextMenuArea(
        items = {
            buildList {
                if (!worktree.isBare) {
                    val running = state.agentCountFor(worktree)
                    if (running > 0) {
                        add(
                            ContextMenuItem(
                                if (running == 1) "Focus agent" else "Focus agent ($running)",
                            ) { state.focusAgentFor(worktree) },
                        )
                    }
                    // One entry per agent the project offers, because a submenu is not something
                    // Compose Desktop's context menu has and a picker for two items is a click too
                    // many for the thing you do most.
                    val agents = state.availableAgents
                    if (agents.isEmpty()) {
                        add(ContextMenuItem("Start agent here") { state.startAgentFor(worktree) })
                    } else {
                        agents.forEach { agent ->
                            add(
                                ContextMenuItem("Start ${agent.name} here") {
                                    state.startAgentFor(worktree, agent)
                                },
                            )
                        }
                    }
                    add(ContextMenuItem("Switch branch…", onSwitchBranch))
                }
                // Offered on the main working tree too: git will not move it, but its branch
                // renames like any other.
                add(ContextMenuItem("Rename…", onRename))
                add(ContextMenuItem("Open terminal here") { state.openTerminal(worktree) })
                add(ContextMenuItem(state.system.revealLabel) { state.system.reveal(worktree.path) })
                add(ContextMenuItem("Copy path") { state.system.copyToClipboard(worktree.path) })
                worktree.branch?.let { branch ->
                    add(ContextMenuItem("Copy branch name") { state.system.copyToClipboard(branch) })
                }
                add(
                    ContextMenuItem(
                        if (worktree.isLocked) "Unlock worktree" else "Lock worktree",
                        onLockToggle,
                    )
                )
                if (!worktree.isMain) add(ContextMenuItem("Remove worktree…", onRemove))
            }
        },
    ) {
    ListRow(
        selected = selected,
        onClick = onClick,
        height = 46.dp,
        padding = PaddingValues(start = 8.dp, end = endPadding),
    ) {
        IdeIcon(
            icon = if (worktree.isMain) IconKind.HOME else IconKind.BRANCH,
            tint = when {
                selected -> colors.text
                worktree.isMain -> colors.textDim
                else -> colors.branchLocal.copy(alpha = 0.8f)
            },
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = worktree.label,
                    color = if (worktree.isDetached) colors.textDim else colors.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The one weighted child, so it gets everything the icons and the age leave.
                    // A weighted spacer next to it would split the row in half and truncate the
                    // branch name long before it needed to be.
                    modifier = Modifier.weight(1f),
                )
                if (worktree.isLocked) IdeIcon(IconKind.LOCK, colors.warning, size = 11.dp)
                if (worktree.isPrunable) IdeIcon(IconKind.WARNING, colors.warning, size = 11.dp)
                // The list is ordered by this, so it has to be visible — and the tooltip says
                // which of the three signals the age came from.
                worktree.lastActivityLabel?.let { age ->
                    Tooltip(
                        text = "Last activity in this worktree — the list is ordered by it",
                        detail = worktree.activityReason,
                    ) {
                        Text(
                            text = age,
                            color = if (selected) colors.text.copy(alpha = 0.75f) else colors.textDisabled,
                            // An explicit compact style: a bare fontSize inherits the ambient line
                            // height, which is tall enough to push the badges out of the row.
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            // Keeps the age clear of a branch name that ran out of room.
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = worktree.name,
                    color = if (selected) colors.text.copy(alpha = 0.7f) else colors.textDisabled,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    conflicts > 0 -> Badge("$conflicts ⚠", colors.conflicted)
                    dirty > 0 -> Badge("$dirty", colors.modified)
                }
                status?.let {
                    if (it.ahead > 0) Badge("↑${it.ahead}", colors.added)
                    if (it.behind > 0) Badge("↓${it.behind}", colors.warning)
                }
            }
        }
        if (worktree.isLocked) {
            ToolButton(
                icon = IconKind.LOCK,
                tooltip = "Locked — click to unlock",
                detail = "git worktree unlock",
                onClick = onLockToggle,
                modifier = Modifier.size(18.dp),
                tint = colors.warning,
            )
        }
    }
    }
}
