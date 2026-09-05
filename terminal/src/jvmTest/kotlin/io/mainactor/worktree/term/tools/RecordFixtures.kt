package io.mainactor.worktree.term.tools

import io.mainactor.worktree.term.TerminalModel
import io.mainactor.worktree.term.WinSize
import io.mainactor.worktree.term.pty.FfmPtyLauncher
import java.io.File

/**
 * Records what real programs print, so the emulator can be tested against them.
 *
 * A tool rather than a test — the same shape as `GenerateIcons` in `:desktopApp`, and for the same
 * reason: it is run deliberately, its output is committed, and a test checks the committed files
 * rather than regenerating them. Recording on every build would make the suite depend on `vim`
 * being installed and on it printing the same thing this year as last.
 *
 * The recording is driven through **our own pty**, not through `script`. That keeps it independent
 * of which flavour of `script` a machine has, fixes the terminal size so a program lays itself out
 * the same way every time, and means the fixtures are produced by the layer they will be used to
 * test — a recording that cannot be made is a pty bug found early.
 *
 * Run with `./gradlew :terminal:recordFixtures`.
 */
object RecordFixtures {

    private class Recording(
        val name: String,
        val command: List<String>,
        val input: List<Pair<Long, String>> = emptyList(),
        val columns: Int = 80,
        val rows: Int = 24,
        val settleMillis: Long = 400,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "src/jvmTest/resources/term")
        target.mkdirs()
        val workDir = File(System.getProperty("java.io.tmpdir"), "forest-fixtures").apply {
            deleteRecursively()
            mkdirs()
        }
        writeSampleFiles(workDir)

        recordings().forEach { recording ->
            val bytes = runCatching { record(recording, workDir) }.getOrElse { failure ->
                println("skipped ${recording.name}: ${failure.message}")
                return@forEach
            }
            if (bytes.isEmpty()) {
                println("skipped ${recording.name}: it printed nothing")
                return@forEach
            }
            File(target, "${recording.name}.raw").writeBytes(bytes)

            // The golden is what our own emulator makes of the bytes. Reviewed by eye once; after
            // that it is the thing that catches a refactor moving every colour by one.
            val model = TerminalModel(recording.columns, recording.rows, scrollback = 2000)
            model.feed(bytes, bytes.size)
            File(target, "${recording.name}.golden").writeText(model.screenText())
            println("recorded ${recording.name}: ${bytes.size} bytes")
        }
    }

    private fun recordings() = listOf(
        // Colours and attributes, with nothing interactive about it.
        Recording(
            name = "colours",
            command = listOf(
                "/bin/sh", "-c",
                """
                printf '\033[1mbold\033[0m \033[3mitalic\033[0m \033[4munderline\033[0m \033[7mreverse\033[0m\n'
                i=0; while [ ${'$'}i -lt 256 ]; do printf '\033[48;5;%dm  \033[0m' ${'$'}i; i=$((i+1)); done; printf '\n'
                printf '\033[38;2;255;100;0mtruecolour\033[0m \033[4:3mcurly\033[0m\n'
                """.trimIndent(),
            ),
        ),
        // A full-screen editor: the alternate screen, a status line, cursor addressing.
        Recording(
            name = "vim",
            command = listOf("/usr/bin/vim", "-u", "NONE", "-N", "sample.txt"),
            // Deliberately never quits: the interesting screen is the one vim is *showing*, and
            // `:q!` would leave the alternate screen and record an empty one.
            input = listOf(
                600L to ":set number\r",
                300L to "G",
                300L to "gg",
                300L to "/seven\r",
            ),
            settleMillis = 700,
        ),
        // A pager: the alternate screen again, and a prompt line that redraws.
        Recording(
            name = "less",
            command = listOf("/usr/bin/less", "-X", "sample.txt"),
            input = listOf(600L to " ", 300L to " ", 300L to "/eleven\r"),
            settleMillis = 700,
        ),
        // A multiplexer, which drives a terminal harder than anything else here.
        Recording(
            name = "tmux",
            command = listOf(
                // Its own config, not the machine's: the default status line carries a hostname
                // and a clock, which would commit somebody's machine name to the repository and
                // make the recording different every minute.
                "/opt/homebrew/bin/tmux", "-f", "tmux.conf", "new-session", "-x", "80", "-y", "24",
                "/bin/sh", "-c", "printf 'in tmux\\n'; sleep 30",
            ),
            // Long enough for the status line to be drawn, and closed while it is still up.
            settleMillis = 1500,
        ),
        // The shape of a build log: a great deal of ordinary output, scrolling past.
        Recording(
            name = "flood",
            command = listOf("/bin/sh", "-c", "i=1; while [ ${'$'}i -le 3000 ]; do echo \"line ${'$'}i of output\"; i=$((i+1)); done"),
            settleMillis = 800,
        ),
    )

    private fun writeSampleFiles(workDir: File) {
        File(workDir, "tmux.conf").writeText(
            """
            set -g status-left "[forest] "
            set -g status-right ""
            """.trimIndent(),
        )
        File(workDir, "sample.txt").writeText(
            (1..60).joinToString("\n") { "line $it: " + NUMBERS.getOrElse(it % NUMBERS.size) { "spare" } },
        )
    }

    private fun record(recording: Recording, workDir: File): ByteArray {
        val pty = FfmPtyLauncher().start(
            command = recording.command,
            workDir = workDir.path,
            env = mapOf(
                "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin",
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                "LANG" to "en_US.UTF-8",
                "HOME" to workDir.path,
            ),
            size = WinSize(recording.columns, recording.rows),
        )
        val collected = ArrayList<Byte>(1 shl 16)
        val reader = Thread {
            val chunk = ByteArray(8192)
            while (true) {
                val read = runCatching { pty.read(chunk) }.getOrDefault(-1)
                if (read < 0) break
                synchronized(collected) { repeat(read) { collected += chunk[it] } }
            }
        }
        reader.isDaemon = true
        reader.start()

        recording.input.forEach { (delay, text) ->
            Thread.sleep(delay)
            pty.write(text.encodeToByteArray())
        }
        Thread.sleep(recording.settleMillis)
        // Snapshot *before* closing. Closing hangs up the terminal, and a full-screen program's
        // last act is to leave the alternate screen and print why it died — so a recording taken
        // afterwards is of vim's obituary rather than of vim.
        val bytes = synchronized(collected) { collected.toByteArray() }
        pty.close()
        reader.join(1000)
        return bytes
    }

    private val NUMBERS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "eleven", "twelve",
    )
}
