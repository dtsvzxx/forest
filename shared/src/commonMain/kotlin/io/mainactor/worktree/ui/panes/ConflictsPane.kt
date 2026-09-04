package io.mainactor.worktree.ui.panes

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.mainactor.worktree.AppState
import io.mainactor.worktree.label
import io.mainactor.worktree.model.ConflictSegment
import io.mainactor.worktree.model.ConflictedFile
import io.mainactor.worktree.model.RepoOperation
import io.mainactor.worktree.model.Resolution
import io.mainactor.worktree.ui.components.Badge
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.components.HorizontalDivider
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeButton
import io.mainactor.worktree.ui.components.IdeIcon
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.components.VerticalDivider
import io.mainactor.worktree.ui.theme.CodeTextStyle
import io.mainactor.worktree.ui.theme.Dimens
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * Conflict resolution.
 *
 * Rather than a three-way editor, this is a region-by-region chooser over the markers git already
 * wrote into the file: every `<<<<<<< / ======= / >>>>>>>` block becomes a card with the two sides
 * side by side and four ways to settle it. Applying writes the merged text back and stages it,
 * which is exactly what "resolved" means to git.
 */
@Composable
fun ConflictsPane(state: AppState, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    val conflicts = state.status.conflicts

    Column(modifier.fillMaxSize().background(colors.panel)) {
        OperationBanner(state)

        if (conflicts.isEmpty()) {
            EmptyState("No conflicts in this worktree.")
            return@Column
        }

        Row(Modifier.weight(1f)) {
            Column(Modifier.width(240.dp).fillMaxHeight().background(colors.panel)) {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(conflicts, key = { it.path }) { file ->
                        ListRow(
                            selected = state.conflictFile?.path == file.path,
                            onClick = { state.openConflict(file.path) },
                            height = 40.dp,
                            padding = PaddingValues(horizontal = 8.dp),
                        ) {
                            IdeIcon(IconKind.WARNING, colors.conflicted, size = 12.dp)
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    color = colors.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    file.directory,
                                    color = colors.textDisabled,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                        }
                    }
                }
            }
            VerticalDivider()
            Box(Modifier.weight(1f)) {
                when {
                    state.conflictLoading -> EmptyState("Loading…")
                    state.conflictFile == null -> EmptyState("Select a conflicted file.")
                    else -> ConflictEditor(state, state.conflictFile!!)
                }
            }
        }
    }
}

/** The strip that appears while a merge/rebase is stopped, offering the three ways out. */
@Composable
private fun OperationBanner(state: AppState) {
    val colors = LocalWorktreeColors.current
    val operation = state.status.operation
    if (operation == RepoOperation.NONE) return

    val unresolved = state.status.conflicts.size
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.warning.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IdeIcon(IconKind.WARNING, colors.warning)
            Text(
                text = buildString {
                    append("A ${operation.label} is in progress")
                    if (unresolved > 0) append(" — $unresolved file(s) still conflicted")
                },
                color = colors.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IdeButton(
                text = "Continue",
                onClick = state::continueOperation,
                enabled = unresolved == 0,
                primary = unresolved == 0,
            )
            if (operation == RepoOperation.REBASE || operation == RepoOperation.CHERRY_PICK) {
                IdeButton("Skip", onClick = state::skipCommit)
            }
            IdeButton("Abort", onClick = state::abortOperation)
        }
        HorizontalDivider()
    }
}

@Composable
private fun ConflictEditor(state: AppState, file: ConflictedFile) {
    val colors = LocalWorktreeColors.current
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(colors.panel)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                file.path,
                color = colors.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            Badge(
                text = if (file.isFullyResolved) "resolved" else "${file.unresolvedCount} left",
                color = if (file.isFullyResolved) colors.success else colors.conflicted,
            )
            Spacer(Modifier.width(4.dp))
            Text("Use whole file:", color = colors.textDim, fontSize = 11.sp)
            ChoiceButton("ours", active = false, accent = colors.oursAccent) {
                state.takeWholeSide(file.path, ours = true)
            }
            ChoiceButton("theirs", active = false, accent = colors.theirsAccent) {
                state.takeWholeSide(file.path, ours = false)
            }
        }
        HorizontalDivider()

        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(colors.editor)) {
                items(file.segments.size, key = { it }) { index ->
                    when (val segment = file.segments[index]) {
                        is ConflictSegment.Text -> ContextBlock(segment.lines)
                        is ConflictSegment.Conflict -> ConflictCard(
                            segment = segment,
                            onResolve = { state.resolveRegion(segment.id, it) },
                        )
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.panel).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Accept all:", color = colors.textDim, style = MaterialTheme.typography.bodySmall)
            ChoiceButton("Ours", active = false, accent = colors.oursAccent) {
                state.resolveAll(Resolution.OURS)
            }
            ChoiceButton("Theirs", active = false, accent = colors.theirsAccent) {
                state.resolveAll(Resolution.THEIRS)
            }
            Spacer(Modifier.weight(1f))
            IdeButton(
                text = "Resolve & stage",
                onClick = state::applyConflictResolution,
                enabled = file.isFullyResolved,
                primary = file.isFullyResolved,
                icon = IconKind.CHECK,
            )
        }
    }
}

