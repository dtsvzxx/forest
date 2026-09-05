package io.mainactor.worktree.model

/**
 * A piece of work, written the way it will be handed over: as a prompt.
 *
 * There is no title field, and that is deliberate. Naming a piece of work is a second job, and one
 * nobody does when the work is the point — so the first line *is* the name, the way it is in a
 * commit message. Write one line and it is a title; write ten and the first one names the other
 * nine.
 *
 * [done] is the whole of the state, because it is the only part an agent-driven task really has:
 * "sent to an agent" is not progress — a pane can be handed a task and finish, fail or be closed,
 * and none of that is visible from here. So the tracker records what the person knows, and asks
 * them for it once.
 */
data class Task(
    val id: String,
    val body: String,
    val done: Boolean = false,
    /** When it was last written to, so the list can put what you were just working on first. */
    val updatedAt: Long = 0,
) {
    val title: String
        get() = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_TITLE)
            ?: "Empty task"

    /** What is left after the first line, for a list that wants to show a little of the body. */
    val preview: String
        get() = body.lineSequence()
            .dropWhile { it.isBlank() }
            .drop(1)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(MAX_TITLE)
            .orEmpty()

    val isEmpty: Boolean get() = body.isBlank()

    /** Worth sending to an agent: there is something written, and it is not already finished. */
    val isOpen: Boolean get() = !done && !isEmpty

    private companion object {
        const val MAX_TITLE = 80
    }
}
