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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mainactor.worktree.AppState
import io.mainactor.worktree.model.Note
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeTextField
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.components.VerticalSplitter
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * Where ideas are kept, in the form they will be used: as prompts.
 *
 * A scratchpad rather than a document. There is no save button and no file to name, because the
 * thing this competes with is a note in another application that never comes back — and every step
 * between having the thought and writing it down is a reason not to.
 *
 * The pane earns its place from the other end: on the agent wall a note is one right-click from
 * being handed to a running agent, which is what makes writing it down worth doing.
 */
@Composable
fun NotesPane(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    val selected = state.notes.firstOrNull { it.id == state.selectedNote }

    Column(modifier.fillMaxSize().background(colors.panel)) {
        NotesToolbar(state, selected)
        HorizontalDivider()

        if (state.notes.isEmpty()) {
            EmptyState("No notes yet. Write down an idea and hand it to an agent later.")
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            NoteList(state, Modifier.width(NOTE_LIST_WIDTH).fillMaxHeight())
            VerticalSplitter(size = NOTE_LIST_WIDTH, onSizeChange = {}, min = NOTE_LIST_WIDTH, max = NOTE_LIST_WIDTH)
            Box(Modifier.weight(1f).fillMaxHeight().background(colors.editor)) {
                if (selected == null) {
                    EmptyState("Select a note.")
                } else {
                    // Keyed by id so moving between notes rebuilds the field rather than carrying
                    // one note's cursor position into another's text.
                    IdeTextField(
                        value = selected.body,
                        onValueChange = { state.updateNote(selected.id, it) },
                        placeholder = "The idea, written as you would say it to an agent.",
                        singleLine = false,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesToolbar(state: AppState, selected: Note?) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolButton(
            icon = IconKind.PLUS,
            tooltip = "Write down a new idea",
            onClick = state::addNote,
        )
        ToolButton(
            icon = IconKind.MINUS,
            tooltip = "Delete this note",
            onClick = { selected?.let { state.deleteNote(it.id) } },
            enabled = selected != null,
        )
        Text(
            text = if (state.notes.isEmpty()) "" else "${state.notes.size} note${if (state.notes.size == 1) "" else "s"}",
            color = colors.textDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Right-click an agent to send one",
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoteList(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    val listState = rememberLazyListState()
    Box(modifier.background(colors.panel)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.notes, key = { it.id }) { note ->
                ContextMenuArea(
                    items = {
                        listOf(
                            ContextMenuItem("Copy") { state.system.copyToClipboard(note.body) },
                            ContextMenuItem("Delete") { state.deleteNote(note.id) },
                        )
                    },
                ) {
                    NoteRow(note, state.selectedNote == note.id) { state.selectNote(note.id) }
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
private fun NoteRow(note: Note, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalWorktreeColors.current
    ListRow(
        selected = selected,
        onClick = onClick,
        height = 40.dp,
        padding = PaddingValues(horizontal = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = note.title,
                color = if (note.isEmpty) colors.textDisabled else colors.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.preview.isNotEmpty()) {
                Text(
                    text = note.preview,
                    color = colors.textDisabled,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Wide enough for a first line, narrow enough to leave the writing room. */
private val NOTE_LIST_WIDTH = 240.dp
