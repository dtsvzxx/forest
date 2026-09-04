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

    /** Login shell for the embedded terminal. */
    /**
     * A login shell that runs [command] first and then hands the pane back to the user.
     *
     * `exec` at the end rather than letting the `-c` shell exit: when an agent quits — or crashes
     * on its first run — the pane would otherwise vanish along with whatever it printed about why.
     * The command is separated with `;` and not `&&` for the same reason.
     *
     * A login shell is what gives the agent the user's own `PATH`, aliases and credential helpers,
     * which is the whole reason a pane has ever run one.
     */
    fun shellRunning(command: String?): List<String> {
        if (command.isNullOrBlank()) return defaultShell()
        if (isWindows) return listOf(System.getenv("COMSPEC") ?: "cmd.exe", "/k", command)
        val shell = System.getenv("SHELL") ?: "/bin/bash"
        return listOf(shell, "-l", "-c", "$command; exec '$shell' -l")
    }

    fun defaultShell(): List<String> = when {
        isWindows -> listOf(System.getenv("COMSPEC") ?: "cmd.exe")
        else -> {
            val shell = System.getenv("SHELL") ?: "/bin/bash"
            // A login shell picks up the user's PATH, aliases and prompt, matching a normal terminal.
            listOf(shell, "--login")
        }
    }
}
