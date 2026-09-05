package io.mainactor.worktree.platform

import io.mainactor.worktree.model.DiffHunk
import io.mainactor.worktree.model.DiffLine
import io.mainactor.worktree.model.DiffLineType
import io.mainactor.worktree.model.FileDiff
import io.mainactor.worktree.ui.panes.DiffHighlighting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a real parser buys over a set of regular expressions.
 *
 * Every case below is one a hand-written lexer gets wrong, and every one of them is common enough
 * to see within a minute of reading a diff.
 */
class TreeSitterHighlighterTest {

    private val highlighter = TreeSitterHighlighter()

    /** The text each token covers, so a test reads as what would be coloured. */
    private fun coloured(path: String, source: String): List<Pair<TokenKind, String>> =
        highlighter.tokens(path, source).map { it.kind to source.substring(it.start, it.end) }

    private fun of(kind: TokenKind, path: String, source: String): List<String> =
        coloured(path, source).filter { it.first == kind }.map { it.second }

    @Test
    fun `keywords, strings, comments and numbers are told apart`() {
        val source = """
            // a comment
            class Greeter {
                val answer = 42
                val greeting = "hello"
            }
        """.trimIndent()

        assertTrue("class" in of(TokenKind.KEYWORD, "Greeter.kt", source))
        assertTrue("val" in of(TokenKind.KEYWORD, "Greeter.kt", source))
        assertTrue("// a comment" in of(TokenKind.COMMENT, "Greeter.kt", source))
        assertTrue("42" in of(TokenKind.NUMBER, "Greeter.kt", source))
        assertTrue(of(TokenKind.STRING, "Greeter.kt", source).any { "hello" in it })
    }

    /**
     * The case that decides between a parser and a pattern: what looks like a comment inside a
     * string is not one, and a lexer that scans for `//` colours the rest of the line grey.
     */
    @Test
    fun `a comment marker inside a string is part of the string`() {
        val source = """val url = "https://example.com/path" // the real comment"""
        val comments = of(TokenKind.COMMENT, "a.kt", source)

        assertEquals(listOf("// the real comment"), comments)
        assertTrue(
            of(TokenKind.STRING, "a.kt", source).any { "https://example.com/path" in it },
            "the URL should be one string, not a string and a comment",
        )
    }

    /** And the other way round: an apostrophe in a comment does not open a string. */
    @Test
    fun `an apostrophe in a comment does not open a string`() {
        val source = "// it's fine\nval x = 1\n"
        assertTrue(of(TokenKind.STRING, "a.kt", source).isEmpty(), "nothing here is a string")
        assertTrue("val" in of(TokenKind.KEYWORD, "a.kt", source))
    }

    /** A word that is a keyword elsewhere is not one where the grammar says it is a name. */
    @Test
    fun `a keyword used as a name is not painted as a keyword`() {
        val source = "const data = { class: 1, from: 2 }\n"
        val keywords = of(TokenKind.KEYWORD, "a.js", source)
        assertTrue("const" in keywords)
        assertFalse("class" in keywords, "a property called class is a property")
    }

    @Test
    fun `several languages are understood`() {
        assertTrue("def" in of(TokenKind.KEYWORD, "a.py", "def f():\n    return 1\n"))
        assertTrue("func" in of(TokenKind.KEYWORD, "a.go", "func main() {}\n"))
        assertTrue("fn" in of(TokenKind.KEYWORD, "a.rs", "fn main() {}\n"))
        assertTrue("class" in of(TokenKind.KEYWORD, "A.java", "class A {}\n"))
        assertTrue(of(TokenKind.STRING, "a.json", """{"key": "value"}""").isNotEmpty())
    }

    /** A file with no grammar is not an error; it is simply not coloured. */
    @Test
    fun `an unknown kind of file is left alone`() {
        assertFalse(highlighter.canHighlight("gradle.lockfile"))
        assertEquals(emptyList(), highlighter.tokens("gradle.lockfile", "anything at all"))
        assertTrue(highlighter.canHighlight("Main.kt"))
    }

