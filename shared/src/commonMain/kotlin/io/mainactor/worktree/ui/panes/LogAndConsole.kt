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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mainactor.worktree.AppState
import io.mainactor.worktree.git.GitLogEntry
import io.mainactor.worktree.model.CommitInfo
import io.mainactor.worktree.ui.components.Badge
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.theme.CodeTextStyle
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/** Commit history of the selected worktree — enough to pick a ref for merge or rebase. */
@Composable
fun LogPane(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    if (state.commits.isEmpty()) {
        EmptyState("No commits yet.", modifier)
        return
    }
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.editor)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.commits, key = { it.hash }) { commit -> CommitRow(commit) }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun CommitRow(commit: CommitInfo) {
    val colors = LocalWorktreeColors.current
    ListRow(
        selected = false,
        onClick = {},
        height = 26.dp,
        padding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(
            text = commit.shortHash,
            color = colors.textDisabled,
            style = CodeTextStyle,
            modifier = Modifier.width(62.dp),
        )
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
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = commit.relativeDate,
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(96.dp),
        )
    }
}

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
