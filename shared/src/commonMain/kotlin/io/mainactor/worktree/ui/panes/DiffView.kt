package io.mainactor.worktree.ui.panes

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.mainactor.worktree.model.DiffLine
import io.mainactor.worktree.model.DiffLineType
import io.mainactor.worktree.model.FileDiff
import io.mainactor.worktree.ui.components.EmptyState
import io.mainactor.worktree.ui.theme.CodeTextStyle
import io.mainactor.worktree.ui.theme.Dimens
import io.mainactor.worktree.ui.theme.LocalWorktreeColors
import kotlin.math.max

/** One rendered row of the diff: either a `@@` separator or a real line. */
private sealed interface DiffRow {
    data class Header(val text: String) : DiffRow
    data class Line(val line: DiffLine) : DiffRow
}

/**
 * Unified diff viewer.
 *
 * The gutter is pinned while the code scrolls horizontally. That works because every row's
 * scrollable half is given the *same* fixed content width (the longest line, measured once):
 * rows of differing widths would clamp their scroll offsets differently and drift apart.
 */
@Composable
fun DiffView(
    diff: FileDiff?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    emptyText: String = "Select a file to see its diff.",
) {
    val colors = LocalWorktreeColors.current

    if (loading) {
        EmptyState("Loading diff…", modifier)
        return
    }
    if (diff == null) {
        EmptyState(emptyText, modifier)
        return
    }
    if (diff.isBinary) {
        EmptyState("Binary file — no textual diff.", modifier)
        return
    }
    if (diff.hunks.isEmpty()) {
        EmptyState(
            when {
                diff.isRename -> "Renamed from ${diff.oldPath} — no content changes."
                else -> "No changes to show."
            },
            modifier,
        )
        return
    }

    val rows = remember(diff) {
        buildList {
            for (hunk in diff.hunks) {
                add(DiffRow.Header(hunk.header))
                hunk.lines.forEach { add(DiffRow.Line(it)) }
            }
        }
    }

    // Monospace: one measurement of a single glyph gives the width of every column.
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(measurer) { measurer.measure("0", CodeTextStyle).size.width.toFloat() }
    val longest = remember(rows) {
        rows.maxOf { row ->
            when (row) {
                is DiffRow.Header -> row.text.length
                is DiffRow.Line -> row.line.text.length + 1
            }
        }
    }
    val contentWidth = with(LocalDensity.current) { (max(longest, 40) * charWidthPx).toDp() + 24.dp }

    val vScroll = rememberLazyListState()
    val hScroll = rememberScrollState()

    Column(modifier.fillMaxSize().background(colors.editor)) {
        Box(Modifier.weight(1f)) {
            LazyColumn(state = vScroll, modifier = Modifier.fillMaxSize()) {
                items(rows.size, key = { it }) { index ->
                    when (val row = rows[index]) {
                        is DiffRow.Header -> HunkHeaderRow(row.text, contentWidth, hScroll)
                        is DiffRow.Line -> DiffLineRow(row.line, contentWidth, hScroll)
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(vScroll),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(hScroll),
            modifier = Modifier.fillMaxWidth().padding(start = Dimens.gutterWidth),
        )
    }
}

@Composable
private fun HunkHeaderRow(
    text: String,
    contentWidth: androidx.compose.ui.unit.Dp,
    hScroll: androidx.compose.foundation.ScrollState,
) {
    val colors = LocalWorktreeColors.current
    Row(Modifier.fillMaxWidth().background(colors.diffHunkBg)) {
        Box(Modifier.width(Dimens.gutterWidth).height(18.dp))
        Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
            Text(
                text = text,
                style = CodeTextStyle,
                color = colors.link.copy(alpha = 0.9f),
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.width(contentWidth).padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun DiffLineRow(
    line: DiffLine,
    contentWidth: androidx.compose.ui.unit.Dp,
    hScroll: androidx.compose.foundation.ScrollState,
) {
    val colors = LocalWorktreeColors.current
    val background = when (line.type) {
        DiffLineType.ADD -> colors.diffAddedBg
        DiffLineType.DELETE -> colors.diffDeletedBg
        else -> Color.Transparent
    }
    val marker = when (line.type) {
        DiffLineType.ADD -> "+"
        DiffLineType.DELETE -> "-"
        DiffLineType.NO_NEWLINE -> "\\"
        DiffLineType.CONTEXT -> " "
    }
    val textColor = when (line.type) {
        DiffLineType.NO_NEWLINE -> colors.textDisabled
        else -> colors.text
    }

    Row(Modifier.fillMaxWidth().background(background)) {
        // Gutter: old and new line numbers, pinned while the code scrolls.
        Row(
            modifier = Modifier
                .width(Dimens.gutterWidth)
                .background(if (background == Color.Transparent) colors.editor else background),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GutterNumber(line.oldNumber, Modifier.weight(1f))
            GutterNumber(line.newNumber, Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
            Text(
                text = marker + line.text,
                style = CodeTextStyle,
                color = textColor,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.width(contentWidth).padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun GutterNumber(number: Int?, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    Text(
        text = number?.toString().orEmpty(),
        style = CodeTextStyle,
        color = colors.diffGutterText,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Compact `+n −n` summary used in file lists and headers. */
@Composable
fun DiffStat(diff: FileDiff, modifier: Modifier = Modifier) {
    val colors = LocalWorktreeColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (diff.added > 0) {
            Text("+${diff.added}", color = colors.added, style = MaterialTheme.typography.bodySmall)
        }
        if (diff.removed > 0) {
            Text("−${diff.removed}", color = colors.conflicted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
