package io.mainactor.worktree.window

import io.mainactor.worktree.platform.Os
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the title bar is really gone rather than merely asked to go.
 *
 * A decorated macOS window takes a 28pt strip off the top of its content for the bar; with the
 * three client properties set, the content pane gets the whole window and the buttons keep
 * floating over it. The insets are what AppKit actually did, so they say more than reading the
 * properties back would — and `pack()` creates the native window without ever showing one, which
 * is what keeps this test out of the way on a developer's screen.
 */
class TitleBarTest {

    @Test
    fun `hiding the title bar gives the content the whole window`() {
        if (!Os.isMac || GraphicsEnvironment.isHeadless()) return

        val decorated = JFrame()
        val hidden = JFrame().also { hideTitleBar(it.rootPane) }
        try {
            decorated.pack()
            hidden.pack()
            // Without this the test would pass on a platform that has no title bar to remove.
            assertTrue(decorated.insets.top > 0, "a plain window has no title bar to hide")
            assertEquals(0, hidden.insets.top, "the title bar still takes a strip off the content")
        } finally {
            decorated.dispose()
            hidden.dispose()
        }
    }
}
