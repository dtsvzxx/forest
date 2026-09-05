package io.mainactor.worktree.ui.panes

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mainactor.worktree.AppState
import io.mainactor.worktree.DiffMode
import io.mainactor.worktree.model.ChangedFile
import io.mainactor.worktree.model.FileDiff
import io.mainactor.worktree.ui.components.Badge
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.HorizontalSplitter
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeIcon
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.ui.theme.CodeTextStyle
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * Right pane: what changed in the selected worktree, and the diff of whatever is selected.
 *
 * Two modes share the layout — the uncommitted working tree, and everything the worktree's
 * branch carries over a base ref, which is the view that actually answers "what is this
 * worktree for?".
 */
@Composable
fun ChangesPane(
    state: AppState,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    // The file list keeps its height; the diff below it takes whatever the pane gains.
    var fileListHeight by remember { mutableStateOf(190.dp) }
    var paneHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    val shownListHeight = if (paneHeight > 0.dp) {
        minOf(fileListHeight, paneHeight * FILE_LIST_MAX_SHARE)
    } else {
        fileListHeight
    }

    Column(
        modifier.fillMaxSize().background(colors.panel).onSizeChanged {
            paneHeight = with(density) { it.height.toDp() }
        },
    ) {
        ChangesToolbar(state, onCommit)
        HorizontalDivider()

        Box(Modifier.fillMaxWidth().height(shownListHeight)) {
            when (state.diffMode) {
                DiffMode.WORKING_TREE -> WorkingTreeFileList(state)
                DiffMode.AGAINST_BASE -> RangeFileList(state)
            }
        }
        HorizontalSplitter(
            size = fileListHeight,
            onSizeChange = { fileListHeight = it },
            min = 70.dp,
            max = 640.dp,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                state.diff?.let { DiffHeader(it, state) }
                DiffView(
                    diff = state.diff,
                    loading = state.diffLoading,
                    highlighter = state.highlighter,
                    emptyText = when (state.diffMode) {
                        DiffMode.WORKING_TREE -> "Select a changed file above."
                        DiffMode.AGAINST_BASE -> "Select a file above."
                    },
                )
            }
        }
    }
}

@Composable
private fun ChangesToolbar(state: AppState, onCommit: () -> Unit) {
    val colors = LocalWorktreeColors.current
    val status = state.status
    val selected = state.selectedFile

    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp).background(colors.panel).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ModeToggle(
            label = "Working tree",
            selected = state.diffMode == DiffMode.WORKING_TREE,
            onClick = { state.setDiffMode(DiffMode.WORKING_TREE) },
        )
        ModeToggle(
            label = "vs ${state.baseRef ?: "base"}",
            selected = state.diffMode == DiffMode.AGAINST_BASE,
            onClick = { state.setDiffMode(DiffMode.AGAINST_BASE) },
        )

        Spacer(Modifier.weight(1f))

        if (state.diffMode == DiffMode.WORKING_TREE) {
            ToolButton(
                icon = IconKind.STAGE,
                tooltip = "Stage the selected file — include it in the next commit",
                detail = "git add ${selected?.name.orEmpty()}",
                onClick = { selected?.let { state.stage(listOf(it)) } },
                enabled = selected != null && selected.unstaged,
            )
            ToolButton(
                icon = IconKind.UNSTAGE,
                tooltip = "Unstage the selected file — keep the edit, drop it from the commit",
                detail = "git restore --staged ${selected?.name.orEmpty()}",
                onClick = { selected?.let { state.unstage(listOf(it)) } },
                enabled = selected != null && selected.staged,
            )
            ToolButton(
                icon = IconKind.REVERT,
                tooltip = "Discard the selected file's changes — this cannot be undone",
                detail = "git restore ${selected?.name.orEmpty()}",
                onClick = { selected?.let { state.discard(listOf(it)) } },
                enabled = selected != null && selected.unstaged,
            )
            ToolButton(
                icon = IconKind.CHECK,
                tooltip = "Stage every change in this worktree",
                detail = "git add --all",
                onClick = state::stageAll,
                enabled = !status.isClean,
            )
            ToolButton(
                icon = IconKind.COMMIT,
                tooltip = "Commit the staged changes",
                detail = "git commit",
                onClick = onCommit,
                enabled = !status.isClean,
                tint = if (status.staged.isNotEmpty()) colors.added else null,
            )
        } else {
            BaseRefPicker(state)
        }
    }
}

@Composable
private fun ModeToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalWorktreeColors.current
    Box(
        Modifier
            .height(24.dp)
            .background(
                if (selected) colors.panelAlt else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) colors.text else colors.textDim,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}

