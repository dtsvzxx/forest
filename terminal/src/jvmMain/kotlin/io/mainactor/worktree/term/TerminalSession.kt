package io.mainactor.worktree.term

import io.mainactor.worktree.term.pty.FfmPtyLauncher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One running terminal: a process on a pty, an emulator reading it, and a screen anyone can draw.
 *
 * Three threads meet here and the rules between them are the whole design:
 *
 * - a **reader** blocks in `read(2)` and feeds the emulator. Blocking is deliberate — the pty's own
 *   buffer is the backpressure, and a queue between the two would only move an unbounded growth
 *   somewhere the operating system cannot see it. It is a platform thread, not a virtual one: a
 *   blocking foreign call pins its carrier anyway, so a virtual thread would cost the pinning
 *   without the saving.
 * - a **writer** owns everything going the other way. Keystrokes are tiny, but a paste is not, and
 *   a pty whose reader has stopped blocks a write once its buffer fills. On the UI thread that is a
 *   frozen window, so writes never happen there.
 * - **whoever draws** reads the model under [withModel]. The model is deliberately not
 *   thread-safe; the lock belongs here, where both sides are visible.
 */
class TerminalSession internal constructor(
    val id: String,
    private val pty: Pty,
    columns: Int,
    rows: Int,
    scrollback: Int,
    /** Called whenever the screen may have changed, from the reader thread. */
    private val onChange: () -> Unit,
    private val onTitle: (String) -> Unit,
    private val clipboard: TerminalClipboard,
) : TerminalPane {

    private val lock = Any()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terminal-write-$id").apply { isDaemon = true }
    }

    private val model = TerminalModel(
        columns = columns,
        rows = rows,
        scrollback = scrollback,
        host = object : TerminalHost {
            // A query's answer goes back through the writer rather than straight out: it is
            // produced on the reader thread, and writing from there could block the very thread
            // that has to drain the pty for the write to complete.
            override fun respond(text: String) = send(text)
            override fun titleChanged(title: String) = onTitle(title)
            override fun clipboardWrite(text: String) = clipboard.write(text)
        },
    )

    @Volatile
    var isAlive: Boolean = true
        private set

    private val reader = Thread({ readUntilClosed() }, "terminal-read-$id").apply {
        isDaemon = true
        start()
    }

    /** Reads the screen under the lock. Keep it short: the reader thread waits behind it. */
    override fun <T> withModel(block: (TerminalModel) -> T): T = synchronized(lock) { block(model) }

    /** What the emulator has decided the window is called, or empty. */
    val title: String get() = withModel { it.title }

    override fun send(text: String) {
        if (text.isEmpty()) return
        val bytes = text.encodeToByteArray()
        writer.execute { runCatching { pty.write(bytes) } }
    }

    /**
     * The emulator adopts the new size first, then the child is told.
     *
     * The other order paints a redraw into a screen that does not exist yet.
     */
    override fun resize(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        val changed = synchronized(lock) {
            if (model.columns == columns && model.rows == rows) false
            else {
                model.resize(columns, rows)
                true
            }
        }
        if (changed) {
            pty.resize(WinSize(columns, rows))
            onChange()
        }
    }

    fun close() {
        isAlive = false
        pty.close()
        writer.shutdownNow()
        reader.interrupt()
    }

    private fun readUntilClosed() {
        val chunk = ByteArray(READ_BYTES)
        while (true) {
            val read = try {
                pty.read(chunk)
            } catch (e: Throwable) {
                -1
            }
            if (read < 0) break
            synchronized(lock) { model.feed(chunk, read) }
            onChange()
        }
        isAlive = false
        onChange()
    }

    private companion object {
        const val READ_BYTES = 8192
    }
}

/**
 * Owns the live sessions, keyed by whatever the application calls them.
 *
 * Deliberately takes an id, a directory and a command line rather than any type of the
 * application's: this module knows nothing about worktrees or projects, and that is what keeps the
 * dependency running one way.
 */
class TerminalSessions(
    private val launcher: PtyLauncher = FfmPtyLauncher(),
    private val scrollback: Int = TerminalBuffer.DEFAULT_SCROLLBACK,
    /** Where `OSC 52` puts what a program asks to copy. */
    private val clipboard: TerminalClipboard = TerminalClipboard.None,
) {

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    /**
     * The session for [id], started if it is not already running.
     *
     * Reattaching rather than restarting is the contract the whole pane rests on: leaving a tab and
     * coming back has to find the same shell, with whatever it was running still running.
     */
    fun getOrCreate(
        id: String,
        workDir: String,
        command: List<String>,
        env: Map<String, String>,
        columns: Int = INITIAL_COLUMNS,
        rows: Int = INITIAL_ROWS,
        onChange: () -> Unit = {},
        onTitle: (String) -> Unit = {},
    ): TerminalSession {
        sessions[id]?.let { existing ->
            if (existing.isAlive) return existing
            existing.close()
            sessions.remove(id)
        }
        val pty = launcher.start(command, workDir, env, WinSize(columns, rows))
        val session = TerminalSession(id, pty, columns, rows, scrollback, onChange, onTitle, clipboard)
        sessions[id] = session
        return session
    }

    fun get(id: String): TerminalSession? = sessions[id]

    fun close(id: String) {
        sessions.remove(id)?.close()
    }

    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    private companion object {
        /** Replaced by the real size as soon as the pane is laid out; a child must start somewhere. */
        const val INITIAL_COLUMNS = 80
        const val INITIAL_ROWS = 24
    }
}
