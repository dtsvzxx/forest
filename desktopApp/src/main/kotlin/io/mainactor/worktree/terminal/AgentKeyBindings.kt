package io.mainactor.worktree.terminal

import io.mainactor.worktree.platform.Os
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/** One chord and what it means. */
data class AgentBinding(
    val label: String,
    val keyCode: Int,
    val modifiers: Int,
) {
    fun matches(event: KeyEvent): Boolean =
        event.keyCode == keyCode && (event.modifiersEx and RELEVANT_MODIFIERS) == modifiers

    private companion object {
        /** Ignore the state of keys we do not care about, like caps lock. */
        const val RELEVANT_MODIFIERS =
            InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or
                InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK
    }
}

data class AgentBindings(
    val splitRight: AgentBinding,
    val splitDown: AgentBinding,
    val newAgent: AgentBinding,
    val closePane: AgentBinding,
    val zoomPane: AgentBinding,
) {
    val all: List<AgentBinding> get() = listOf(splitRight, splitDown, newAgent, closePane, zoomPane)
}

/**
 * The shortcuts differ per platform for a concrete reason, not for taste: on Linux and Windows a
 * bare `Ctrl` chord belongs to the shell — `Ctrl+D` is end-of-input, `Ctrl+W` deletes a word — so
 * the wall uses `Ctrl+Shift`, the convention terminal emulators already follow there. macOS keeps
 * `Cmd`, which never reaches the PTY, so the familiar iTerm chords are free to use.
 */
fun defaultAgentBindings(isMac: Boolean = Os.isMac): AgentBindings {
    val primary = if (isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
    val prefix = if (isMac) "⌘" else "Ctrl+Shift+"
    return AgentBindings(
        splitRight = AgentBinding("${prefix}D", KeyEvent.VK_D, primary),
        splitDown = AgentBinding(
            label = if (isMac) "⌘⇧D" else "Ctrl+Shift+E",
            keyCode = if (isMac) KeyEvent.VK_D else KeyEvent.VK_E,
            modifiers = if (isMac) primary or InputEvent.SHIFT_DOWN_MASK else primary,
        ),
        newAgent = AgentBinding("${prefix}T", KeyEvent.VK_T, primary),
        closePane = AgentBinding("${prefix}W", KeyEvent.VK_W, primary),
        zoomPane = AgentBinding("${prefix}Enter", KeyEvent.VK_ENTER, primary),
    )
}

/**
 * Installs the wall's shortcuts as a global key hook.
 *
 * A pane is a heavyweight Swing terminal that owns the keyboard focus, so Compose's own key
 * handling never sees these chords — AWT delivers them straight to the focused component.
 * A [KeyEventDispatcher] runs before that dispatch, which is the only place the wall can claim
 * them from. [enabled] keeps them out of the way while the project view is showing.
 */
class AgentKeyBindings(
    private val bindings: AgentBindings,
    private val enabled: () -> Boolean,
    private val onSplitRight: () -> Unit,
    private val onSplitDown: () -> Unit,
    private val onNewAgent: () -> Unit,
    private val onClosePane: () -> Unit,
    private val onZoomPane: () -> Unit,
) {
    private val dispatcher = KeyEventDispatcher { event ->
        if (event.id != KeyEvent.KEY_PRESSED || !enabled()) return@KeyEventDispatcher false
        val action = when {
            bindings.splitDown.matches(event) -> onSplitDown
            bindings.splitRight.matches(event) -> onSplitRight
            bindings.newAgent.matches(event) -> onNewAgent
            bindings.closePane.matches(event) -> onClosePane
            bindings.zoomPane.matches(event) -> onZoomPane
            else -> null
        }
        action?.invoke()
        // Consuming stops the chord reaching the shell behind it.
        action != null
    }

    fun install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    }

    fun uninstall() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
    }
}
