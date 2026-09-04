package io.mainactor.worktree.git

import io.mainactor.worktree.model.Branch
import io.mainactor.worktree.model.CommitInfo
import io.mainactor.worktree.model.FileDiff
import io.mainactor.worktree.model.RepoOperation
import io.mainactor.worktree.model.RepoStatus
import io.mainactor.worktree.model.Worktree
import io.mainactor.worktree.platform.CommandResult
import io.mainactor.worktree.platform.CommandRunner
import io.mainactor.worktree.platform.FileSystemAccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** One executed git invocation, surfaced in the Console tab so nothing the app does is hidden. */
data class GitLogEntry(
    val seq: Long,
    val workDir: String,
    val command: String,
    val exitCode: Int,
    val output: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * The whole git layer, driving the `git` binary rather than a JVM reimplementation.
 *
 * That choice is deliberate: worktrees, rebase, merge strategies, hooks, credential helpers and
 * conflict handling are exactly the areas where a reimplementation diverges from what the user
 * gets on their own command line — and this app puts a real terminal next to the UI, so the two
 * had better agree.
 */
class Git(
    private val runner: CommandRunner,
    private val fs: FileSystemAccess,
    private val gitPath: String = "git",
    /** Windows spells the bit bucket differently, and `diff --no-index` needs it by name. */
    private val nullDevice: String = "/dev/null",
    private val onLog: (GitLogEntry) -> Unit = {},
) {
    private var seq = 0L

    private val baseEnv = mapOf(
        // Never block on an interactive credential prompt we have no terminal for.
        "GIT_TERMINAL_PROMPT" to "0",
        "GIT_PAGER" to "cat",
        "GIT_OPTIONAL_LOCKS" to "0",
    )

    private val configArgs = listOf(
        "-c", "color.ui=false",
        "-c", "core.pager=cat",
        "-c", "advice.detachedHead=false",
    )

    suspend fun run(workDir: String?, vararg args: String): CommandResult = run(workDir, args.toList())

    suspend fun run(workDir: String?, args: List<String>, stdin: String? = null): CommandResult {
        val command = listOf(gitPath) + configArgs + args
        val result = runner.exec(workDir, command, stdin, baseEnv)
        onLog(
            GitLogEntry(
                seq = ++seq,
                workDir = workDir.orEmpty(),
                command = (listOf("git") + args).joinToString(" ") { if (' ' in it) "\"$it\"" else it },
                exitCode = result.exitCode,
                output = buildString {
                    append(result.stdout)
                    if (result.stderr.isNotBlank()) {
                        if (isNotEmpty() && !endsWith("\n")) append('\n')
                        append(result.stderr)
                    }
                }.trimEnd(),
            )
        )
        return result
    }

    // ---------------------------------------------------------------- discovery

    suspend fun version(): String? = run(null, "--version").takeIf { it.ok }?.stdout?.trim()

    /** Absolute path of the top of the working tree containing [dir], or null when it isn't a repo. */
    suspend fun topLevel(dir: String): String? =
        run(dir, "rev-parse", "--show-toplevel").takeIf { it.ok }?.stdout?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The main working tree of the repository containing [dir]. Opening a linked worktree should
     * still open the whole project, so this normalises whatever the user picked.
     */
    suspend fun mainWorktree(dir: String): String? =
        worktrees(dir).firstOrNull()?.path ?: topLevel(dir)

    suspend fun worktrees(dir: String): List<Worktree> {
        val r = run(dir, "worktree", "list", "--porcelain")
        return if (r.ok) GitParsers.parseWorktreeList(r.stdout) else emptyList()
    }

    /**
     * Working-tree status. [detailed] asks git to list every untracked file and to look for
     * renames; the summary form is enough for a list badge and is markedly cheaper on a big
     * repository, because git can then collapse an untracked directory into a single entry
     * instead of walking it.
     *
     * The in-progress operation is *not* probed here — see [currentOperation].
     */
    suspend fun status(dir: String, detailed: Boolean = true): RepoStatus {
        val args = mutableListOf("status", "--porcelain=v2", "--branch")
        args += if (detailed) "--untracked-files=all" else "--untracked-files=normal"
        args += if (detailed) "--renames" else "--no-renames"
        args += "-z"
        val r = run(dir, args)
        return if (r.ok) GitParsers.parseStatus(r.stdout) else RepoStatus.EMPTY
    }

    /**
     * Whether a merge/rebase/cherry-pick is stopped in this worktree.
     *
     * Reads the marker files under the worktree's git directory, the same way git decides itself.
     * The obvious alternative — one `rev-parse --verify` per candidate ref — costs five child
     * processes per call, which is ruinous when the caller has dozens of worktrees to check.
     * It is also more accurate: `rebase-merge`/`rebase-apply` exist for the whole rebase, while
     * `REBASE_HEAD` appears only once it has stopped on a conflict.
     */
    suspend fun currentOperation(dir: String): RepoOperation {
        val gitDir = run(dir, "rev-parse", "--absolute-git-dir")
            .takeIf { it.ok }?.stdout?.trim()?.takeIf { it.isNotEmpty() }
            ?: return RepoOperation.NONE

        fun has(name: String) = fs.exists(fs.resolve(gitDir, name))

        return when {
            has("rebase-merge") || has("rebase-apply") -> RepoOperation.REBASE
            has("MERGE_HEAD") -> RepoOperation.MERGE
            has("CHERRY_PICK_HEAD") -> RepoOperation.CHERRY_PICK
            has("REVERT_HEAD") -> RepoOperation.REVERT
            has("BISECT_LOG") -> RepoOperation.BISECT
            else -> RepoOperation.NONE
        }
    }

    suspend fun branches(dir: String): List<Branch> = coroutineScope {
        val sep = GitParsers.FS
        val format = "%(refname:short)$sep%(upstream:short)$sep%(HEAD)$sep%(worktreepath)$sep"
        val local = async { run(dir, "for-each-ref", "--format=${format}local", "refs/heads") }
        val remote = async { run(dir, "for-each-ref", "--format=${format}remote", "refs/remotes") }
        GitParsers.parseBranches(local.await().stdout) + GitParsers.parseBranches(remote.await().stdout)
    }

    /**
     * Committer time of each of [commits], as `sha -> unix seconds`.
     *
     * `--no-walk` turns `git log` into a lookup of exactly the commits named, so dating the whole
     * worktree list costs one child process rather than one per worktree. A single unresolvable
     * sha aborts the command, so callers pass only ones git can resolve.
     */
    suspend fun commitTimes(dir: String, commits: Collection<String>): Map<String, Long> {
        if (commits.isEmpty()) return emptyMap()
        val sep = GitParsers.FS
        val r = run(dir, listOf("log", "--no-walk", "--format=%H$sep%ct") + commits.distinct())
        if (!r.ok) return emptyMap()
        return r.stdout.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val f = line.split(sep)
                val sha = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val at = f.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                sha to at
            }
            .toMap()
    }

    suspend fun log(dir: String, limit: Int = 200, ref: String? = null): List<CommitInfo> {
        val fs = GitParsers.FS
        val args = mutableListOf(
            "log", "--max-count=$limit",
            "--format=%H$fs%h$fs%s$fs%an$fs%ar$fs%D",
        )
        if (ref != null) args += ref
        val r = run(dir, args)
        return if (r.ok) GitParsers.parseLog(r.stdout) else emptyList()
    }

    // ---------------------------------------------------------------- worktrees

    /**
     * `git worktree add`. Exactly one of [newBranch] / [existingBranch] should be set; with neither,
     * git detaches at [baseRef].
     */
    suspend fun addWorktree(
        dir: String,
        path: String,
        newBranch: String? = null,
        existingBranch: String? = null,
        baseRef: String? = null,
        force: Boolean = false,
        detach: Boolean = false,
    ): CommandResult {
        val args = mutableListOf("worktree", "add")
        if (force) args += "--force"
        when {
            newBranch != null -> { args += "-b"; args += newBranch }
            detach -> args += "--detach"
        }
        args += path
        when {
            existingBranch != null -> args += existingBranch
            baseRef != null -> args += baseRef
        }
        return run(dir, args)
    }

    suspend fun removeWorktree(dir: String, path: String, force: Boolean = false): CommandResult {
        val args = mutableListOf("worktree", "remove")
        if (force) args += "--force"
        args += path
        return run(dir, args)
    }

    suspend fun lockWorktree(dir: String, path: String, reason: String?): CommandResult {
        val args = mutableListOf("worktree", "lock")
        if (!reason.isNullOrBlank()) { args += "--reason"; args += reason }
        args += path
        return run(dir, args)
    }

    suspend fun unlockWorktree(dir: String, path: String): CommandResult =
        run(dir, "worktree", "unlock", path)

    suspend fun pruneWorktrees(dir: String): CommandResult = run(dir, "worktree", "prune", "-v")

    // ---------------------------------------------------------------- diff

    suspend fun diff(dir: String, staged: Boolean, path: String? = null, contextLines: Int = 3): List<FileDiff> {
        val args = mutableListOf("diff", "--no-color", "--no-ext-diff", "--find-renames", "-U$contextLines")
        if (staged) args += "--cached"
        if (path != null) { args += "--"; args += path }
        val r = run(dir, args)
        return if (r.ok) GitParsers.parseDiff(r.stdout) else emptyList()
    }

    /** Diff for an untracked file — git has nothing to compare against, so ask it to pretend. */
    suspend fun diffUntracked(dir: String, path: String): List<FileDiff> {
        val r = run(dir, "diff", "--no-color", "--no-index", "--", nullDevice, path)
        // `--no-index` exits 1 whenever the files differ, which is the normal case here.
        return GitParsers.parseDiff(r.stdout).map { it.copy(path = path, isNew = true) }
    }

    /** Diff between two refs, used to show what a worktree's branch carries over its base. */
    suspend fun diffRange(dir: String, from: String, to: String, contextLines: Int = 3): List<FileDiff> {
        val r = run(dir, "diff", "--no-color", "--no-ext-diff", "-U$contextLines", "$from...$to")
        return if (r.ok) GitParsers.parseDiff(r.stdout) else emptyList()
    }

    // ---------------------------------------------------------------- index & commit

    suspend fun stage(dir: String, paths: List<String>): CommandResult =
        run(dir, listOf("add", "--") + paths)

    suspend fun stageAll(dir: String): CommandResult = run(dir, "add", "--all")

    suspend fun unstage(dir: String, paths: List<String>): CommandResult =
        run(dir, listOf("restore", "--staged", "--") + paths)

    suspend fun discard(dir: String, paths: List<String>): CommandResult =
        run(dir, listOf("restore", "--worktree", "--") + paths)

    suspend fun deleteUntracked(dir: String, paths: List<String>): CommandResult =
        run(dir, listOf("clean", "-f", "--") + paths)

    suspend fun commit(dir: String, message: String, amend: Boolean = false, stageAll: Boolean = false): CommandResult {
        val args = mutableListOf("commit", "--file=-")
        if (amend) args += "--amend"
        if (stageAll) args += "--all"
        return run(dir, args, stdin = message)
    }

    // ---------------------------------------------------------------- remote

    suspend fun fetch(dir: String, prune: Boolean = true, all: Boolean = true): CommandResult {
        val args = mutableListOf("fetch")
        if (all) args += "--all"
        if (prune) args += "--prune"
        args += "--progress"
        return run(dir, args)
    }

    suspend fun pull(dir: String, rebase: Boolean): CommandResult =
        run(dir, "pull", if (rebase) "--rebase" else "--no-rebase", "--progress")

    /**
     * Pushes the current branch. When it has no upstream yet, [setUpstream] publishes it to
     * [remote] under the same name — the equivalent of `git push -u origin <branch>`.
     */
    suspend fun push(
        dir: String,
        setUpstream: Boolean = false,
        force: Boolean = false,
        remote: String = "origin",
        branch: String? = null,
    ): CommandResult {
        val args = mutableListOf("push", "--progress")
        if (force) args += "--force-with-lease"
        if (setUpstream && branch != null) {
            args += "--set-upstream"
            args += remote
            args += branch
        }
        return run(dir, args)
    }

    // ---------------------------------------------------------------- branches

    /**
     * Switches this worktree to [ref].
     *
     * git refuses a branch that another worktree already has checked out; the caller filters
     * those out of the picker, and the error is surfaced verbatim if one slips through.
     */
    suspend fun checkout(dir: String, ref: String): CommandResult = run(dir, "checkout", ref)

    suspend fun checkoutNewBranch(dir: String, name: String, startPoint: String?): CommandResult {
        val args = mutableListOf("checkout", "-b", name)
        if (!startPoint.isNullOrBlank()) args += startPoint
        return run(dir, args)
    }

    /** Creates a local branch following [remoteRef], the usual intent behind picking `origin/x`. */
    suspend fun checkoutTracking(dir: String, localName: String, remoteRef: String): CommandResult =
        run(dir, "checkout", "-b", localName, "--track", remoteRef)

    // ---------------------------------------------------------------- integrate

    suspend fun merge(dir: String, ref: String, noFastForward: Boolean = false, squash: Boolean = false): CommandResult {
        val args = mutableListOf("merge")
        if (noFastForward) args += "--no-ff"
        if (squash) args += "--squash"
        args += ref
        return run(dir, args)
    }

    suspend fun rebase(dir: String, onto: String, interactive: Boolean = false): CommandResult {
        val args = mutableListOf("rebase")
        if (interactive) args += "--interactive"
        args += onto
        return run(dir, args)
    }

    suspend fun continueOperation(dir: String, operation: RepoOperation): CommandResult = when (operation) {
        RepoOperation.REBASE -> run(dir, listOf("rebase", "--continue"), stdin = "")
        RepoOperation.MERGE -> run(dir, listOf("merge", "--continue"), stdin = "")
        RepoOperation.CHERRY_PICK -> run(dir, listOf("cherry-pick", "--continue"), stdin = "")
        RepoOperation.REVERT -> run(dir, listOf("revert", "--continue"), stdin = "")
        else -> CommandResult(0, "", "")
    }

    suspend fun abortOperation(dir: String, operation: RepoOperation): CommandResult = when (operation) {
        RepoOperation.REBASE -> run(dir, "rebase", "--abort")
        RepoOperation.MERGE -> run(dir, "merge", "--abort")
        RepoOperation.CHERRY_PICK -> run(dir, "cherry-pick", "--abort")
        RepoOperation.REVERT -> run(dir, "revert", "--abort")
        RepoOperation.BISECT -> run(dir, "bisect", "reset")
        RepoOperation.NONE -> CommandResult(0, "", "")
    }

    suspend fun skipOperation(dir: String, operation: RepoOperation): CommandResult = when (operation) {
        RepoOperation.REBASE -> run(dir, "rebase", "--skip")
        RepoOperation.CHERRY_PICK -> run(dir, "cherry-pick", "--skip")
        else -> CommandResult(0, "", "")
    }

    // ---------------------------------------------------------------- conflicts

    suspend fun conflictedPaths(dir: String): List<String> =
        run(dir, "diff", "--name-only", "--diff-filter=U", "-z")
            .stdout.split(GitParsers.NUL).filter { it.isNotBlank() }

    /** Stage 1/2/3 of a conflicted path: base, ours, theirs. */
    suspend fun conflictStage(dir: String, stage: Int, path: String): String? =
        run(dir, "show", ":$stage:$path").takeIf { it.ok }?.stdout

    suspend fun takeOurs(dir: String, path: String): CommandResult =
        run(dir, "checkout", "--ours", "--", path)

    suspend fun takeTheirs(dir: String, path: String): CommandResult =
        run(dir, "checkout", "--theirs", "--", path)

    suspend fun markResolved(dir: String, paths: List<String>): CommandResult =
        run(dir, listOf("add", "--") + paths)

    // ---------------------------------------------------------------- creation

    suspend fun init(dir: String, bare: Boolean = false): CommandResult {
        val args = mutableListOf("init")
        if (bare) args += "--bare"
        args += dir
        return run(null, args)
    }

    suspend fun clone(url: String, targetPath: String, bare: Boolean = false): CommandResult {
        val args = mutableListOf("clone", "--progress")
        if (bare) args += "--bare"
        args += url
        args += targetPath
        return run(null, args)
    }
}
