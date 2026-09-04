package io.mainactor.worktree.ui.panes

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.mainactor.worktree.AppState
import io.mainactor.worktree.model.CommitInfo
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.HorizontalSplitter
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeTextField
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.components.VerticalSplitter
import io.mainactor.worktree.ui.theme.CodeTextStyle
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * Find a file by name, then see every commit that touched it.
 *
 * The two halves are one feature: the question behind "where is this file" is almost always "and
 * what has been done to it". So the pane reads top to bottom — query, the files that match, then
 * the history of whichever one is selected beside that commit's patch for it.
 *
 * The search itself never spawns a process. `git ls-files` runs once per worktree and
 * [AppState.search] filters the result in memory, because a child process per keystroke on a tree
 * of twenty thousand files is the difference between instant and unusable.
 */
@Composable
fun SearchPane(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    var listHeight by remember { mutableStateOf(220.dp) }
    var historyWidth by remember { mutableStateOf(330.dp) }
    var pane by remember { mutableStateOf(DpSize.Zero) }
    val density = LocalDensity.current

    val shownListHeight =
        if (pane.height > 0.dp) minOf(listHeight, pane.height * RESULTS_MAX_SHARE) else listHeight
    val shownHistoryWidth =
        if (pane.width > 0.dp) minOf(historyWidth, pane.width * HISTORY_MAX_SHARE) else historyWidth

    Column(
        modifier.fillMaxSize().background(colors.editor).onSizeChanged {
            pane = with(density) { DpSize(it.width.toDp(), it.height.toDp()) }
        },
    ) {
        SearchField(state)
        HorizontalDivider()

        Box(Modifier.fillMaxWidth().height(shownListHeight)) { Results(state) }
        HorizontalSplitter(
            size = listHeight,
            onSizeChange = { listHeight = it },
            min = 80.dp,
            max = 720.dp,
        )

        Row(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.width(shownHistoryWidth).fillMaxHeight().background(colors.panel)) {
                History(state)
            }
            VerticalSplitter(
                size = historyWidth,
                onSizeChange = { historyWidth = it },
                min = 200.dp,
                max = 560.dp,
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                state.fileDiff?.let { DiffHeader(it) }
                DiffView(
                    diff = state.fileDiff,
                    loading = state.fileDiffLoading,
                    emptyText = when {
                        state.searchFile == null -> "Find a file above to see where it changed."
                        state.fileCommits.isEmpty() -> "No commit has touched this file."
                        state.fileCommit == null -> "Select a commit."
                        // `--follow` reaches back past renames, and git has no patch to give for
                        // this path in a commit where the file was called something else.
                        else -> "This file was named differently in this commit."
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchField(state: AppState) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdeTextField(
            value = state.searchQuery,
            onValueChange = state::search,
            placeholder = when {
                state.searchIndexing -> "Reading the file list…"
                state.searchIndexSize > 0 -> "Find among ${state.searchIndexSize} files…"
                else -> "Find a file by name…"
            },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (state.searchQuery.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            ToolButton(
                icon = IconKind.CLOSE,
                tooltip = "Clear the search",
                onClick = { state.search("") },
                modifier = Modifier.size(18.dp),
                tint = colors.textDim,
            )
        }
    }
}

@Composable
private fun Results(state: AppState) {
    val colors = LocalWorktreeColors.current
    val results = state.searchResults
    when {
        state.searchQuery.isBlank() -> EmptyState("Type part of a file name.")
        state.searchIndexing && results.isEmpty() -> EmptyState("Reading the file list…")
        results.isEmpty() -> EmptyState("No tracked file matches \"${state.searchQuery}\".")
        else -> {
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item { SectionHeader("Files", results.size, colors.modified) }
                    items(results, key = { it }) { path ->
                        ResultRow(path, state.searchFile == path) { state.selectSearchFile(path) }
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

@Composable
private fun ResultRow(path: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalWorktreeColors.current
    ListRow(selected = selected, onClick = onClick, padding = PaddingValues(horizontal = 8.dp)) {
        Text(
            text = path.substringAfterLast('/'),
            color = colors.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = path.substringBeforeLast('/', ""),
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Every commit that touched the selected file. */
@Composable
private fun History(state: AppState) {
    val colors = LocalWorktreeColors.current
    val commits = state.fileCommits
    when {
        state.searchFile == null -> EmptyState("Select a file.")
        state.fileHistoryLoading -> EmptyState("Loading…")
        commits.isEmpty() -> EmptyState("No commit has touched this file.")
        else -> {
            val listState = rememberLazyListState()
            val hashWidth = commits.maxOf { it.shortHash.length }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item { SectionHeader("Commits", commits.size, colors.branchRemote) }
                    items(commits, key = { it.hash }) { commit ->
                        HistoryRow(
                            commit = commit,
                            hashWidth = hashWidth,
                            selected = state.fileCommit?.hash == commit.hash,
                            onClick = { state.selectFileCommit(commit) },
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

/**
 * A commit in the narrow history column.
 *
 * Deliberately not the Log tab's row: that one has fixed author and date columns sized for a
 * full-width pane, and at this width they would leave nothing for the subject.
 */
@Composable
private fun HistoryRow(
    commit: CommitInfo,
    hashWidth: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalWorktreeColors.current
    ListRow(selected = selected, onClick = onClick, padding = PaddingValues(horizontal = 8.dp)) {
        Text(
            text = commit.shortHash.padEnd(hashWidth),
            color = colors.textDisabled,
            style = CodeTextStyle,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = commit.subject,
            color = colors.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = commit.relativeDate,
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** How much of the pane the result list may take when it gets short. */
private const val RESULTS_MAX_SHARE = 0.7f

/** The same, for the history column against the pane's width. */
private const val HISTORY_MAX_SHARE = 0.5f
