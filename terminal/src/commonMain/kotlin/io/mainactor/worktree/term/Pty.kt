package io.mainactor.worktree.term

/** How big a terminal is, in character cells. */
data class WinSize(val columns: Int, val rows: Int) {
    init {
        require(columns > 0 && rows > 0) { "a terminal cannot be ${columns}x$rows" }
    }
}

/**
 * A pseudo-terminal with a process on the far end.
 *
 * This is the whole platform surface of a terminal session, and it is an interface in common code
 * for the same reason `CommandRunner` and `FileSystemAccess` are in `:shared` — so the parts above
 * it can be driven against a fake, with no process, no file descriptors and no display.
 */
interface Pty {

    /** False once the process has exited; the status is then in [exitCode]. */
    val isAlive: Boolean

    /** The process's exit status, or null while it is still running. */
    val exitCode: Int?

    /**
     * Blocks until the process writes something, and returns how many bytes landed in [into];
     * -1 once there will be no more.
     *
     * Called from one thread per session, and deliberately blocking: the pty's own buffer plus a
     * blocking read *is* the backpressure. Putting a queue between this and the emulator would only
     * move the unbounded growth somewhere the operating system cannot see it.
     */
    fun read(into: ByteArray): Int

    /** Sends [length] bytes to the process. Never from the UI thread: a full pty buffer blocks. */
    fun write(bytes: ByteArray, length: Int = bytes.size)

    /**
     * Tells the process the terminal changed size, which reaches it as `SIGWINCH`.
     *
     * The emulator has to adopt the new size *first*: a child that redraws for a screen the buffer
     * does not have yet paints into nowhere.
     */
    fun resize(size: WinSize)

    /** Ends the process and releases the pty. Idempotent. */
    fun close()
}

/** Starts processes on a pseudo-terminal. */
interface PtyLauncher {

    /**
     * Runs [command] in [workDir] on a new pty of [size].
     *
     * [env] is the child's whole environment, not an addition to this process's — a pane's
     * environment is built deliberately (a login shell, `TERM`, `COLORTERM`), and inheriting
     * whatever the app was launched with is how a packaged build ends up differing from a
     * developer's.
     */
    fun start(
        command: List<String>,
        workDir: String,
        env: Map<String, String>,
        size: WinSize,
    ): Pty
}
