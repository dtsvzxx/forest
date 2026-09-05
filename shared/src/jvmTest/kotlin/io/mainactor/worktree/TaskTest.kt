package io.mainactor.worktree

import io.mainactor.worktree.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskTest {

    /**
     * The first line is the name, which is why there is no name to fill in.
     *
     * Naming a piece of work is a second job, and one nobody does when the work is the point.
     */
    @Test
    fun `a task is named by its first line`() {
        val task = Task("1", "Rewrite the pty layer\n\nUse FFM so nothing native ships.")
        assertEquals("Rewrite the pty layer", task.title)
        assertEquals("Use FFM so nothing native ships.", task.preview)
    }

    @Test
    fun `leading blank lines are not the name`() {
        val task = Task("1", "\n\n   \nthe actual idea\nand more")
        assertEquals("the actual idea", task.title)
        assertEquals("and more", task.preview)
    }

    @Test
    fun `an empty task says so rather than showing nothing`() {
        val task = Task("1", "   \n  ")
        assertTrue(task.isEmpty)
        assertEquals("Empty task", task.title)
        assertEquals("", task.preview)
    }

    @Test
    fun `a one-line task has a name and no preview`() {
        val task = Task("1", "just the one line")
        assertEquals("just the one line", task.title)
        assertEquals("", task.preview)
    }

    /** What the agent wall offers: written down, not finished. */
    @Test
    fun `only an unfinished task with something in it is open`() {
        assertTrue(Task("1", "write the release notes").isOpen)
        assertFalse(Task("1", "write the release notes", done = true).isOpen)
        assertFalse(Task("1", "   ").isOpen)
    }
}
