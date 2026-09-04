package io.mainactor.worktree.terminal

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The agent wall runs a whole grid of shells at once, which is the part of this app most likely to
 * fall over: every pane is a real PTY behind a heavyweight Swing widget. This drives the manager
 * directly, without a window, so the failure would be a test rather than a blank grid.
 */
class TerminalSessionManagerTest {

    private lateinit var workDir: java.io.File
    private lateinit var manager: TerminalSessionManager

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("worktree-terminals").toFile()
        manager = TerminalSessionManager()
    }

    @AfterTest
    fun tearDown() {
        manager.closeAll()
        workDir.deleteRecursively()
    }

    @Test
    fun `a wall of shells runs side by side`() {
        val handles = (1..WALL_SIZE).map { i ->
            manager.getOrCreate(id = "agent-$i", workDir = workDir.path, title = "agent $i")
        }

        assertEquals(WALL_SIZE, handles.map { it.id }.distinct().size)
        assertTrue(handles.all { it.isAlive }, "every pane's shell should be running")
        // Distinct widgets, or two panes would fight over one component.
        assertEquals(WALL_SIZE, handles.map { it.widget }.distinct().size)
    }

    @Test
    fun `returning to a pane reattaches the running shell rather than starting a new one`() {
        val first = manager.getOrCreate("agent-1", workDir.path, "agent 1")

        val again = manager.getOrCreate("agent-1", workDir.path, "agent 1")

        assertSame(first, again, "switching panes must not restart the shell")
        assertSame(first.widget, again.widget)
    }

    @Test
    fun `closing one pane leaves the others alone`() {
        val a = manager.getOrCreate("agent-a", workDir.path, "a")
        val b = manager.getOrCreate("agent-b", workDir.path, "b")

        manager.close("agent-a")
        waitUntil { !a.isAlive }

        assertTrue(!a.isAlive, "the closed pane's shell should be gone")
        assertTrue(b.isAlive, "closing one pane must not disturb another")
    }

    @Test
    fun `closing everything stops every shell`() {
        val handles = (1..4).map { manager.getOrCreate("agent-$it", workDir.path, "agent $it") }

        manager.closeAll()
        waitUntil { handles.none { it.isAlive } }

        assertTrue(handles.none { it.isAlive })
    }

    @Test
    fun `each pane reports its own id when it takes the keyboard focus`() {
        val focused = mutableListOf<String>()
        manager.onFocusGained = { focused += it }
        val a = manager.getOrCreate("agent-a", workDir.path, "a")
        val b = manager.getOrCreate("agent-b", workDir.path, "b")

        // Swing consumes clicks inside the terminal, so the wall only learns about focus from the
        // widget. A synthesised event is dropped without a realised window, so the listeners are
        // invoked directly: what this pins down is that one is attached to the *terminal panel*
        // of each pane and reports that pane's own id.
        listOf(a, b, a).forEach { handle -> handle.notifyFocusGained() }

        assertEquals(listOf("agent-a", "agent-b", "agent-a"), focused)
    }

    @Test
    fun `focusing a pane is safe to ask for at any time`() {
        val handle = manager.getOrCreate("agent-1", workDir.path, "one")

        // Off-screen there is nothing to focus; it must not throw, because the wall requests focus
        // whenever the selected pane changes.
        handle.requestFocus()
        assertFalse(handle.hasKeyboardFocus)
    }

    @Test
    fun `a pane shows no scrollbar and gives the text the full width`() {
        val handle = manager.getOrCreate("agent-1", workDir.path, "one")
        val widget = handle.widget

        widget.setSize(800, 400)
        widget.doLayout()
        // The layered pane holding the terminal has to be laid out too before its children have
        // real bounds.
        widget.components.forEach { it.doLayout() }

        val scrollbars = widget.allComponents().filterIsInstance<javax.swing.JScrollBar>()
        assertTrue(scrollbars.isNotEmpty(), "the widget still needs its scrollbar model")
        assertTrue(scrollbars.none { it.isVisible }, "a scrollbar is still on screen")
        assertEquals(
            widget.width,
            widget.terminalPanel.width,
            "the hidden scrollbar is still reserving width",
        )
    }

    private fun java.awt.Container.allComponents(): List<java.awt.Component> =
        components.flatMap { child ->
            listOf(child) + if (child is java.awt.Container) child.allComponents() else emptyList()
        }

    private fun TerminalSessionHandle.notifyFocusGained() {
        val panel = widget.terminalPanel
        val event = java.awt.event.FocusEvent(panel, java.awt.event.FocusEvent.FOCUS_GAINED)
        panel.focusListeners.forEach { it.focusGained(event) }
    }

    private fun waitUntil(deadlineMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < until && !condition()) Thread.sleep(50)
    }

    private companion object {
        /** The 3x2 grid the feature was drawn from. */
        const val WALL_SIZE = 6
    }
}
