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

    @Test
    fun `every chord is distinct`() {
        listOf(defaultAgentBindings(isMac = true), defaultAgentBindings(isMac = false)).forEach { bindings ->
            val chords = bindings.all.map { it.keyCode to it.modifiers }
            assertEquals(chords.size, chords.distinct().size, "two actions share a chord: $chords")
        }
    }
}
