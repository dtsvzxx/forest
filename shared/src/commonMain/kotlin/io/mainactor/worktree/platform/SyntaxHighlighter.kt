package io.mainactor.worktree.platform

/** What a stretch of source is, as far as colouring it is concerned. */
enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, ANNOTATION }

/** A run of characters of one kind, in the coordinates of the text it came from. */
data class Token(val start: Int, val end: Int, val kind: TokenKind)

/**
 * Colours source code.
 *
 * An interface here and an implementation in the platform layer, like [CommandRunner] and
 * [FileSystemAccess] — the parser is a native library, and `commonMain` never touches the JVM. It
 * is also what lets the diff view be rendered in a test with no highlighting at all, which is what
 * [None] is for.
 *
 * **A diff is a fragment, and that is the interesting part.** With three lines of context a hunk
 * can begin inside a block comment or a multi-line string, and nothing in the patch says so. The
 * whole-file toggle is the answer rather than a heuristic: with the whole file in hand the parse is
 * exact, and with three lines it is a good guess. A guess is the right thing to be wrong about
 * here — nobody has ever been misled by a mis-coloured comment.
 */
interface SyntaxHighlighter {

    /**
     * Tokens for [text], which is the whole of what should be parsed as one unit.
     *
     * Returns nothing for a language with no grammar shipped, which is the ordinary case rather
     * than an error: a `.lock` file or a `.patch` is simply not coloured.
     */
    fun tokens(path: String, text: String): List<Token>

    /** True when this path is one we can colour, so a caller can skip the work of assembling text. */
    fun canHighlight(path: String): Boolean

    /** No colouring at all — what a test and a render without a platform layer get. */
    object None : SyntaxHighlighter {
        override fun tokens(path: String, text: String): List<Token> = emptyList()
        override fun canHighlight(path: String): Boolean = false
    }
}
