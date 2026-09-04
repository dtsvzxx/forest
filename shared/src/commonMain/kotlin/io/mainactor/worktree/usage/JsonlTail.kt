package io.mainactor.worktree.usage

import io.mainactor.worktree.platform.FileSystemAccess

/**
 * Reads an append-only JSONL file forward, handing over complete lines only.
 *
 * Both agent CLIs write their session logs this way — appended line by line, never rewritten — so
 * the whole file is read once and every later poll costs only the bytes that were added. That
 * matters: Claude Code's largest transcript on this machine is 59 MB and Codex's rollouts run to
 * several megabytes, and re-reading those every few seconds behind a UI is not a thing to do.
 *
 * What the lines *mean* is not this class's business; see [ClaudeTranscript] and [CodexRollout],
 * which fold them very differently.
 */
class JsonlTail(private val path: String) {

    /** Byte offset just past the last *complete* line consumed. */
    var offset: Long = 0
        private set

    /**
     * Reads whatever has been appended since the last call.
     *
     * [onReset] fires when the file turns out to be shorter than where we stopped — a different
     * file wearing the same name — so the caller can drop what it accumulated from the old one.
     */
    fun poll(fs: FileSystemAccess, onReset: () -> Unit = {}, onLine: (String) -> Unit) {
        if (fs.fileSize(path) < offset) {
            offset = 0
            onReset()
        }

        while (true) {
            val chunk = fs.readFrom(path, offset, CHUNK_BYTES)
            if (chunk.isEmpty()) return

            val lastNewline = chunk.lastIndexOf(NEWLINE)
            if (lastNewline < 0) {
                // No line ends inside this chunk. Either the file's tail is still being written —
                // leave the offset alone and pick it up next poll — or one line is longer than a
                // whole chunk, and skipping it is the only option.
                if (chunk.size < CHUNK_BYTES) return
                offset += chunk.size
                continue
            }

            // A newline is always a character boundary, so decoding up to one never splits UTF-8.
            chunk.decodeToString(0, lastNewline + 1).lineSequence().forEach(onLine)
            offset += lastNewline + 1

            if (chunk.size < CHUNK_BYTES) return
        }
    }

    private companion object {
        /** Big enough that a poll is one read for any ordinary session log. */
        const val CHUNK_BYTES = 4 * 1024 * 1024
        const val NEWLINE = '\n'.code.toByte()
    }
}
