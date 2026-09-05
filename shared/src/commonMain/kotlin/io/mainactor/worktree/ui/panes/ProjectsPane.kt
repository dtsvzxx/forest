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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mainactor.worktree.AppState
import io.mainactor.worktree.model.Project
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeIcon
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.components.ToolWindowHeader
import io.mainactor.worktree.ui.components.Tooltip
import io.mainactor.worktree.ui.TerminalEngines
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * Leftmost pane: the repositories the user works with, plus the ways in to a new one.
 * Selecting a project loads its worktrees into the middle pane.
 */
@Composable
fun ProjectsPane(
    state: AppState,
    onClone: () -> Unit,
    onConfigureAgents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    Column(modifier.fillMaxSize().background(colors.panel)) {
        ToolWindowHeader("Projects") {
            ToolButton(
                icon = IconKind.FOLDER,
                tooltip = "Open an existing repository",
                onClick = state::chooseProject,
            )
            ToolButton(
                icon = IconKind.PLUS,
                tooltip = "Create a repository in an empty folder",
                detail = "git init",
                onClick = state::initNewProject,
            )
            ToolButton(
                icon = IconKind.FETCH,
                tooltip = "Clone a remote repository",
                detail = "git clone <url>",
                onClick = onClone,
            )
            TerminalEngineButton()
        }
        HorizontalDivider()

        if (state.projects.isEmpty()) {
            EmptyProjects(onOpen = state::chooseProject)
            return@Column
        }

        val listState = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.projects, key = { it.path }) { project ->
                    ProjectRow(
                        state = state,
                        project = project,
                        selected = state.project?.path == project.path,
                        worktreeCount = if (state.project?.path == project.path) state.worktrees.size else null,
                        onClick = { state.openProject(project.path) },
                        onForget = { state.forgetProject(project.path) },
                        onConfigureAgents = onConfigureAgents,
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

/**
 * Picks the terminal a new pane opens on, when there is more than one to pick from.
 *
 * Absent unless the platform layer filled [TerminalEngines] in, so this costs nothing on a build
 * with a single engine and never appears in a render test.
 */
@Composable
private fun TerminalEngineButton() {
    val colors = LocalWorktreeColors.current
    val options = TerminalEngines.options
    if (options.size < 2) return
    var open by remember { mutableStateOf(false) }

    Box {
        ToolButton(
            icon = IconKind.SETTINGS,
            tooltip = "Which terminal a new pane opens on",
            detail = "Running panes keep the one they started with",
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.panelAlt),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (option.id == TerminalEngines.selected) {
                                    IdeIcon(IconKind.CHECK, colors.accent, size = 11.dp)
                                } else {
                                    Spacer(Modifier.width(11.dp))
                                }
                                Text(
                                    option.label,
                                    color = colors.text,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                option.detail,
                                color = colors.textDim,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 17.dp),
                            )
                        }
                    },
                    onClick = {
                        open = false
                        TerminalEngines.choose(option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProjectRow(
    state: AppState,
    project: Project,
    selected: Boolean,
    worktreeCount: Int?,
    onClick: () -> Unit,
    onForget: () -> Unit,
    onConfigureAgents: () -> Unit,
) {
    val colors = LocalWorktreeColors.current
    // The path used to be a second line on every row. It is long, nearly identical between
    // projects and rarely what you are scanning for — a right-click reaches it when you need it.
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("Open") { onClick() },
                ContextMenuItem("Agents…") { onClick(); onConfigureAgents() },
                ContextMenuItem(state.system.revealLabel) { state.system.reveal(project.path) },
                ContextMenuItem("Copy path") { state.system.copyToClipboard(project.path) },
                ContextMenuItem("Remove from list") { onForget() },
            )
        },
    ) {
        Tooltip(text = project.name, detail = project.path) {
            ListRow(
                selected = selected,
                onClick = onClick,
                padding = PaddingValues(start = 8.dp, end = 4.dp),
            ) {
                IdeIcon(IconKind.FOLDER, if (selected) colors.text else colors.textDim)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = project.name,
                    color = colors.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (worktreeCount != null) {
                    Text(
                        text = "$worktreeCount",
                        color = if (selected) colors.text.copy(alpha = 0.75f) else colors.textDim,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                ToolButton(
                    icon = IconKind.CLOSE,
                    tooltip = "Remove from this list — nothing on disk is deleted",
                    onClick = onForget,
                    modifier = Modifier.size(18.dp),
                    tint = colors.textDim,
                )
            }
        }
    }
}

@Composable
private fun EmptyProjects(onOpen: () -> Unit) {
    val colors = LocalWorktreeColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IdeIcon(IconKind.FOLDER, colors.textDisabled, size = 28.dp)
        Text(
            "No repositories yet",
            color = colors.textDim,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Open a folder that contains a .git directory, or create a new repository.",
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
        )
        io.mainactor.worktree.ui.components.IdeButton(
            text = "Open repository…",
            onClick = onOpen,
            icon = IconKind.FOLDER,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
