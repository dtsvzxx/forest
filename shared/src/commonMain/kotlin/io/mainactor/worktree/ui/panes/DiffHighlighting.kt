package io.mainactor.worktree.ui.panes

import io.mainactor.worktree.model.DiffLine
import io.mainactor.worktree.model.DiffLineType
import io.mainactor.worktree.model.FileDiff
import io.mainactor.worktree.platform.SyntaxHighlighter
import io.mainactor.worktree.platform.Token
import io.mainactor.worktree.platform.TokenKind

/** The coloured runs of one line, in that line's own coordinates. */
class LineTokens(val spans: List<Token>) {
    companion object {
        val NONE = LineTokens(emptyList())
    }
}

/**
 * Colours a patch, one side at a time.
 *
 * **A patch is two files, not one**, and that is the thing to get right. A deleted line comes from
 * the old file and an added line from the new one; parsing them together as they appear on screen
 * means an added comment opener closes over the deleted lines beneath it, and a deleted triple quote
 * opens a string that swallows the rest of the hunk. So the patch is reassembled into two texts — context plus
 * deletions, and context plus additions — each is parsed on its own, and every line takes its
 * colours from the side it belongs to.
 *
 * What it cannot do is know what came before the first line of a hunk. Three lines of context can
 * begin inside a block comment with nothing to say so; the whole-file toggle is the answer, and
 * with it the parse is exact rather than plausible.
 */
internal object DiffHighlighting {

    /**
     * Tokens for every line of [diff], in the order the lines are shown.
     *
     * Empty when nothing can colour this file, so a caller can compare against it cheaply and skip
     * the work of building annotated text.
     */
    fun of(diff: FileDiff, highlighter: SyntaxHighlighter): List<LineTokens> {
        val lines = diff.hunks.flatMap { it.lines }
        if (lines.isEmpty()) return emptyList()
        val path = diff.path
        if (!highlighter.canHighlight(path)) return emptyList()

        val old = colourSide(path, lines, highlighter) { it != DiffLineType.ADD }
        val new = colourSide(path, lines, highlighter) { it != DiffLineType.DELETE }

        return lines.mapIndexed { index, line ->
            when (line.type) {
                DiffLineType.DELETE -> old[index] ?: LineTokens.NONE
                DiffLineType.ADD -> new[index] ?: LineTokens.NONE
                // Context is in both; either side gives the same answer, and the new one is the
                // side a reader is usually asking about.
                else -> new[index] ?: old[index] ?: LineTokens.NONE
            }
        }
    }

    /**
     * Rebuilds one side of the patch as text, parses it, and hands each line back its own tokens.
     *
     * The offsets a parser returns are into the reassembled text, so each line records where it
     * began and the spans are shifted back into its own coordinates — and clipped, because a
     * string that runs across a line break produces one token spanning both.
     */
    private inline fun colourSide(
        path: String,
        lines: List<DiffLine>,
        highlighter: SyntaxHighlighter,
        include: (DiffLineType) -> Boolean,
    ): Map<Int, LineTokens> {
        val text = StringBuilder()
        val starts = ArrayList<Pair<Int, Int>>() // line index to where it starts in the text
        lines.forEachIndexed { index, line ->
            if (!include(line.type) || line.type == DiffLineType.NO_NEWLINE) return@forEachIndexed
            starts += index to text.length
            text.append(line.text).append('\n')
        }
        if (starts.isEmpty()) return emptyMap()

        val tokens = highlighter.tokens(path, text.toString())
        if (tokens.isEmpty()) return emptyMap()

        val result = HashMap<Int, LineTokens>(starts.size)
        var cursor = 0
        starts.forEachIndexed { position, (index, start) ->
            val end = if (position + 1 < starts.size) starts[position + 1].second - 1 else text.length
            val spans = ArrayList<Token>()
            // The tokens are in order, so the scan walks forward with the lines rather than
            // searching the whole list for each one.
            while (cursor < tokens.size && tokens[cursor].end <= start) cursor++
            var scan = cursor
            while (scan < tokens.size && tokens[scan].start < end) {
                val token = tokens[scan]
                val from = maxOf(token.start, start) - start
                val to = minOf(token.end, end) - start
                if (to > from) spans += Token(from, to, token.kind)
                scan++
            }
            if (spans.isNotEmpty()) result[index] = LineTokens(spans)
        }
        return result
    }
}

/** Which colour a kind is drawn in, kept beside the mapping it belongs to. */
internal fun TokenKind.colour(colors: io.mainactor.worktree.ui.theme.WorktreeColors) = when (this) {
    TokenKind.KEYWORD -> colors.syntaxKeyword
    TokenKind.STRING -> colors.syntaxString
    TokenKind.COMMENT -> colors.syntaxComment
    TokenKind.NUMBER -> colors.syntaxNumber
    TokenKind.TYPE -> colors.syntaxType
    TokenKind.FUNCTION -> colors.syntaxType
    TokenKind.ANNOTATION -> colors.syntaxAnnotation
}
