package io.mainactor.worktree.platform

import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterBash
import org.treesitter.TreeSitterC
import org.treesitter.TreeSitterGo
import org.treesitter.TreeSitterJava
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterJson
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterMarkdown
import org.treesitter.TreeSitterPython
import org.treesitter.TreeSitterRust
import org.treesitter.TreeSitterTypescript
import org.treesitter.TreeSitterYaml

/**
 * Colours source with tree-sitter — a real parser rather than a set of regular expressions.
 *
 * The difference shows on the lines people actually write: `// not a comment` inside a string stays
 * a string, an apostrophe in a comment does not open one, and a keyword used as an identifier is
 * not painted like a keyword. Those are exactly the cases a lexer of one's own gets wrong, and they
 * are common enough to notice within a minute of looking at a diff.
 *
 * The cost is native code, which this application had just finished removing from its terminal — a
 * deliberate trade, made with the numbers in hand. It is smaller than it looks: the parser and its
 * grammars ship as ordinary Mach-O inside jars, which `hardenEmbeddedNatives` already re-signs, and
 * `com.apple.security.cs.disable-library-validation` is already required by something else. So
 * this adds bundle size and no new machinery.
 *
 * **Everything here is a guess about grammars we did not write.** Node type names are per-grammar,
 * so rather than a table per language the mapping is structural: an *anonymous* node whose type is
 * made of letters is a keyword (that is how tree-sitter represents `class`, `fun`, `if`), a named
 * node whose type ends in `comment` is a comment, and so on. It is wrong occasionally and never
 * badly, and it works for a grammar nobody has looked at yet.
 */
class TreeSitterHighlighter : SyntaxHighlighter {

    /**
     * One parser per language, made on first use and never thrown away.
     *
     * A parser holds native state and costs milliseconds to build; a diff view asks for the same
     * language over and over. Not thread-safe, so all use is on one thread — see [tokens].
     */
    private val parsers = HashMap<String, TSParser?>()

    private val lock = Any()

    override fun canHighlight(path: String): Boolean = languageOf(path) != null

    /**
     * Parses [text] and returns what to colour.
     *
     * Synchronised because a `TSParser` is native state that cannot be entered twice, and diffs are
     * highlighted from whichever thread happened to load one. Parsing a file is fast enough
     * (milliseconds) that contending on it is cheaper than a parser per caller.
     */
    override fun tokens(path: String, text: String): List<Token> {
        if (text.isEmpty()) return emptyList()
        val language = languageOf(path) ?: return emptyList()
        return synchronized(lock) {
            val parser = parserFor(language) ?: return emptyList()
            val tokens = ArrayList<Token>(text.length / 16)
            runCatching {
                val tree = parser.parseString(null, text)
                collect(tree.rootNode, text.length, tokens)
            }.getOrElse { return emptyList() }
            tokens
        }
    }

    private fun parserFor(language: String): TSParser? = parsers.getOrPut(language) {
        // A grammar that will not load is not worth a crash: the file is simply not coloured.
        runCatching {
            TSParser().apply { setLanguage(grammarFor(language) ?: return@runCatching null) }
        }.getOrNull()
    }

    /**
     * Walks the tree, taking the outermost node that has a colour.
     *
     * Outermost because a string's contents are nodes of their own — an interpolation, an escape —
     * and colouring the parts would leave the quotes a different colour from what is between them.
     * The exception is a node whose children carry colour of their own, which is why the walk
     * continues into anything it did not classify.
     */
    private fun collect(node: TSNode, length: Int, into: MutableList<Token>) {
        val kind = kindOf(node)
        if (kind != null) {
            val start = node.startByte
            val end = minOf(node.endByte, length)
            if (end > start) into += Token(start, end, kind)
            if (kind == TokenKind.STRING || kind == TokenKind.COMMENT) return
        }
        val children = node.childCount
        for (index in 0 until children) collect(node.getChild(index), length, into)
    }

    private fun kindOf(node: TSNode): TokenKind? {
        val type = node.type ?: return null
        // Anonymous nodes are the literal text of the grammar: `class`, `fun`, `{`, `;`. The ones
        // made of letters are its keywords — which is how this stays grammar-agnostic.
        if (!node.isNamed) {
            return if (type.isNotEmpty() && type.all { it.isLetter() }) TokenKind.KEYWORD else null
        }
        return when {
            type.endsWith("comment") -> TokenKind.COMMENT
            type.contains("string") || type == "raw_text" -> TokenKind.STRING
            type.contains("char_literal") || type == "character" -> TokenKind.STRING
            type.endsWith("number") || type.contains("integer") || type.contains("float") ||
                type.contains("_literal") && type.startsWith("real") -> TokenKind.NUMBER
            type == "number" || type == "int_literal" || type == "number_literal" -> TokenKind.NUMBER
            type.endsWith("type_identifier") || type == "type" || type == "user_type" -> TokenKind.TYPE
            type == "annotation" || type == "attribute" || type == "decorator" -> TokenKind.ANNOTATION
            else -> null
        }
    }

    private fun grammarFor(language: String): TSLanguage? = when (language) {
        "kotlin" -> TreeSitterKotlin()
        "java" -> TreeSitterJava()
        "javascript" -> TreeSitterJavascript()
        "typescript" -> TreeSitterTypescript()
        "python" -> TreeSitterPython()
        "go" -> TreeSitterGo()
        "rust" -> TreeSitterRust()
        "c" -> TreeSitterC()
        "bash" -> TreeSitterBash()
        "json" -> TreeSitterJson()
        "yaml" -> TreeSitterYaml()
        "markdown" -> TreeSitterMarkdown()
        else -> null
    }

    /**
     * Which grammar a path wants, by extension and then by name.
     *
     * The name matters more than it looks: a repository is full of files whose extension says
     * nothing (`Dockerfile`, `gradlew`, `.zshrc`) and whose name says everything.
     */
    private fun languageOf(path: String): String? {
        val name = path.substringAfterLast('/')
        BY_NAME[name]?.let { return it }
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return BY_EXTENSION[extension.lowercase()]
    }

    private companion object {
        val BY_EXTENSION = mapOf(
            "kt" to "kotlin", "kts" to "kotlin",
            "java" to "java",
            "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascript",
            "ts" to "typescript", "tsx" to "typescript",
            "py" to "python", "pyi" to "python",
            "go" to "go",
            "rs" to "rust",
            "c" to "c", "h" to "c",
            "sh" to "bash", "bash" to "bash", "zsh" to "bash",
            "json" to "json",
            "yaml" to "yaml", "yml" to "yaml",
            "md" to "markdown", "markdown" to "markdown",
        )

        val BY_NAME = mapOf(
            "Dockerfile" to "bash",
            "Makefile" to "bash",
            "gradlew" to "bash",
            ".zshrc" to "bash",
            ".bashrc" to "bash",
            ".bash_profile" to "bash",
            ".zprofile" to "bash",
        )
    }
}
