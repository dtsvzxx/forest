package io.mainactor.worktree.ui.panes

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mainactor.worktree.AppState
import io.mainactor.worktree.model.Task
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeIcon
import io.mainactor.worktree.ui.components.IdeTextField
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.components.VerticalSplitter
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * The project's list of work, written in the form it will be used: as prompts.
 *
 * A working list rather than a document. There is no save button and no file to name, because what
 * this competes with is a task written into another application that never gets opened again — and
 * every step between deciding to do something and writing it down is a reason not to.
 *
 * The pane earns its place from the other end: on the agent wall a task is one click from being
 * handed to a running agent, which is what makes writing it down worth doing.
 */
@Composable
fun TasksPane(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    val selected = state.tasks.firstOrNull { it.id == state.selectedTask }

    Column(modifier.fillMaxSize().background(colors.panel)) {
        TasksToolbar(state, selected)
        HorizontalDivider()

        if (state.tasks.isEmpty()) {
            EmptyState("Nothing on the list. Write down what needs doing and hand it to an agent.")
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            TaskList(state, Modifier.width(TASK_LIST_WIDTH).fillMaxHeight())
            VerticalSplitter(
                size = TASK_LIST_WIDTH,
                onSizeChange = {},
                min = TASK_LIST_WIDTH,
                max = TASK_LIST_WIDTH,
                color = colors.separator,
            )
            Box(Modifier.weight(1f).fillMaxHeight().background(colors.editor)) {
                if (selected == null) {
                    EmptyState("Select a task.")
                } else {
                    IdeTextField(
                        value = selected.body,
                        onValueChange = { state.updateTask(selected.id, it) },
                        placeholder = "What needs doing, written as you would say it to an agent.",
                        singleLine = false,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TasksToolbar(state: AppState, selected: Task?) {
    val colors = LocalWorktreeColors.current
    val open = state.openTasks.size
    val done = state.tasks.count { it.done }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolButton(
            icon = IconKind.PLUS,
            tooltip = "Add a task",
            onClick = state::addTask,
        )
        ToolButton(
            icon = IconKind.MINUS,
            tooltip = "Delete this task",
            onClick = { selected?.let { state.deleteTask(it.id) } },
            enabled = selected != null,
        )
        Text(
            // What is left comes first, because that is the number anyone reads a list for.
            text = when {
                state.tasks.isEmpty() -> ""
                done == 0 -> "$open open"
                else -> "$open open · $done done"
            },
            color = colors.textDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Send one from an agent's header",
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TaskList(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    val listState = rememberLazyListState()
    Box(modifier.background(colors.panel)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.tasks, key = { it.id }) { task ->
                ContextMenuArea(
                    items = {
                        listOf(
                            ContextMenuItem(if (task.done) "Reopen" else "Mark done") {
                                state.toggleTaskDone(task.id)
                            },
                            ContextMenuItem("Copy") { state.system.copyToClipboard(task.body) },
                            ContextMenuItem("Delete") { state.deleteTask(task.id) },
                        )
                    },
                ) {
                    TaskRow(
                        task = task,
                        selected = state.selectedTask == task.id,
                        onClick = { state.selectTask(task.id) },
                        onToggleDone = { state.toggleTaskDone(task.id) },
                    )
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun TaskRow(task: Task, selected: Boolean, onClick: () -> Unit, onToggleDone: () -> Unit) {
    val colors = LocalWorktreeColors.current
    ListRow(
        selected = selected,
        onClick = onClick,
        height = TASK_ROW_HEIGHT,
        padding = PaddingValues(horizontal = 8.dp),
    ) {
        // The box is its own click target: ticking a task off is not the same act as opening it,
        // and a list where the two share a hit area finishes tasks people meant to read.
        TaskCheck(task.done, onToggleDone)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = task.title,
                color = when {
                    task.done -> colors.textDisabled
                    task.isEmpty -> colors.textDisabled
                    else -> colors.text
                },
                // Struck through as well as dimmed: colour alone is what a colour-blind reader
                // cannot use, and it is also what a selected row's own background takes away.
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.preview.isNotEmpty()) {
                Text(
                    text = task.preview,
                    color = colors.textDisabled,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TaskCheck(done: Boolean, onToggle: () -> Unit) {
    val colors = LocalWorktreeColors.current
    Box(
        modifier = Modifier
            .size(13.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (done) colors.accent else Color.Transparent)
            .border(1.dp, if (done) colors.accent else colors.controlBorder, RoundedCornerShape(3.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (done) IdeIcon(IconKind.CHECK, Color.White, size = 9.dp)
    }
}

/** Wide enough for a first line, narrow enough to leave the writing room. */
private val TASK_LIST_WIDTH = 240.dp

/** Two lines of text: a task's first line and its second. */
private val TASK_ROW_HEIGHT = 40.dp
