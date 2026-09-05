package io.mainactor.worktree.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs child processes with [ProcessBuilder].
 *
 * stdout and stderr are drained on separate coroutines: git writes progress to stderr while
 * streaming pack data to stdout, and reading them sequentially deadlocks as soon as either
 * pipe buffer fills.
 */
class ProcessCommandRunner : CommandRunner {

    override suspend fun exec(
        workDir: String?,
        command: List<String>,
        stdin: String?,
        env: Map<String, String>,
    ): CommandResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(command)
        if (workDir != null) {
            val dir = File(workDir)
            if (dir.isDirectory) builder.directory(dir)
        }
        builder.environment().putAll(env)

        val process = try {
            builder.start()
        } catch (e: Exception) {
            return@withContext CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "failed to start ${command.firstOrNull()}",
            )
        }

        try {
            if (stdin != null) {
                process.outputStream.use { it.write(stdin.toByteArray(StandardCharsets.UTF_8)) }
            } else {
                process.outputStream.close()
            }

            val (out, err) = coroutineScope {
                listOf(
                    async { process.inputStream.readBytes().toString(StandardCharsets.UTF_8) },
                    async { process.errorStream.readBytes().toString(StandardCharsets.UTF_8) },
                ).awaitAll()
            }
            CommandResult(runInterruptible { process.waitFor() }, out, err)
        } catch (e: CancellationException) {
            process.destroyForcibly()
            throw e
        } catch (e: Exception) {
            process.destroyForcibly()
            CommandResult(-1, "", e.message ?: e::class.simpleName.orEmpty())
        }
    }
}

/** [ShellRunner] over the user's login shell. */
class JvmShellRunner(private val runner: CommandRunner = ProcessCommandRunner()) : ShellRunner {
    override suspend fun run(workDir: String, commandLine: String): CommandResult =
        runner.exec(workDir, Os.loginShellCommand(commandLine))
}

class JvmFileSystemAccess(
    /** Overridable so tests can keep the recent-projects file inside a temp tree. */
    private val home: String = System.getProperty("user.home") ?: "/",
) : FileSystemAccess {
    override fun exists(path: String) = File(path).exists()
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun readText(path: String): String = File(path).readText()

    override fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun createDirectories(path: String) {
        Files.createDirectories(Path.of(path))
    }

    override fun homeDir(): String = home
    override fun nameOf(path: String): String = File(path).name.ifEmpty { path }
    override fun parentOf(path: String): String? = File(path).parent
    override fun resolve(base: String, child: String): String = File(base, child).absolutePath

    override fun canonicalPath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }

    override fun lastModifiedAt(path: String): Long = File(path).lastModified() / 1000

    override fun findOnPath(name: String): String? {
        val names = if (Os.isWindows) listOf("$name.exe", "$name.cmd", name) else listOf(name)
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.forEach { dir ->
            names.forEach { candidate ->
                val file = File(dir, candidate)
                if (file.isFile && file.canExecute()) return file.absolutePath
            }
        }
        // Where a windowed process's PATH typically fails to reach.
        return EXTRA_BIN_DIRS
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    override fun listDirectory(path: String): List<String> =
        File(path).list()?.toList().orEmpty()

    override fun fileSize(path: String): Long = File(path).length()

    override fun readFrom(path: String, offset: Long, maxBytes: Int): ByteArray {
        val file = File(path)
        val length = file.length()
        if (offset >= length) return ByteArray(0)
        val wanted = minOf(length - offset, maxBytes.toLong()).toInt()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(wanted)
                raf.readFully(buffer)
                buffer
            }
        } catch (e: IOException) {
            // A transcript can be pruned or rotated out from under us at any moment; the reader
            // treats an empty result as "nothing new", which is the right answer either way.
            ByteArray(0)
        }
    }

    override fun now(): Long = System.currentTimeMillis() / 1000

    private companion object {
        /**
         * Directories a windowed process's inherited `PATH` usually misses.
         *
         * Same problem [GitLocator] solves for git, and for the same reason: launched from Finder
         * rather than from a terminal, the JVM gets `/usr/bin:/bin:/usr/sbin:/sbin` and nothing else.
         */
        val EXTRA_BIN_DIRS = listOf(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/opt/local/bin",
            System.getProperty("user.home") + "/.local/bin",
            System.getProperty("user.home") + "/.bun/bin",
            System.getProperty("user.home") + "/bin",
        )
    }
}

/**
 * Finds the `git` executable.
 *
 * A GUI process launched from Finder or a desktop launcher inherits a minimal PATH that usually
 * lacks Homebrew and friends, so falling back to the well-known install locations is what keeps
 * the app working when it is started as a bundled application rather than from a shell.
 */
object GitLocator {
    private val candidates = listOf(
        "/usr/bin/git",
        "/usr/local/bin/git",
        "/opt/homebrew/bin/git",
        "/opt/local/bin/git",
        "C:\\Program Files\\Git\\cmd\\git.exe",
        "C:\\Program Files (x86)\\Git\\cmd\\git.exe",
    )

