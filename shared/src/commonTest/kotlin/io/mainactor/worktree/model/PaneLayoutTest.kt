package io.mainactor.worktree.model

import io.mainactor.worktree.TerminalSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaneLayoutTest {

    private fun pane(id: String) = TerminalSession(id = id, title = id, workDir = "/repo")
    private fun leaf(id: String) = PaneNode.Leaf(pane(id))

    @Test
    fun `splitting a leaf puts the new pane second, so right means right`() {
        val tree = leaf("a").splitLeaf("a", SplitAxis.ROW, pane("b"), splitId = "s1")

        val split = tree as PaneNode.Split
        assertEquals(SplitAxis.ROW, split.axis)
        assertEquals(listOf("a", "b"), tree.sessions().map { it.id })
    }

    @Test
    fun `splitting reaches a leaf nested deep in the tree`() {
        val tree = leaf("a")
            .splitLeaf("a", SplitAxis.ROW, pane("b"), "s1")
            .splitLeaf("b", SplitAxis.COLUMN, pane("c"), "s2")
            .splitLeaf("c", SplitAxis.ROW, pane("d"), "s3")

        assertEquals(listOf("a", "b", "c", "d"), tree.sessions().map { it.id })
        assertTrue(tree.contains("d"))
    }

    @Test
    fun `splitting an id that is not there changes nothing`() {
        val tree = leaf("a").splitLeaf("a", SplitAxis.ROW, pane("b"), "s1")

        assertEquals(tree, tree.splitLeaf("ghost", SplitAxis.ROW, pane("c"), "s2"))
    }

    @Test
    fun `closing a pane gives its space to its sibling instead of leaving a hole`() {
        val tree = leaf("a")
            .splitLeaf("a", SplitAxis.ROW, pane("b"), "s1")
            .splitLeaf("b", SplitAxis.COLUMN, pane("c"), "s2")

        val afterClose = tree.removeLeaf("b")

        // The inner split collapses: "c" takes the whole half "b" and "c" shared.
        assertEquals(listOf("a", "c"), afterClose!!.sessions().map { it.id })
        val split = afterClose as PaneNode.Split
        assertEquals("s1", split.id)
        assertTrue(split.second is PaneNode.Leaf)
    }

    @Test
    fun `closing the last pane empties the wall`() {
        assertNull(leaf("a").removeLeaf("a"))
    }

    @Test
    fun `closing an id that is not there leaves the tree alone`() {
        val tree = leaf("a").splitLeaf("a", SplitAxis.ROW, pane("b"), "s1")

        assertEquals(tree, tree.removeLeaf("ghost"))
    }

    @Test
    fun `a divider moves only its own split`() {
        val tree = leaf("a")
            .splitLeaf("a", SplitAxis.ROW, pane("b"), "s1")
            .splitLeaf("b", SplitAxis.COLUMN, pane("c"), "s2")

        val resized = tree.withFraction("s2", 0.8f) as PaneNode.Split

        assertEquals(0.5f, resized.fraction, "the outer split should not have moved")
        val inner = resized.second as PaneNode.Split
        assertEquals(0.8f, inner.fraction)
    }

    @Test
    fun `a divider cannot be dragged past a pane's minimum`() {
        val tree = leaf("a").splitLeaf("a", SplitAxis.ROW, pane("b"), "s1")

        assertEquals(MIN_PANE_FRACTION, (tree.withFraction("s1", 0f) as PaneNode.Split).fraction)
        assertEquals(1f - MIN_PANE_FRACTION, (tree.withFraction("s1", 1f) as PaneNode.Split).fraction)
    }

    @Test
    fun `sessions come back in reading order`() {
        val tree = PaneNode.Split(
            id = "root",
            axis = SplitAxis.ROW,
            first = leaf("left"),
            second = PaneNode.Split("inner", SplitAxis.COLUMN, leaf("top"), leaf("bottom")),
        )

        assertEquals(listOf("left", "top", "bottom"), tree.sessions().map { it.id })
    }
}
