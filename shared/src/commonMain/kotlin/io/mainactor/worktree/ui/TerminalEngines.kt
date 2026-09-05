package io.mainactor.worktree.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which terminal a new pane opens on, offered to the user.
 *
 * Filled in by the platform layer for the same reason [AgentShortcuts] and [WindowChrome] are: the
 * engines are constructed in `main.kt`, and nothing in this module knows or should know that one of
 * them is a Swing widget. Empty by default, so a build with one engine — and every render test —
 * simply has no such menu.
 *
 * The choice takes effect for the *next* pane. A running one cannot change engine, because its
 * process is attached to one implementation; saying so in the menu is cheaper than letting someone
 * discover it by switching and seeing nothing happen.
 */
object TerminalEngines {

    data class Option(val id: String, val label: String, val detail: String)

    var options: List<Option> by mutableStateOf(emptyList())

    var selected: String by mutableStateOf("")

    var onSelect: (String) -> Unit = {}

    /** Records the choice and remembers it, so the menu shows the new one at once. */
    fun choose(id: String) {
        selected = id
        onSelect(id)
    }
}
