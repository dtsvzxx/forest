package io.mainactor.worktree.ui.panes

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import io.mainactor.worktree.AppState
import io.mainactor.worktree.git.GitLogEntry
import io.mainactor.worktree.model.CommitInfo
import io.mainactor.worktree.ui.components.Badge
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalSplitter
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.components.VerticalSplitter
import io.mainactor.worktree.ui.theme.CodeTextStyle
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * Commit history of the selected worktree, and what the selected commit changed.
 *
 * The detail sits *below* the list rather than beside it because a commit row is wide — hash,
 * refs, subject, author, age — and taking half the width for a detail pane would truncate the
 * subject, which is the column the list is actually scanned by. Within the detail the files go
 * beside the patch, which is the arrangement the Changes tab already uses one axis at a time.
 */
@Composable
fun LogPane(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    if (state.commits.isEmpty()) {
        EmptyState("No commits yet.", modifier)
        return
    }

    var listHeight by remember { mutableStateOf(220.dp) }
    var filesWidth by remember { mutableStateOf(280.dp) }
    var pane by remember { mutableStateOf(DpSize.Zero) }
    val density = LocalDensity.current

    // Squeezed against the pane rather than clamped, so the stored size comes back when there is
    // room again — the same behaviour as the window's own panes.
    val shownListHeight =
        if (pane.height > 0.dp) minOf(listHeight, pane.height * COMMIT_LIST_MAX_SHARE) else listHeight
    val shownFilesWidth =
        if (pane.width > 0.dp) minOf(filesWidth, pane.width * COMMIT_FILES_MAX_SHARE) else filesWidth

    Column(
        modifier.fillMaxSize().background(colors.editor).onSizeChanged {
            pane = with(density) { DpSize(it.width.toDp(), it.height.toDp()) }
        },
    ) {
        Box(Modifier.fillMaxWidth().height(shownListHeight)) { CommitList(state) }
        // Drawn on `editor`, where `border` is the identical Gray1; see SearchPane.
        HorizontalSplitter(
            size = listHeight,
            onSizeChange = { listHeight = it },
            min = 80.dp,
            max = 720.dp,
            color = colors.separator,
        )
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.width(shownFilesWidth).fillMaxHeight().background(colors.panel)) {
                CommitFiles(state)
            }
            VerticalSplitter(
                size = filesWidth,
                onSizeChange = { filesWidth = it },
                min = 160.dp,
                max = 520.dp,
                color = colors.separator,
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                state.commitFile?.let { DiffHeader(it) }
                DiffView(
                    diff = state.commitFile,
                    loading = state.commitDiffLoading,
                    emptyText = if (state.selectedCommit == null) {
                        "Select a commit above to see what it changed."
                    } else {
                        "Select a file."
                    },
                )
            }
        }
    }
}

@Composable
private fun CommitList(state: AppState) {
    val listState = rememberLazyListState()
    // The hash column is monospace and every hash is the same length, so padding to the widest is
    // exact alignment without measuring text — and, unlike the fixed dp width this column used to
    // carry, it cannot be a hair too narrow and wrap the hash onto a second, clipped line.
    val hashWidth = state.commits.maxOf { it.shortHash.length }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.commits, key = { it.hash }) { commit ->
                CommitRow(
                    commit = commit,
                    hashWidth = hashWidth,
                    selected = state.selectedCommit?.hash == commit.hash,
                    onClick = { state.selectCommit(commit) },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun CommitRow(
    commit: CommitInfo,
    hashWidth: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalWorktreeColors.current
    ListRow(
        selected = selected,
        onClick = onClick,
        height = 26.dp,
        padding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(
            text = commit.shortHash.padEnd(hashWidth),
            color = colors.textDisabled,
            style = CodeTextStyle,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(8.dp))
        commit.refs.take(3).forEach { ref ->
            Badge(
                text = ref.removePrefix("HEAD -> "),
                color = if (ref.startsWith("HEAD")) colors.added else colors.branchRemote,
            )
            Spacer(Modifier.width(4.dp))
        }
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
            text = commit.author,
            color = colors.textDim,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = commit.relativeDate,
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp),
        )
    }
}

/** The files the selected commit touched. */
@Composable
private fun CommitFiles(state: AppState) {
    val colors = LocalWorktreeColors.current
    val files = state.commitFiles
    when {
        state.selectedCommit == null -> EmptyState("Select a commit.")
        state.commitDiffLoading -> EmptyState("Loading…")
        // A merge that brought nothing over its first parent really did change no files.
        files.isEmpty() -> EmptyState("This commit changed no files.")
        else -> {
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item { SectionHeader("Files", files.size, colors.modified) }
                    items(files, key = { it.path }) { file ->
                        FileDiffRow(file, state.commitFile?.path == file.path) {
                            state.selectCommitFile(file)
                        }
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

/** How much of the Log tab the commit list may take when the pane gets short. */
private const val COMMIT_LIST_MAX_SHARE = 0.7f

/** The same, for the file list against the pane's width. */
private const val COMMIT_FILES_MAX_SHARE = 0.5f

/**
 * Every git command the app ran, with its output. Nothing this UI does to a repository is
 * hidden behind a button label — if a button surprises you, the exact invocation is here.
 */
@Composable
fun ConsolePane(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    val listState = rememberLazyListState()

    LaunchedEffect(state.gitLog.size) {
        if (state.gitLog.isNotEmpty()) listState.scrollToItem(state.gitLog.lastIndex)
    }

    if (state.gitLog.isEmpty()) {
        EmptyState("No git commands run yet.", modifier)
        return
    }

    Box(modifier.fillMaxSize().background(colors.editor)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            items(state.gitLog.toList(), key = { it.seq }) { entry -> ConsoleEntry(entry) }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun ConsoleEntry(entry: GitLogEntry) {
    val colors = LocalWorktreeColors.current
    val hScroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (entry.ok) "$" else "!",
                color = if (entry.ok) colors.added else colors.conflicted,
                style = CodeTextStyle,
            )
            Text(
                text = entry.command,
                color = colors.text,
                style = CodeTextStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!entry.ok) {
                Text("exit ${entry.exitCode}", color = colors.conflicted, fontSize = 10.sp)
            }
        }
        if (entry.output.isNotBlank()) {
            Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                Text(
                    text = entry.output.lines().take(MAX_OUTPUT_LINES).joinToString("\n"),
                    color = if (entry.ok) colors.textDim else colors.conflicted.copy(alpha = 0.85f),
                    style = CodeTextStyle,
                    softWrap = false,
                    modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
                )
            }
        }
    }
}

private const val MAX_OUTPUT_LINES = 40