/** Lets the user pick which ref the "vs base" comparison runs against. */
@Composable
private fun BaseRefPicker(state: AppState) {
    val colors = LocalWorktreeColors.current
    var open by remember { mutableStateOf(false) }
    val local = state.branches.filterNot { it.isRemote }

    Box {
        Row(
            modifier = Modifier
                .height(22.dp)
                .clickable { open = true }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IdeIcon(IconKind.BRANCH, colors.textDim, size = 12.dp)
            Text(
                state.baseRef ?: "choose base…",
                color = colors.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            IdeIcon(IconKind.CHEVRON_DOWN, colors.textDim, size = 10.dp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.panelAlt),
        ) {
            local.forEach { branch ->
                DropdownMenuItem(
                    text = {
                        Text(branch.name, color = colors.text, style = MaterialTheme.typography.bodySmall)
                    },
                    onClick = {
                        open = false
                        state.setBaseRef(branch.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkingTreeFileList(state: AppState) {
    val colors = LocalWorktreeColors.current
    val status = state.status
    if (status.isClean) {
        EmptyState("Working tree is clean.")
        return
    }

    val listState = rememberLazyListState()
    val conflicts = status.conflicts
    val staged = status.staged
    val unstaged = status.files.filter { it.unstaged && !it.conflicted }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (conflicts.isNotEmpty()) {
                item { SectionHeader("Conflicts", conflicts.size, colors.conflicted) }
                items(conflicts, key = { "c:${it.path}" }) { file ->
                    FileRow(file, state.selectedFile?.path == file.path) { state.selectFile(file) }
                }
            }
            if (staged.isNotEmpty()) {
                item { SectionHeader("Staged", staged.size, colors.added) }
                items(staged, key = { "s:${it.path}" }) { file ->
                    FileRow(file, state.selectedFile?.path == file.path) { state.selectFile(file) }
                }
            }
            if (unstaged.isNotEmpty()) {
                item { SectionHeader("Changes", unstaged.size, colors.modified) }
                items(unstaged, key = { "u:${it.path}" }) { file ->
                    FileRow(file, state.selectedFile?.path == file.path) { state.selectFile(file) }
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
private fun RangeFileList(state: AppState) {
    val diffs = state.rangeDiffs
    if (diffs.isEmpty()) {
        EmptyState(
            if (state.baseRef == null) "Pick a base branch to compare against." else "No differences from ${state.baseRef}.",
        )
        return
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { SectionHeader("Changed vs ${state.baseRef}", diffs.size, LocalWorktreeColors.current.modified) }
            items(diffs, key = { it.path }) { fileDiff ->
                FileDiffRow(fileDiff, state.diff?.path == fileDiff.path) { state.selectRangeFile(fileDiff) }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
internal fun SectionHeader(title: String, count: Int, color: Color) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            color = colors.textDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Badge("$count", color)
    }
}

@Composable
private fun FileRow(file: ChangedFile, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalWorktreeColors.current
    val statusColor = when {
        file.conflicted -> colors.conflicted
        file.untracked -> colors.untracked
        file.badge == 'D' -> colors.deleted
        file.badge == 'A' -> colors.added
        file.badge == 'R' -> colors.renamed
        else -> colors.modified
    }
    ListRow(selected = selected, onClick = onClick, padding = PaddingValues(start = 8.dp, end = 8.dp)) {
        Text(
            text = file.badge.toString(),
            color = statusColor,
            style = CodeTextStyle,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = file.name,
            color = if (file.badge == 'D') colors.textDim else colors.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = file.directory,
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        if (file.staged && file.unstaged) Badge("partial", colors.warning)
    }
}

/** One row of a list of [FileDiff]s — the range comparison and the Log tab's commit both use it. */
@Composable
internal fun FileDiffRow(diff: FileDiff, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalWorktreeColors.current
    val badge = when {
        diff.isNew -> "A"
        diff.isDeleted -> "D"
        diff.isRename -> "R"
        else -> "M"
    }
    ListRow(selected = selected, onClick = onClick, padding = PaddingValues(start = 8.dp, end = 8.dp)) {
        Text(
            text = badge,
            color = when (badge) {
                "A" -> colors.added
                "D" -> colors.deleted
                "R" -> colors.renamed
                else -> colors.modified
            },
            style = CodeTextStyle,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = diff.path.substringAfterLast('/'),
            color = colors.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = diff.path.substringBeforeLast('/', ""),
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        DiffStat(diff)
    }
}

@Composable
internal fun DiffHeader(diff: FileDiff, state: AppState? = null) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(colors.panel)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = diff.oldPath?.let { "$it → ${diff.path}" } ?: diff.path,
            color = colors.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        DiffStat(diff)
        // Absent where there is no state to ask — a header rendered on its own in a test.
        state?.let { WholeFileToggle(it) }
    }
}

/**
 * Switches the diff between the changed lines and the whole file.
 *
 * Three lines of context answers "what changed" and not "what does this look like now", which is
 * the question you have as soon as the change is more than a typo. It lives in the header rather
 * than in a pane's toolbar because it belongs to the diff being read, and one of these serves all
 * three places a diff is shown.
 */
@Composable
private fun WholeFileToggle(state: AppState) {
    val colors = LocalWorktreeColors.current
    ToolButton(
        icon = IconKind.EXPAND,
        tooltip = if (state.wholeFileDiff) {
            "Showing the whole file — click for just the changes"
        } else {
            "Show the whole file, not only the changed lines"
        },
        detail = if (state.wholeFileDiff) "git diff -U3" else "git diff -U1000000",
        onClick = { state.showWholeFile(!state.wholeFileDiff) },
        tint = if (state.wholeFileDiff) colors.accent else colors.textDim,
    )
}

/** How much of the changes pane the file list may take when the pane gets short. */
private const val FILE_LIST_MAX_SHARE = 0.7f