/** Unconflicted text between regions, collapsed to its edges so the cards stay close together. */
@Composable
private fun ContextBlock(lines: List<String>) {
    val colors = LocalWorktreeColors.current
    var expanded by remember { mutableStateOf(false) }
    val collapsedLimit = 6
    val shown = if (expanded || lines.size <= collapsedLimit) {
        lines
    } else {
        lines.take(3) + listOf(ELLIPSIS_MARKER) + lines.takeLast(2)
    }
    val hScroll = rememberScrollState()

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        shown.forEach { line ->
            if (line === ELLIPSIS_MARKER) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(colors.panelAlt)
                        .clickable { expanded = true }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "… ${lines.size - 5} more lines",
                        color = colors.textDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                    Text(
                        text = line.ifEmpty { " " },
                        style = CodeTextStyle,
                        color = colors.textDim,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }
}

private val ELLIPSIS_MARKER = String(charArrayOf('…'))

@Composable
private fun ConflictCard(
    segment: ConflictSegment.Conflict,
    onResolve: (Resolution) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    val region = segment.region
    val resolved = segment.resolution != Resolution.UNRESOLVED

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(Dimens.arc))
            .border(
                width = 1.dp,
                color = if (resolved) colors.success.copy(alpha = 0.55f) else colors.conflicted.copy(alpha = 0.65f),
                shape = RoundedCornerShape(Dimens.arc),
            )
            .background(colors.panel),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Conflict ${segment.id + 1}",
                color = colors.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            if (resolved) {
                Badge(
                    text = when (segment.resolution) {
                        Resolution.OURS -> "ours"
                        Resolution.THEIRS -> "theirs"
                        Resolution.BOTH_OURS_FIRST -> "both, ours first"
                        Resolution.BOTH_THEIRS_FIRST -> "both, theirs first"
                        Resolution.UNRESOLVED -> ""
                    },
                    color = colors.success,
                )
            }
            Spacer(Modifier.weight(1f))
            ChoiceButton("Ours", segment.resolution == Resolution.OURS, colors.oursAccent) {
                onResolve(Resolution.OURS)
            }
            ChoiceButton("Theirs", segment.resolution == Resolution.THEIRS, colors.theirsAccent) {
                onResolve(Resolution.THEIRS)
            }
            ChoiceButton("Both", segment.resolution == Resolution.BOTH_OURS_FIRST, colors.accent) {
                onResolve(Resolution.BOTH_OURS_FIRST)
            }
            if (resolved) {
                ChoiceButton("Reset", false, colors.textDim) { onResolve(Resolution.UNRESOLVED) }
            }
        }

        Row(Modifier.fillMaxWidth()) {
            SidePanel(
                title = region.oursLabel,
                subtitle = "ours",
                lines = region.ours,
                background = colors.oursBg,
                accent = colors.oursAccent,
                dimmed = segment.resolution == Resolution.THEIRS,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            SidePanel(
                title = region.theirsLabel,
                subtitle = "theirs",
                lines = region.theirs,
                background = colors.theirsBg,
                accent = colors.theirsAccent,
                dimmed = segment.resolution == Resolution.OURS,
                modifier = Modifier.weight(1f),
            )
        }

        region.base?.takeIf { it.isNotEmpty() }?.let { base ->
            HorizontalDivider()
            SidePanel(
                title = "common ancestor",
                subtitle = "base",
                lines = base,
                background = colors.baseBg,
                accent = colors.warning,
                dimmed = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChoiceButton(text: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val colors = LocalWorktreeColors.current
    Box(
        Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) accent.copy(alpha = 0.30f) else Color.Transparent)
            .border(1.dp, if (active) accent else colors.controlBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (active) colors.text else colors.textDim, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun SidePanel(
    title: String,
    subtitle: String,
    lines: List<String>,
    background: Color,
    accent: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    val hScroll = rememberScrollState()

    Column(modifier.background(background.copy(alpha = if (dimmed) 0.35f else 1f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.width(3.dp).height(11.dp).background(accent))
            Text(subtitle, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(
                text = title,
                color = colors.textDim,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        when {
            lines.isEmpty() -> Text(
                "(empty)",
                color = colors.textDisabled,
                style = CodeTextStyle,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )

            else -> Column(Modifier.fillMaxWidth()) {
                lines.take(MAX_SIDE_LINES).forEach { line ->
                    Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                        Text(
                            text = line.ifEmpty { " " },
                            style = CodeTextStyle,
                            color = if (dimmed) colors.textDim else colors.text,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
                if (lines.size > MAX_SIDE_LINES) {
                    Text(
                        "… ${lines.size - MAX_SIDE_LINES} more lines (resolve to see the full text)",
                        color = colors.textDisabled,
                        style = CodeTextStyle,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** Conflict sides are rendered eagerly, so cap absurdly long regions. */
private const val MAX_SIDE_LINES = 300
