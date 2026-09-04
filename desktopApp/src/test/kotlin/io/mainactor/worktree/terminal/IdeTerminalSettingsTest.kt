package io.mainactor.worktree.terminal

import com.jediterm.terminal.TerminalColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@Suppress("DEPRECATION")
class IdeTerminalSettingsTest {

    private val settings = IdeTerminalSettings()

    @Test
    fun `the default style carries the app's own colours`() {
        val style = settings.defaultStyle

        assertEquals(IdeTerminalSettings.DEFAULT_FOREGROUND, style.foreground)
        assertEquals(IdeTerminalSettings.DEFAULT_BACKGROUND, style.background)
    }

    @Test
    fun `black never leaks in as the default`() {
        // JediTerm's own default style is black on white, and it is the *style* that cells and the
        // block cursor are painted from — overriding only the derived colour getters left the
        // cursor black on a dark background.
        assertNotEquals(TerminalColor.BLACK, settings.defaultStyle.foreground)
        assertNotEquals(TerminalColor.BLACK, settings.defaultForeground)
        assertNotEquals(TerminalColor.WHITE, settings.defaultBackground)
    }

    @Test
    fun `the derived getters follow the style`() {
        assertEquals(settings.defaultStyle.foreground, settings.defaultForeground)
        assertEquals(settings.defaultStyle.background, settings.defaultBackground)
    }
}
