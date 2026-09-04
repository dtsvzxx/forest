package io.mainactor.worktree.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Reveals paths in the platform file manager.
 *
 * Every desktop spells this differently, and only macOS and Windows can select the item itself —
 * on Linux the best that is portable is opening the containing folder.
 */
class DesktopSystemIntegration(
    private val isMac: Boolean = Os.isMac,
    private val isWindows: Boolean = Os.isWindows,
) : SystemIntegration {

    override val revealLabel: String = revealLabelFor(isMac, isWindows)

    override fun reveal(path: String) {
        runCatching { ProcessBuilder(revealCommand(path, isMac, isWindows)).start() }
    }

    override fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }
}

internal fun revealLabelFor(isMac: Boolean, isWindows: Boolean): String = when {
    isMac -> "Show in Finder"
    isWindows -> "Show in Explorer"
    else -> "Show in Files"
}

internal fun revealCommand(path: String, isMac: Boolean, isWindows: Boolean): List<String> = when {
    isMac -> listOf("open", "-R", path)
    // Explorer wants the switch and the path as one argument, with no space after the comma.
    isWindows -> listOf("explorer.exe", "/select,$path")
    // No portable "select this file" on Linux; open the folder that contains it.
    else -> listOf("xdg-open", File(path).parent ?: path)
}
