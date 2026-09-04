package io.mainactor.worktree.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Native folder picker.
 *
 * macOS gets [FileDialog] with `apple.awt.fileDialogForDirectories`, because AWT's own
 * [JFileChooser] there is a Swing dialog that looks nothing like a Mac file panel. Everywhere
 * else [JFileChooser] in directories-only mode is the closest thing to native available on the JVM.
 */
class SwingDirectoryChooser : DirectoryChooser {

    override suspend fun chooseDirectory(title: String, startIn: String?): String? =
        withContext(Dispatchers.Main) {
            if (Os.isMac) chooseWithFileDialog(title, startIn) else chooseWithJFileChooser(title, startIn)
        }

    private fun chooseWithFileDialog(title: String, startIn: String?): String? {
        val previous = System.getProperty(MAC_DIRECTORY_MODE)
        System.setProperty(MAC_DIRECTORY_MODE, "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            startIn?.let { dialog.directory = it }
            dialog.isVisible = true
            val dir = dialog.directory ?: return null
            val file = dialog.file ?: return null
            return File(dir, file).absolutePath
        } finally {
            if (previous == null) System.clearProperty(MAC_DIRECTORY_MODE) else System.setProperty(MAC_DIRECTORY_MODE, previous)
        }
    }

    private fun chooseWithJFileChooser(title: String, startIn: String?): String? {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser(startIn?.let(::File)).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }

    private companion object {
        const val MAC_DIRECTORY_MODE = "apple.awt.fileDialogForDirectories"
    }
}
