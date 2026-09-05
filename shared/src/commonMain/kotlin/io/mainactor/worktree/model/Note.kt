package io.mainactor.worktree.model

/**
 * An idea, written the way it will be handed over: as a prompt.
 *
 * There is no title field, and that is deliberate. Naming a thought is a second job, and one nobody
 * does when the thought is the point — so the first line *is* the name, the way it is in a commit
 * message. Write one line and it is a title; write ten and the first one names the other nine.
 */
data class Note(
    val id: String,
    val body: String,
    /** When it was last written to, so the list can put what you were just thinking about first. */
    val updatedAt: Long = 0,
) {
    val title: String
        get() = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_TITLE)
            ?: "Empty note"

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

    private companion object {
        const val MAX_TITLE = 80
    }
}
