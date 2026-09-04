package io.mainactor.worktree.model

import io.mainactor.worktree.TerminalSession

/** Which way a split divides its space. */
enum class SplitAxis {
    /** Side by side — "split right". */
    ROW,

    /** Stacked — "split down". */
    COLUMN,
}

/**
 * The agent wall's layout: a binary tree of panes, the way a terminal multiplexer arranges them.
 *
 * A tree rather than an auto-arranged grid because the operation the user reaches for is "split
 * *this* pane", which only means something relative to an existing pane.
 */
sealed interface PaneNode {

    data class Leaf(val session: TerminalSession) : PaneNode

    data class Split(
        val id: String,
        val axis: SplitAxis,
        val first: PaneNode,
        val second: PaneNode,
        /** Share of the space taken by [first]. */
        val fraction: Float = 0.5f,
    ) : PaneNode
}

/** Every session in the tree, left to right and top to bottom. */
fun PaneNode.sessions(): List<TerminalSession> = when (this) {
    is PaneNode.Leaf -> listOf(session)
    is PaneNode.Split -> first.sessions() + second.sessions()
}

fun PaneNode.contains(sessionId: String): Boolean = sessions().any { it.id == sessionId }

/**
 * Replaces the leaf holding [targetId] with a split of itself and [session].
 *
 * The new pane always takes the second half, so "split right" puts it to the right and "split
 * down" puts it below — which is what those words promise.
 */
fun PaneNode.splitLeaf(
    targetId: String,
    axis: SplitAxis,
    session: TerminalSession,
    splitId: String,
): PaneNode = when (this) {
    is PaneNode.Leaf ->
        if (this.session.id == targetId) {
            PaneNode.Split(id = splitId, axis = axis, first = this, second = PaneNode.Leaf(session))
        } else {
            this
        }

    is PaneNode.Split -> copy(
        first = first.splitLeaf(targetId, axis, session, splitId),
        second = second.splitLeaf(targetId, axis, session, splitId),
    )
}

/**
 * Drops the leaf holding [sessionId]. The removed pane's sibling takes over the space its parent
 * split occupied, so closing panes collapses the tree instead of leaving holes in it.
 *
 * Returns null when the tree becomes empty.
 */
fun PaneNode.removeLeaf(sessionId: String): PaneNode? = when (this) {
    is PaneNode.Leaf -> if (session.id == sessionId) null else this

    is PaneNode.Split -> {
        val newFirst = first.removeLeaf(sessionId)
        val newSecond = second.removeLeaf(sessionId)
        when {
            newFirst == null -> newSecond
            newSecond == null -> newFirst
            else -> copy(first = newFirst, second = newSecond)
        }
    }
}

/** Adjusts one split's divider, leaving the rest of the tree alone. */
fun PaneNode.withFraction(splitId: String, fraction: Float): PaneNode = when (this) {
    is PaneNode.Leaf -> this
    is PaneNode.Split ->
        if (id == splitId) {
            copy(fraction = fraction.coerceIn(MIN_PANE_FRACTION, 1f - MIN_PANE_FRACTION))
        } else {
            copy(first = first.withFraction(splitId, fraction), second = second.withFraction(splitId, fraction))
        }
}

/** Keeps a pane from being dragged down to nothing. */
const val MIN_PANE_FRACTION = 0.08f

/**
 * Rebuilds the tree with every session passed through [transform], keeping its shape.
 *
 * Used when a worktree is renamed: the shells keep running — a moved directory is the same inode,
 * so their own working directory follows — but the path each pane records for it does not, and that
 * path is what finds its usage and takes you back to it in the project view.
 */
fun PaneNode.mapSessions(transform: (TerminalSession) -> TerminalSession): PaneNode = when (this) {
    is PaneNode.Leaf -> PaneNode.Leaf(transform(session))
    is PaneNode.Split -> copy(first = first.mapSessions(transform), second = second.mapSessions(transform))
}
