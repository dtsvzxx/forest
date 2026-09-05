package io.mainactor.worktree.term

import io.mainactor.worktree.term.pty.FfmPtyLauncher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two halves together: a real program on a real pty, drawn by the emulator.
 *
 * Every other test drives one piece with input written by hand. This one is the only place that
 * says the pieces fit — that what a shell actually emits, chunked however the kernel chose to
 * deliver it, lands on the screen. It is also the shape every recorded-stream test will take.
 */
class LiveTerminalTest {

    private val shell = "/bin/sh"
    private val available = File(shell).canExecute()

    /** Runs [script] to completion and returns the screen it painted. */
    private fun screenAfter(
        script: String,
        columns: Int = 40,
        rows: Int = 6,
        scrollback: Int = 500,
    ): TerminalModel {
        val terminal = TerminalModel(columns, rows, scrollback = scrollback)
        val pty = FfmPtyLauncher().start(
            command = listOf(shell, "-c", script),
            workDir = System.getProperty("java.io.tmpdir"),
            env = mapOf("PATH" to "/usr/bin:/bin", "TERM" to "xterm-256color"),
            size = WinSize(columns, rows),
        )
        try {
            val chunk = ByteArray(4096)
            while (true) {
                val read = pty.read(chunk)
                if (read < 0) break
                terminal.feed(chunk, read)
            }
        } finally {
            pty.close()
        }
        return terminal
    }

    @Test
    fun `what a shell prints lands on the screen`() {
        if (!available) return
        val terminal = screenAfter("printf 'one\\r\\ntwo\\r\\nthree'")
        assertEquals("one", terminal.buffer.line(0).text())
        assertEquals("two", terminal.buffer.line(1).text())
        assertEquals("three", terminal.buffer.line(2).text())
    }

    @Test
    fun `escape sequences from a real program are obeyed`() {
        if (!available) return
        // Paint in colour, move the cursor about, erase — the everyday vocabulary of a prompt.
        val terminal = screenAfter("printf '\\033[31mred\\033[0m\\r\\n\\033[3;5Hfar\\033[H\\033[Ktop'")
        assertEquals("top", terminal.buffer.line(0).text())
        assertEquals("    far", terminal.buffer.line(2).text())
    }

    /**
     * The chunk boundary, for real: `read(2)` returns whatever was in the pty buffer, so a
     * multi-byte character or a sequence split across two reads is the normal case, not an edge.
     */
    @Test
    fun `output arriving in arbitrary chunks still reads as what was printed`() {
        if (!available) return
        val terminal = screenAfter("printf '中文 🌲 done\\r\\n'")
        assertEquals("中文 🌲 done", terminal.buffer.line(0).text())
    }

    @Test
    fun `a program that clears and redraws leaves only what it drew`() {
        if (!available) return
        val terminal = screenAfter("printf 'noise\\r\\nmore noise\\r\\n\\033[2J\\033[Hclean'")
        assertEquals("clean", terminal.buffer.line(0).text())
        assertTrue(terminal.buffer.line(1).text().isEmpty())
    }

    @Test
    fun `a flood scrolls and the history keeps what went past`() {
        if (!available) return
        val terminal = screenAfter("i=1; while [ \$i -le 200 ]; do printf 'line%d\\r\\n' \$i; i=\$((i+1)); done")
        assertEquals("line200", terminal.buffer.line(4).text())
        val history = (0 until terminal.buffer.scrollbackSize).map { terminal.buffer.scrollbackLine(it).text() }
        assertTrue(history.size > 100, "the history should hold what scrolled past, not ${history.size} lines")
        assertTrue("line10" in history, "an early line should still be scrollable to")
    }
}
