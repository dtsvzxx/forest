package io.mainactor.worktree.terminal

import io.mainactor.worktree.term.TerminalClipboard
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * The system clipboard, for a pane.
 *
 * Every failure is swallowed on purpose. A clipboard can be owned by another process, empty, or
 * holding something that is not text, and none of those are worth an exception in the middle of a
 * keystroke — a copy that quietly does nothing is a far smaller problem than a pane that stops
 * taking input.
 */
class AwtTerminalClipboard : TerminalClipboard {

    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard

    override fun read(): String? = runCatching {
        clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun write(text: String) {
        runCatching { clipboard.setContents(StringSelection(text), null) }
    }
}
