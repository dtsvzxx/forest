package io.mainactor.worktree.term.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import io.mainactor.worktree.term.TerminalPane
import io.mainactor.worktree.term.TerminalSessions
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole chain on a real shell: laying the pane out again has to reach the child.
 *
 * Both halves were already covered — `laying the pane out sets the size in cells` proves the view
 * measures itself, `resizing a running pane reaches the child as SIGWINCH` proves the pty carries a
 * size to the process — and a test of each half is not a test of the join. This one drags the
 * splitter: it opens a pane narrow, widens it, and then asks the shell itself how wide it thinks it
 * is, because that is the only answer that matters.
 */
class LiveResizeTest {

    private val available = File("/bin/sh").canExecute()

    @Test
    fun `widening a live pane reaches the shell, not only the emulator`() {
        if (!available) return
        val sessions = TerminalSessions()
        val pane = sessions.getOrCreate(
            id = "resize",
            workDir = System.getProperty("java.io.tmpdir"),
            command = listOf("/bin/sh", "-i"),
            env = mapOf("TERM" to "xterm-256color", "PS1" to "$ "),
        )
        try {
            val scene = ImageComposeScene(600, 300, Density(1f), Dispatchers.Unconfined) {
                TerminalView(pane = pane, focused = true, modifier = Modifier.fillMaxSize())
            }
            val narrow = try {
                scene.render()
                scene.render()
                val narrow = pane.withModel { it.columns }
                scene.constraints = Constraints.fixed(1200, 400)
                scene.render()
                scene.render()
                narrow
            } finally {
                scene.close()
            }

            val wide = pane.withModel { it.columns }
            assertTrue(narrow in 40..110, "the pane started at an unexpected width: $narrow")
            assertTrue(wide > narrow + 40, "the emulator did not take the wider pane: $narrow → $wide")

            pane.send("stty size\n")
            val answer = sizeFromShell(pane)
            assertTrue(
                answer.endsWith(" $wide"),
                "the shell still thinks it is another width: expected $wide columns, got '$answer'",
            )
        } finally {
            sessions.closeAll()
        }
    }

    /** Reads the shell's answer off the screen: two numbers on a line of their own. */
    private fun sizeFromShell(pane: TerminalPane): String {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val line = pane.withModel { it.screenText() }
                .lines()
                .firstOrNull { it.trim().matches(Regex("""\d+ \d+""")) }
            if (line != null) return line.trim()
            Thread.sleep(POLL_MS)
        }
        return "(the shell never answered)"
    }

    private companion object {
        const val TIMEOUT_NANOS = 5_000_000_000L
        const val POLL_MS = 50L
    }
}
