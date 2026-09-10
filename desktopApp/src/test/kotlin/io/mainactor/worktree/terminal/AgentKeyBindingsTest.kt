package io.mainactor.worktree.terminal

import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentKeyBindingsTest {

    private val component: Component = JPanel()

    private fun press(keyCode: Int, modifiers: Int) =
        KeyEvent(component, KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)

    @Test
    fun `macOS uses Command, which never reaches the shell`() {
        val bindings = defaultAgentBindings(isMac = true)

        assertTrue(bindings.splitRight.matches(press(KeyEvent.VK_D, InputEvent.META_DOWN_MASK)))
        assertEquals("⌘D", bindings.splitRight.label)
        assertEquals("⌘⇧D", bindings.splitDown.label)
    }

    @Test
    fun `elsewhere it is Ctrl+Shift, because bare Ctrl belongs to the shell`() {
        val bindings = defaultAgentBindings(isMac = false)
        val ctrlShift = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

        assertTrue(bindings.splitRight.matches(press(KeyEvent.VK_D, ctrlShift)))
        // Plain Ctrl+D is end-of-input and must pass straight through to the agent.
        assertFalse(bindings.splitRight.matches(press(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK)))
        assertEquals("Ctrl+Shift+D", bindings.splitRight.label)
    }

    @Test
    fun `split down does not answer to the split right chord`() {
        val mac = defaultAgentBindings(isMac = true)

        // ⌘D and ⌘⇧D differ by one modifier; matching has to be exact, not "contains".
        assertFalse(mac.splitRight.matches(press(KeyEvent.VK_D, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)))
        assertTrue(mac.splitDown.matches(press(KeyEvent.VK_D, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)))
    }

    @Test
    fun `an unrelated modifier does not fire a shortcut`() {
        val bindings = defaultAgentBindings(isMac = true)

        assertFalse(bindings.splitRight.matches(press(KeyEvent.VK_D, InputEvent.ALT_DOWN_MASK)))
        assertFalse(bindings.splitRight.matches(press(KeyEvent.VK_D, 0)))
    }

    /**
     * The one chord that is the same everywhere, and the only one taken rather than chosen.
     *
     * The others diverge because a bare `Ctrl` combination belongs to the shell on Linux and
     * Windows. A function key with `Alt` is nobody's shell binding, so the reason does not apply —
     * and ⌥F12 is what the IDE this app is styled after opens its terminal with.
     */
    @Test
    fun `the terminal chord is the same on both platforms`() {
        val mac = defaultAgentBindings(isMac = true)
        val other = defaultAgentBindings(isMac = false)

        assertEquals(mac.terminal.keyCode, other.terminal.keyCode)
        assertEquals(mac.terminal.modifiers, other.terminal.modifiers)
        assertTrue(mac.terminal.matches(press(KeyEvent.VK_F12, InputEvent.ALT_DOWN_MASK)))
        // F12 on its own is a function key a program may want; only the Alt form is claimed.
        assertFalse(mac.terminal.matches(press(KeyEvent.VK_F12, 0)))
        assertEquals("⌥F12", mac.terminal.label)
        assertEquals("Alt+F12", other.terminal.label)
    }

    /**
     * The terminal is offered on both screens, so its chord is gated on its own.
     *
     * The other five only mean something where there are panes to split and close, and letting
     * them through in the project view would claim chords from whatever is focused there.
     */
    @Test
    fun `the terminal chord fires off the wall, where the wall's own do not`() {
        var terminal = 0
        var newAgent = 0
        val bindings = defaultAgentBindings(isMac = true)
        val hotkeys = AgentKeyBindings(
            bindings = bindings,
            enabled = { false },
            terminalEnabled = { true },
            onSplitRight = {},
            onSplitDown = {},
            onNewAgent = { newAgent++ },
            onClosePane = {},
            onZoomPane = {},
            onTerminal = { terminal++ },
        )

        assertTrue(
            hotkeys.handle(press(bindings.terminal.keyCode, bindings.terminal.modifiers)),
            "the terminal chord was not claimed, so it would reach the shell behind it",
        )
        assertFalse(hotkeys.handle(press(bindings.newAgent.keyCode, bindings.newAgent.modifiers)))

        assertEquals(1, terminal, "the terminal chord is gated on its own, not on the wall's")
        assertEquals(0, newAgent, "the wall's chords must stay out of the project view")
    }

    @Test
    fun `every chord is distinct`() {
        listOf(defaultAgentBindings(isMac = true), defaultAgentBindings(isMac = false)).forEach { bindings ->
            val chords = bindings.all.map { it.keyCode to it.modifiers }
            assertEquals(chords.size, chords.distinct().size, "two actions share a chord: $chords")
        }
    }
}