    /** Some files carry their language in their name rather than in an extension. */
    @Test
    fun `a file named rather than extended is still recognised`() {
        assertTrue(highlighter.canHighlight("Dockerfile"))
        assertTrue(highlighter.canHighlight("packaging/harden-embedded-natives.sh"))
        assertTrue(highlighter.canHighlight(".zshrc"))
    }

    /**
     * A diff is a fragment, so the parser is handed things that are not whole files. It must come
     * back with something usable rather than nothing.
     */
    @Test
    fun `a fragment of a file is still coloured`() {
        val fragment = "    val name = \"x\"\n    fun go() {\n"
        assertTrue(of(TokenKind.KEYWORD, "a.kt", fragment).isNotEmpty(), "a fragment lost all colour")
    }

    @Test
    fun `text with nothing in it is handled`() {
        assertEquals(emptyList(), highlighter.tokens("a.kt", ""))
    }

    // ---------------------------------------------------------------- the patch, not the file

    private fun patch(vararg lines: Pair<DiffLineType, String>) = FileDiff(
        path = "Sample.kt",
        hunks = listOf(
            DiffHunk(
                header = "@@ -1,3 +1,3 @@",
                oldStart = 1,
                oldCount = lines.size,
                newStart = 1,
                newCount = lines.size,
                lines = lines.mapIndexed { index, (type, text) ->
                    DiffLine(type = type, text = text, oldNumber = index + 1, newNumber = index + 1)
                },
            ),
        ),
    )

    /**
     * The thing a patch makes easy to get wrong: a deleted line comes from the old file and an
     * added one from the new, so parsing them as they appear on screen lets an added comment
     * opener close over the deleted lines beneath it.
     */
    @Test
    fun `an added comment does not comment out the lines it replaced`() {
        val diff = patch(
            DiffLineType.ADD to "    /* newly commented out",
            DiffLineType.DELETE to "    val kept = 1",
            DiffLineType.CONTEXT to "    val after = 2",
        )

        val coloured = DiffHighlighting.of(diff, highlighter)

        val deleted = coloured[1].spans.map { it.kind }
        assertTrue(
            TokenKind.COMMENT !in deleted,
            "the deleted line was swallowed by a comment that only exists on the other side",
        )
        assertTrue(TokenKind.KEYWORD in deleted, "the deleted line should still be coloured: $deleted")
    }

    /** And the same in reverse: a deleted opener must not reach the added lines. */
    @Test
    fun `a deleted comment does not comment out what replaced it`() {
        val diff = patch(
            DiffLineType.DELETE to "    /* was commented out",
            DiffLineType.ADD to "    val restored = 1",
            DiffLineType.CONTEXT to "    val after = 2",
        )

        val coloured = DiffHighlighting.of(diff, highlighter)

        assertTrue(
            TokenKind.COMMENT !in coloured[1].spans.map { it.kind },
            "the added line took its colour from the side it does not belong to",
        )
    }

    @Test
    fun `spans are in each line's own coordinates`() {
        val diff = patch(DiffLineType.CONTEXT to "val answer = 42")
        val spans = DiffHighlighting.of(diff, highlighter)[0].spans
        val number = spans.single { it.kind == TokenKind.NUMBER }
        assertEquals(13, number.start, "the offset should be into the line, not into the patch")
        assertEquals(15, number.end)
    }

    /** A file with no grammar produces nothing at all, so the caller can skip the work. */
    @Test
    fun `a patch of an unknown file is not coloured`() {
        val diff = FileDiff(
            path = "gradle.lockfile",
            hunks = listOf(
                DiffHunk("@@", 1, 1, 1, 1, listOf(DiffLine(DiffLineType.CONTEXT, "anything", 1, 1))),
            ),
        )
        assertEquals(emptyList(), DiffHighlighting.of(diff, highlighter))
    }
}
