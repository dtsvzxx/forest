package io.mainactor.worktree.platform

/** Result of a finished child process. */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0

    /** stderr if git said anything there, otherwise stdout — git splits messages unpredictably. */
    val message: String
        get() = stderr.trim().ifEmpty { stdout.trim() }
}

/**
 * Runs child processes. The only genuinely platform-specific part of the git layer; everything
 * built on top of it lives in commonMain and is therefore unit-testable with a fake runner.
 */
interface CommandRunner {
    suspend fun exec(
        workDir: String?,
        command: List<String>,
        stdin: String? = null,
        env: Map<String, String> = emptyMap(),
    ): CommandResult
}

/** The handful of filesystem operations the UI needs, kept behind an interface for the same reason. */
interface FileSystemAccess {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun createDirectories(path: String)
    fun homeDir(): String
    fun nameOf(path: String): String
    fun parentOf(path: String): String?
    fun resolve(base: String, child: String): String
    /**
     * Resolves symlinks and `..` segments. git always reports canonical worktree paths, while a
     * path the user typed or a dialog returned may not be one — on macOS `/var/...` and
     * `/private/var/...` name the same directory — so the two have to be compared canonically.
     */
    fun canonicalPath(path: String): String

    /** Epoch seconds of the last modification, or 0 when the path does not exist. */
    fun lastModifiedAt(path: String): Long

    /** Epoch seconds now, for rendering timestamps as "5 minutes ago". */
    fun now(): Long
}

/** The desktop the app is running on, for the few actions that leave the window. */
interface SystemIntegration {

    /** Menu label for [reveal], worded the way the current desktop words it. */
    val revealLabel: String

    /** Opens the platform file manager with [path] selected. */
    fun reveal(path: String)

    /** Puts [text] on the system clipboard. */
    fun copyToClipboard(text: String)
}

/** Lets the user pick a directory with the OS-native chooser. */
interface DirectoryChooser {
    /** Returns the chosen absolute path, or null when the dialog was cancelled. */
    suspend fun chooseDirectory(title: String, startIn: String?): String?
}
