package io.mainactor.worktree.term.pty

import io.mainactor.worktree.term.Pty
import io.mainactor.worktree.term.WinSize
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives real pseudo-terminals.
 *
 * Everything here runs a `/bin/sh` and reads what it printed, because that is the only way to
 * establish the two things this layer exists for and neither of which a return code reveals: that
 * the child got a *controlling* terminal, and that the numbers copied out of a system header mean
 * on this platform what they mean on the other one. It needs no display, and it skips itself where
 * there is no `/bin/sh` — which is Windows, until ConPTY lands.
 */
class PtyTest {

    private val shell = "/bin/sh"
    private val available = File(shell).canExecute()
    private val started = mutableListOf<Pty>()

    @AfterTest
    fun stopEverything() {
        started.forEach { runCatching { it.close() } }
    }

    private fun run(script: String, size: WinSize = WinSize(80, 24)): Pty =
        FfmPtyLauncher().start(
            command = listOf(shell, "-c", script),
            workDir = System.getProperty("java.io.tmpdir"),
            env = mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "TERM" to "xterm-256color"),
            size = size,
        ).also { started += it }

    /** Reads until [marker] shows up, or the process ends. */
    private fun Pty.textUpTo(marker: String): String {
        val buffer = ByteArray(4096)
        val text = StringBuilder()
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            text.append(String(buffer, 0, n))
            if (text.contains(marker)) break
        }
        return text.toString().replace("\r\n", "\n")
    }

    /**
     * The point of the whole layer: `tty` answers only for a process whose session has one, so a
     * path here is proof that `POSIX_SPAWN_SETSID` plus an `addopen` of the slave did the job a
     * fork helper is normally shipped for.
     */
    @Test
    fun `a shell on a pty has a controlling terminal`() {
        if (!available) return
        val output = run("tty; echo done").textUpTo("done")
        assertTrue(
            Regex("/dev/(tty|pts)").containsMatchIn(output),
            "the shell reported no controlling terminal: $output",
        )
    }

    /** `TIOCSWINSZ` is a number copied from a header; this is what says it is the right one. */
    @Test
    fun `the size a pane is opened at is the size the child sees`() {
        if (!available) return
        val output = run("stty size; echo done", size = WinSize(columns = 137, rows = 42)).textUpTo("done")
        assertTrue("42 137" in output, "expected 42 rows by 137 columns, got: $output")
    }

    /** Resizing has to reach the child as a signal, not merely be recorded on our side. */
    @Test
    fun `resizing a running pane reaches the child as SIGWINCH`() {
        if (!available) return
        // `trap` fires when the kernel delivers the signal, which it only does for the process
        // group that owns the terminal — so this fails if the controlling terminal is wrong too.
        val pty = run("trap 'stty size; echo caught' WINCH; echo ready; while true; do sleep 0.1; done")
        pty.textUpTo("ready")
        pty.resize(WinSize(columns = 100, rows = 30))
        assertTrue("30 100" in pty.textUpTo("caught"), "the child did not see the new size")
        assertEquals(WinSize(columns = 100, rows = 30), (pty as UnixPty).windowSize())
    }

    @Test
    fun `a child that exits is reaped with its status`() {
        if (!available) return
        val pty = run("exit 7")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (pty.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(7, pty.exitCode, "expected the shell's own exit status")
        assertTrue(!pty.isAlive)
    }

    /** The wall's actual shape: six panes at once, each its own terminal. */
    @Test
    fun `six ptys coexist and close independently`() {
        if (!available) return
        val panes = (1..6).map { run("echo pane$it; while true; do sleep 0.1; done") }
        panes.forEachIndexed { index, pty ->
            assertTrue("pane${index + 1}" in pty.textUpTo("pane${index + 1}"))
        }
        assertTrue(panes.all { it.isAlive })

        panes[2].close()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (panes[2].isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(!panes[2].isAlive, "closing a pane did not end its shell")
        assertTrue(panes.filterIndexed { i, _ -> i != 2 }.all { it.isAlive }, "closing one pane took others with it")
    }

    /** A pane runs where it was told to, and that is a file action rather than a `cd` in the command. */
    @Test
    fun `a pane starts in the directory it was given`() {
        if (!available) return
        val directory = File(System.getProperty("java.io.tmpdir"), "pty-cwd-${System.nanoTime()}")
        directory.mkdirs()
        try {
            val pty = FfmPtyLauncher().start(
                command = listOf(shell, "-c", "pwd; echo done"),
                workDir = directory.path,
                env = mapOf("PATH" to "/usr/bin:/bin"),
                size = WinSize(80, 24),
            ).also { started += it }
            // macOS hands out /var, which is a symlink to /private/var, so compare canonical paths.
            val reported = File(pty.textUpTo("done").lineSequence().first().trim())
            assertEquals(directory.canonicalPath, reported.canonicalPath)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Writing is the other half: what a pane types has to arrive as terminal input. */
    @Test
    fun `what is written to a pane reaches the shell as input`() {
        if (!available) return
        val pty = run("read line; echo got:\$line")
        pty.write("hello\n".toByteArray())
        assertTrue("got:hello" in pty.textUpTo("got:"), "the shell never received the line")
    }

    /** The environment is the child's whole environment, not this process's plus a few. */
    @Test
    fun `a pane gets exactly the environment it was given`() {
        if (!available) return
        val pty = FfmPtyLauncher().start(
            command = listOf(shell, "-c", "echo [\$TERM][\$FOREST_PROBE][\$HOME]; echo done"),
            workDir = System.getProperty("java.io.tmpdir"),
            env = mapOf("TERM" to "xterm-256color", "FOREST_PROBE" to "set"),
            size = WinSize(80, 24),
        ).also { started += it }
        val output = pty.textUpTo("done")
        assertTrue("[xterm-256color][set][]" in output, "unexpected environment: $output")
    }
}
