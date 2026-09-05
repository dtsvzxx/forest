package io.mainactor.worktree.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.mainactor.worktree.TerminalSession
import io.mainactor.worktree.platform.FileSystemAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class NoopBackend(val name: String) : TerminalBackend {
    override var onFocusGained: (sessionId: String) -> Unit = {}
    val closed = mutableListOf<String>()
    var closedAll = false

    override fun close(id: String) {
        closed += id
    }

    override fun closeAll() {
        closedAll = true
    }

    @Composable
    override fun Pane(session: TerminalSession, focused: Boolean, modifier: Modifier) = Unit
}

/** Just enough file system to hold one setting file. */
private class MemoryFiles : FileSystemAccess {
    val files = mutableMapOf<String, String>()
    override fun exists(path: String) = path in files
    override fun isDirectory(path: String) = false
    override fun readText(path: String) = files.getValue(path)
    override fun writeText(path: String, text: String) { files[path] = text }
    override fun createDirectories(path: String) = Unit
    override fun homeDir() = "/home"
    override fun nameOf(path: String) = path.substringAfterLast('/')
    override fun parentOf(path: String) = path.substringBeforeLast('/').ifEmpty { null }
    override fun resolve(base: String, child: String) = "$base/$child"
    override fun canonicalPath(path: String) = path
    override fun lastModifiedAt(path: String) = 0L
    override fun findOnPath(name: String): String? = null
    override fun listDirectory(path: String) = emptyList<String>()
    override fun fileSize(path: String) = 0L
    override fun readFrom(path: String, offset: Long, maxBytes: Int) = ByteArray(0)
    override fun now() = 0L
}

class TerminalBackendsTest {

    private val jediterm = NoopBackend("jediterm")
    private val native = NoopBackend("native")
    private var engine = TerminalEngine.JEDITERM
    private val backends = TerminalBackends(jediterm, native, { engine })

    /**
     * A running pane cannot change engine: its process is attached to one implementation. Changing
     * the setting has to decide what the *next* pane opens on and leave the live ones alone —
     * which is also what lets both engines be on screen at once, side by side, for comparison.
     */
    @Test
    fun `each pane keeps the engine it was opened with`() {
        assertSame(jediterm, backends.backendFor("first"))

        engine = TerminalEngine.NATIVE
        assertSame(jediterm, backends.backendFor("first"), "a live pane must not change engine")
        assertSame(native, backends.backendFor("second"), "a new pane follows the setting")

        // Closed and reopened, it follows the setting like any new pane.
        backends.close("first")
        assertSame(native, backends.backendFor("first"))
    }

    @Test
    fun `closing reaches the engine that owns the pane`() {
        engine = TerminalEngine.NATIVE
        backends.backendFor("a")
        backends.close("a")
        assertEquals(listOf("a"), native.closed)
        assertTrue(jediterm.closed.isEmpty(), "the other engine was told about a pane it never had")
    }

    /** Closing the project ends every pane, whichever engine each one happened to be on. */
    @Test
    fun `closing everything reaches both engines`() {
        backends.closeAll()
        assertTrue(jediterm.closedAll && native.closedAll)
    }

    @Test
    fun `the focus report is wired to whichever engine reports it`() {
        val seen = mutableListOf<String>()
        backends.onFocusGained = { seen += it }
        jediterm.onFocusGained("from-swing")
        native.onFocusGained("from-compose")
        assertEquals(listOf("from-swing", "from-compose"), seen)
    }
}

class TerminalEngineSettingTest {

    private val files = MemoryFiles()
    private val setting = TerminalEngineSetting(files)

    /** The engine that has run every pane so far stays the default until the new one earns it. */
    @Test
    fun `with nothing configured the established engine is used`() {
        assertEquals(TerminalEngine.JEDITERM, setting.read())
    }

    @Test
    fun `the choice survives in a file beside the other preferences`() {
        setting.write(TerminalEngine.NATIVE)
        assertEquals(TerminalEngine.NATIVE, setting.read())
        assertEquals("native", files.files["/home/.worktree/terminal"])
    }

    /** A file someone edited by hand should not leave the app without a terminal. */
    @Test
    fun `an unreadable setting falls back rather than failing`() {
        files.files["/home/.worktree/terminal"] = "quantum"
        assertEquals(TerminalEngine.JEDITERM, setting.read())
    }
}
