package io.mainactor.worktree.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.mainactor.worktree.TerminalSession
import io.mainactor.worktree.platform.FileSystemAccess

/** Which terminal a pane runs on. */
enum class TerminalEngine {
    /** JediTerm in a `SwingPanel` — what every pane has run on so far. */
    JEDITERM,

    /** The emulator in `:terminal`, drawn by Compose. */
    NATIVE,
    ;

    companion object {
        fun of(name: String?): TerminalEngine? =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/**
 * Runs both engines side by side and remembers which one each pane was opened on.
 *
 * The engine cannot be changed under a running pane — its process is attached to one
 * implementation — so this is not a global `if` but a routing table. Changing the setting decides
 * what the *next* pane opens on; the ones already running live out their lives on the engine they
 * started with, which means both can be on screen at once. That is also the best way to compare
 * them: two panes, side by side, running the same program.
 */
class TerminalBackends(
    private val jediterm: TerminalBackend,
    private val native: TerminalBackend,
    private val engine: () -> TerminalEngine,
) : TerminalBackend {

    private val owner = mutableMapOf<String, TerminalBackend>()

    override var onFocusGained: (sessionId: String) -> Unit = {}
        set(value) {
            field = value
            jediterm.onFocusGained = value
            native.onFocusGained = value
        }

    override fun sendPrompt(id: String, text: String) {
        owner[id]?.sendPrompt(id, text)
    }

    override fun close(id: String) {
        owner.remove(id)?.close(id) ?: run {
            // A pane the router never saw cannot be routed, so ask both; closing an id an engine
            // does not know is a no-op in either of them.
            jediterm.close(id)
            native.close(id)
        }
    }

    override fun closeAll() {
        owner.clear()
        jediterm.closeAll()
        native.closeAll()
    }

    @Composable
    override fun Pane(session: TerminalSession, focused: Boolean, modifier: Modifier) {
        backendFor(session.id).Pane(session, focused, modifier)
    }

    /**
     * The engine this session runs on, chosen once and remembered until it is closed.
     *
     * Separate from [Pane] so the routing can be asserted without composing anything — the part
     * worth testing is which engine a pane is bound to, not what it drew.
     */
    internal fun backendFor(id: String): TerminalBackend = owner.getOrPut(id) {
        when (engine()) {
            TerminalEngine.NATIVE -> native
            TerminalEngine.JEDITERM -> jediterm
        }
    }
}

/**
 * Where the choice is kept: `~/.worktree/terminal`, beside `recent` and `last`.
 *
 * `FOREST_TERMINAL` overrides the file rather than replacing it. The variable is how a developer
 * switches for one run; it is not how a user switches, because an app launched from Finder never
 * sees one — the same reason the packaged build could not find `claude` on its `PATH`.
 */
class TerminalEngineSetting(private val fs: FileSystemAccess) {

    private val file get() = fs.resolve(fs.resolve(fs.homeDir(), ".worktree"), "terminal")

    fun read(): TerminalEngine {
        TerminalEngine.of(System.getenv(ENVIRONMENT_VARIABLE))?.let { return it }
        if (!fs.exists(file)) return DEFAULT
        return TerminalEngine.of(runCatching { fs.readText(file) }.getOrNull()) ?: DEFAULT
    }

    fun write(engine: TerminalEngine) {
        runCatching {
            fs.createDirectories(fs.resolve(fs.homeDir(), ".worktree"))
            fs.writeText(file, engine.name.lowercase())
        }
    }

    private companion object {
        const val ENVIRONMENT_VARIABLE = "FOREST_TERMINAL"

        /** The engine that has run every pane so far stays the default until the new one earns it. */
        val DEFAULT = TerminalEngine.JEDITERM
    }
}
