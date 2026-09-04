package io.mainactor.worktree.model

/** A git repository the user has opened. Identified by the path of its *main* working tree. */
data class Project(
    val path: String,
    val name: String,
)

/** One entry of `git worktree list --porcelain`. */
data class Worktree(
    val path: String,
    val head: String? = null,
    val branch: String? = null,
    val isBare: Boolean = false,
    val isDetached: Boolean = false,
    val isLocked: Boolean = false,
    val lockReason: String? = null,
    val isPrunable: Boolean = false,
    val prunableReason: String? = null,
    /** The first entry returned by git is always the main working tree; it cannot be removed. */
    val isMain: Boolean = false,
    /** Committer time of [head], as a unix timestamp. */
    val lastCommitAt: Long? = null,
    /** Newest of the commit, the git index and any uncommitted edit — what the list is ordered by. */
    val lastActivityAt: Long? = null,
    /** [lastActivityAt] rendered as an age, e.g. "3 days ago". */
    val lastActivityLabel: String? = null,
    /** Why [lastActivityAt] is what it is, for the row's tooltip. */
    val activityReason: String? = null,
) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')

    val shortHead: String? get() = head?.take(7)

    val label: String
        get() = when {
            isBare -> "(bare)"
            branch != null -> branch.removePrefix("refs/heads/")
            isDetached -> "detached at ${shortHead.orEmpty()}"
            else -> name
        }
}

/**
 * Per-file change codes as reported by `git status --porcelain=v2`.
 * '.' means "unchanged in this half", matching git's own notation.
 */
data class ChangedFile(
    val path: String,
    val origPath: String? = null,
    val index: Char = '.',
    val worktree: Char = '.',
    val conflicted: Boolean = false,
    val untracked: Boolean = false,
    val ignored: Boolean = false,
) {
    val staged: Boolean get() = index != '.' && !conflicted && !untracked
    val unstaged: Boolean get() = worktree != '.' || untracked
    val name: String get() = path.substringAfterLast('/')
    val directory: String get() = path.substringBeforeLast('/', "")

    /** Single-letter badge for the file list, mirroring what the IDE shows in the gutter. */
    val badge: Char
        get() = when {
            conflicted -> '!'
            untracked -> '?'
            index != '.' -> index
            else -> worktree
        }
}

/** Which multi-step operation, if any, the working tree is currently in the middle of. */
enum class RepoOperation { NONE, MERGE, REBASE, CHERRY_PICK, REVERT, BISECT }

data class RepoStatus(
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val head: String? = null,
    val detached: Boolean = false,
    val files: List<ChangedFile> = emptyList(),
    val operation: RepoOperation = RepoOperation.NONE,
) {
    val staged: List<ChangedFile> get() = files.filter { it.staged }
    val unstaged: List<ChangedFile> get() = files.filter { it.unstaged && !it.conflicted }
    val conflicts: List<ChangedFile> get() = files.filter { it.conflicted }
    val isClean: Boolean get() = files.isEmpty()
    val hasUpstream: Boolean get() = upstream != null

    companion object {
        val EMPTY = RepoStatus()
    }
}

data class Branch(
    val name: String,
    val isRemote: Boolean,
    val isCurrent: Boolean = false,
    val upstream: String? = null,
    /** Path of the worktree that has this branch checked out, if any — git refuses to check it out twice. */
    val checkedOutIn: String? = null,
) {
    val shortName: String get() = if (isRemote) name.substringAfter('/') else name
}

data class CommitInfo(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val relativeDate: String,
    val refs: List<String> = emptyList(),
)

enum class DiffLineType { CONTEXT, ADD, DELETE, NO_NEWLINE }

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldNumber: Int? = null,
    val newNumber: Int? = null,
)

data class DiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

data class FileDiff(
    val path: String,
    val oldPath: String? = null,
    val hunks: List<DiffHunk> = emptyList(),
    val isBinary: Boolean = false,
    val isNew: Boolean = false,
    val isDeleted: Boolean = false,
    val isRename: Boolean = false,
    val mode: String? = null,
) {
    val added: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.ADD } }
    val removed: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.DELETE } }
    val isEmpty: Boolean get() = hunks.isEmpty() && !isBinary
}

/** One `<<<<<<< / ======= / >>>>>>>` region inside a conflicted file. */
data class ConflictRegion(
    val oursLabel: String,
    val theirsLabel: String,
    val ours: List<String>,
    val base: List<String>?,
    val theirs: List<String>,
)

sealed interface ConflictSegment {
    data class Text(val lines: List<String>) : ConflictSegment
    data class Conflict(val id: Int, val region: ConflictRegion, val resolution: Resolution = Resolution.UNRESOLVED) : ConflictSegment
}

enum class Resolution { UNRESOLVED, OURS, THEIRS, BOTH_OURS_FIRST, BOTH_THEIRS_FIRST }

data class ConflictedFile(
    val path: String,
    val segments: List<ConflictSegment>,
) {
    val regions: List<ConflictSegment.Conflict> get() = segments.filterIsInstance<ConflictSegment.Conflict>()
    val unresolvedCount: Int get() = regions.count { it.resolution == Resolution.UNRESOLVED }
    val isFullyResolved: Boolean get() = unresolvedCount == 0
}
