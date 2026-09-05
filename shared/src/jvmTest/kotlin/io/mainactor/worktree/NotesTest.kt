package io.mainactor.worktree

import io.mainactor.worktree.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteTest {

    /**
     * The first line is the name, which is why there is no name to fill in.
     *
     * Naming a thought is a second job, and one nobody does when the thought is the point.
     */
    @Test
    fun `a note is named by its first line`() {
        val note = Note("1", "Rewrite the pty layer\n\nUse FFM so nothing native ships.")
        assertEquals("Rewrite the pty layer", note.title)
        assertEquals("Use FFM so nothing native ships.", note.preview)
    }

    @Test
    fun `leading blank lines are not the name`() {
        val note = Note("1", "\n\n   \nthe actual idea\nand more")
        assertEquals("the actual idea", note.title)
        assertEquals("and more", note.preview)
    }

    @Test
    fun `an empty note says so rather than showing nothing`() {
        val note = Note("1", "   \n  ")
        assertTrue(note.isEmpty)
        assertEquals("Empty note", note.title)
        assertEquals("", note.preview)
    }

    @Test
    fun `a one-line note has a name and no preview`() {
        val note = Note("1", "just the one line")
        assertEquals("just the one line", note.title)
        assertEquals("", note.preview)
    }
}
