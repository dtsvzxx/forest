package io.mainactor.worktree.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OsTest {

    /**
     * The bug this pins: a packaged build could not start `claude`.
     *
     * From Finder the JVM's `PATH` is `/usr/bin:/bin:/usr/sbin:/sbin`, and a *non-interactive*
     * login shell never reads `.zshrc` — where the `PATH` that holds `claude` is set. Only `-i`
     * sources it, so the pane's shell has to be a login **and** interactive one.
     */
    @Test
    fun `a pane runs its agent in a login interactive shell`() {
        if (Os.isWindows) return
        val command = Os.shellRunning("claude")
        assertTrue("-l" in command, "not a login shell: $command")
        assertTrue("-i" in command, "not an interactive shell: $command")
        // The pane survives the agent exiting, and shows whatever it printed on the way out.
        assertTrue(command.last().startsWith("claude; exec "), command.last())
    }

    /**
     * The bug this pins: from a DMG, Finder started the app with no `SHELL` at all, the fallback
     * picked `/bin/bash`, and the pane answered `bash: claude: command not found` — bash never
     * reads the `.zshrc` that puts `~/.local/bin` on the `PATH`. The shell has to come from the
     * account database when the environment does not carry one.
     */
    @Test
    fun `the shell is the user's own even when the environment names none`() {
        if (Os.isWindows) return
        assertTrue(File(Os.userShell).canExecute(), "not a usable shell: ${Os.userShell}")
        val recorded = ProcessBuilder(
            if (Os.isMac) listOf("/usr/bin/dscl", ".", "-read", "/Users/" + System.getProperty("user.name"), "UserShell")
            else listOf("getent", "passwd", System.getProperty("user.name")),
        ).redirectErrorStream(true).start().inputStream.readBytes().decodeToString()
        // Whatever `chsh` recorded is what a pane must run, and it is what $SHELL says here.
        assertTrue(Os.userShell in recorded, "${Os.userShell} is not the recorded shell: $recorded")
    }

    @Test
    fun `a background command runs in a login interactive shell too`() {
        if (Os.isWindows) return
        val command = Os.loginShellCommand("./build.sh")
        assertTrue("-l" in command, "not a login shell: $command")
        assertTrue("-i" in command, "not an interactive shell: $command")
        assertEquals("./build.sh", command.last())
    }

    /**
     * The regression the flags exist for, run for real: a shell given the environment Finder
     * hands an app still finds what the user's `.zshrc` puts on the `PATH`.
     */
    @Test
    fun `that shell finds a command only the user's rc file puts on PATH`() {
        if (Os.isWindows) return
        val shell = Os.userShell
        if (!File(shell).canExecute()) return
        val home = Files.createTempDirectory("os-shell").toFile()
        val bin = File(home, "bin").apply { mkdirs() }
        File(bin, "only-in-rc").apply {
            writeText("#!/bin/sh\necho found\n")
            setExecutable(true)
        }
        // Only an interactive shell reads these; a plain `-l -c` shell reads neither.
        File(home, ".zshrc").writeText("export PATH=\"$bin:\$PATH\"\n")
        File(home, ".bashrc").writeText("export PATH=\"$bin:\$PATH\"\n")

        val command = Os.loginShellCommand("only-in-rc")
        val process = ProcessBuilder(command)
            .directory(home)
            .redirectErrorStream(true)
            .apply {
                // What a windowed process actually inherits.
                environment().clear()
                environment()["HOME"] = home.absolutePath
                environment()["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
                // No SHELL: that is what a DMG-launched build was handed, and why `claude` was
                // looked for by bash on an account whose shell is zsh.
            }
            .start()
        process.outputStream.close()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor()
        home.deleteRecursively()

        assertTrue("found" in output, "shell did not read its rc file: $output")
    }
}
