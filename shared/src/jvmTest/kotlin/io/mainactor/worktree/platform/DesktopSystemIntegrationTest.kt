package io.mainactor.worktree.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reveal command differs per desktop and cannot be exercised on all three from one machine,
 * so the command line itself is what gets asserted.
 */
class DesktopSystemIntegrationTest {

    @Test
    fun `macOS selects the item in Finder`() {
        assertEquals(
            listOf("open", "-R", "/Users/dev/repo"),
            revealCommand("/Users/dev/repo", isMac = true, isWindows = false),
        )
        assertEquals("Show in Finder", revealLabelFor(isMac = true, isWindows = false))
    }

    @Test
    fun `Windows passes the switch and the path as one argument`() {
        // `explorer /select, C:\...` with a space silently opens Documents instead.
        assertEquals(
            listOf("explorer.exe", "/select,C:\\dev\\repo"),
            revealCommand("C:\\dev\\repo", isMac = false, isWindows = true),
        )
        assertEquals("Show in Explorer", revealLabelFor(isMac = false, isWindows = true))
    }

    @Test
    fun `Linux opens the containing folder, since selecting is not portable`() {
        assertEquals(
            listOf("xdg-open", "/home/dev"),
            revealCommand("/home/dev/repo", isMac = false, isWindows = false),
        )
        assertEquals("Show in Files", revealLabelFor(isMac = false, isWindows = false))
    }

    @Test
    fun `a path with no parent still opens something`() {
        assertEquals(listOf("xdg-open", "repo"), revealCommand("repo", isMac = false, isWindows = false))
    }
}
