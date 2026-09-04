package io.mainactor.worktree.git

import io.mainactor.worktree.model.ChangedFile
import io.mainactor.worktree.platform.FileSystemAccess

/**
 * Dates a worktree's uncommitted work from the filesystem.
 *
 * Two approaches that do *not* work, and why:
 *
 *  - **The worktree directory's mtime.** A directory's mtime moves only when entries are added,
 *    removed or renamed inside it, so editing an existing file leaves both its own directory and
 *    the worktree root untouched. Measured: appending to `src/a.txt` changed neither.
 *  - **Walking the worktree.** Accurate, but ~300 ms for a 20 000-file checkout — times however
 *    many worktrees the repository has, on every refresh. It would also be dominated by build
 *    output rather than by anything the user wrote.
 *
 * What does work is statting only the files git has already named as changed. That list arrives
 * free with the status the app runs anyway, and is a handful of entries in normal use.
 *
 * The git index is deliberately *not* consulted: `git worktree add` and `git commit` rewrite it
 * with the current time, which would bury the commit dates it is meant to complement.
 */
class WorktreeActivity(private val fs: FileSystemAccess) {

    /**
     * Newest mtime among [files], whose paths are relative to [worktreePath], as epoch seconds.
     *
     * Capped: a build that dropped thousands of untracked files should not turn one refresh into
     * thousands of stat calls.
     */
    fun newestChangedFileAt(worktreePath: String, files: List<ChangedFile>, limit: Int = MAX_STATS): Long =
        files.asSequence()
            .filterNot { it.ignored }
            .take(limit)
            .map { fs.lastModifiedAt(fs.resolve(worktreePath, it.path)) }
            .maxOrNull()
            ?: 0L

    private companion object {
        /** Enough to date any realistic working set; a pathological one is not worth the syscalls. */
        const val MAX_STATS = 200
    }
}