    fun locate(): String {
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.forEach { dir ->
            for (name in listOf("git", "git.exe")) {
                val f = File(dir, name)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        candidates.firstOrNull { File(it).isFile }?.let { return it }
        return "git"
    }
}

object Os {
    private val name: String = System.getProperty("os.name").orEmpty().lowercase()
    val isWindows: Boolean = "win" in name
    val isMac: Boolean = "mac" in name || "darwin" in name

    /** The device `git diff --no-index` can be pointed at to mean "this file did not exist". */
    val nullDevice: String = if (isWindows) "NUL" else "/dev/null"

    /**
     * The user's own shell, which is **not** always what `$SHELL` says.
     *
     * A process launched from Finder inherits almost no environment, and `SHELL` is one of the
     * variables that can be missing entirely — the same launch that reduces `PATH` to
     * `/usr/bin:/bin:/usr/sbin:/sbin`. Falling back to `/bin/bash` there is wrong twice over: the
     * default shell has been zsh since Catalina, so bash reads a set of rc files (`~/.bashrc`,
     * `~/.bash_profile`) that a zsh user does not have, and every `PATH` line their `.zshrc` sets
     * is skipped. The pane then opens on `bash: claude: command not found` — a real report, from a
     * DMG-launched build whose sibling in `/Applications` happened to be given a `SHELL` and
     * worked.
     *
     * So ask the account database, which is where `chsh` writes and what Terminal itself reads:
     * `dscl` on macOS, the passwd entry elsewhere. The last resort is `/bin/sh`, the one shell a
     * POSIX system is required to have, rather than a guess at which of the others is installed.
     */
    val userShell: String by lazy {
        System.getenv("SHELL")?.takeIf { it.isNotBlank() }
            ?: recordedShell()
            ?: "/bin/sh"
    }

    private fun recordedShell(): String? {
        val user = System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: return null
        val command = when {
            isMac -> listOf("/usr/bin/dscl", ".", "-read", "/Users/$user", "UserShell")
            else -> listOf("getent", "passwd", user)
        }
        val output = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.close()
            val text = process.inputStream.readBytes().decodeToString()
            if (process.waitFor() == 0) text else null
        }.getOrNull() ?: return null
        val shell = when {
            // "UserShell: /bin/zsh"
            isMac -> output.substringAfter("UserShell:", "").trim()
            // "user:*:501:20::/Users/user:/bin/zsh"
            else -> output.lineSequence().firstOrNull()?.substringAfterLast(':')?.trim().orEmpty()
        }
        return shell.takeIf { it.isNotBlank() && File(it).canExecute() }
    }

    /**
     * The flags that make `$SHELL -c` behave like the terminal the user actually types in.
     *
     * `-l` alone is not enough, and the gap only shows in a packaged build. A *non-interactive*
     * login shell reads `.zshenv`, `.zprofile` and `.zlogin` but **not** `.zshrc` — and `.zshrc` is
     * where a shell puts its `PATH` (`~/.local/bin`, Homebrew, version-manager shims) and its
     * aliases. Started from a terminal the JVM inherits that `PATH` anyway and everything works;
     * started from Finder it inherits `/usr/bin:/bin:/usr/sbin:/sbin`, so `claude` — installed in
     * `~/.local/bin` — came back as `zsh:1: command not found`. `-i` is what sources `.zshrc`, so
     * the pane finds exactly what a terminal window would.
     */
    private val LOGIN_INTERACTIVE = listOf("-l", "-i", "-c")

    /** A login shell that runs [command] and exits, for work with no terminal attached. */
    fun loginShellCommand(command: String): List<String> = when {
        isWindows -> listOf(System.getenv("COMSPEC") ?: "cmd.exe", "/c", command)
        else -> listOf(userShell) + LOGIN_INTERACTIVE + command
    }

    /**
     * A login shell that runs [command] first and then hands the pane back to the user.
     *
     * `exec` at the end rather than letting the `-c` shell exit: when an agent quits — or crashes
     * on its first run — the pane would otherwise vanish along with whatever it printed about why.
     * The command is separated with `;` and not `&&` for the same reason.
     *
     * A login shell is what gives the agent the user's own `PATH`, aliases and credential helpers,
     * which is the whole reason a pane has ever run one — see [LOGIN_INTERACTIVE] for why it also
     * has to be an interactive one.
     */
    fun shellRunning(command: String?): List<String> {
        if (command.isNullOrBlank()) return defaultShell()
        if (isWindows) return listOf(System.getenv("COMSPEC") ?: "cmd.exe", "/k", command)
        val shell = userShell
        return listOf(shell) + LOGIN_INTERACTIVE + "$command; exec '$shell' -l"
    }

    fun defaultShell(): List<String> = when {
        isWindows -> listOf(System.getenv("COMSPEC") ?: "cmd.exe")
        else -> {
            val shell = userShell
            // A login shell picks up the user's PATH, aliases and prompt, matching a normal terminal.
            listOf(shell, "--login")
        }
    }
}
