package io.mainactor.worktree.git

import io.mainactor.worktree.model.RepoOperation
import io.mainactor.worktree.model.Resolution
import io.mainactor.worktree.platform.GitLocator
import io.mainactor.worktree.platform.JvmFileSystemAccess
import io.mainactor.worktree.platform.ProcessCommandRunner
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the git layer against a real repository in a temp directory.
 *
 * The parsers have their own unit tests; what this covers is the half that only shows up against
 * the real binary — argument order, exit codes, and the worktree/merge/rebase state machine.
 */
class GitIntegrationTest {

    private lateinit var root: File
    private lateinit var main: File
    private lateinit var git: Git

    private val gitAvailable: Boolean by lazy {
        runBlocking { Git(ProcessCommandRunner(), JvmFileSystemAccess(), GitLocator.locate()).version() != null }
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("worktree-test").toFile()
        main = File(root, "main").apply { mkdirs() }
        git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), GitLocator.locate())

        if (!gitAvailable) return
        runBlocking {
            git.run(main.path, "init", "--quiet", "--initial-branch=main", ".")
            git.run(main.path, "config", "user.email", "test@example.com")
            git.run(main.path, "config", "user.name", "Test")
            git.run(main.path, "config", "commit.gpgsign", "false")
            write("file.txt", "line1\nline2\nline3\n")
            git.stageAll(main.path)
            git.commit(main.path, "initial commit")
        }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(name: String, content: String, dir: File = main) {
        File(dir, name).writeText(content)
    }

    /**
     * The bug this pins: the Log tab's age column disagreed with the order it was sorting in.
     *
     * git walks the log by **committer** time, and the column was `%ar`, the *author* time. A
     * rebased or cherry-picked commit keeps its author date, so the top row of a real repository
     * read "6 weeks ago" over rows saying "7 days ago" — the list looked shuffled and was not.
     */
    @Test
    fun `the log's age is the one git sorts by`() {
        if (!gitAvailable) return
        runBlocking {
            // What a rebase leaves behind: written a year ago, landed on this branch just now.
            write("rebased.txt", "x\n")
            git.stageAll(main.path)
            val committed = ProcessCommandRunner().exec(
                workDir = main.path,
                command = listOf(
                    GitLocator.locate(), "commit", "-m", "rebased work",
                    "--date", Instant.now().minus(400, ChronoUnit.DAYS).toString(),
                ),
                env = mapOf("GIT_COMMITTER_DATE" to Instant.now().toString()),
            )
            assertTrue(committed.ok, committed.message)

            // The setup is only worth anything if the two clocks really disagree.
            val authored = git.run(main.path, "log", "-1", "--format=%ar").stdout.trim()
            assertTrue("year" in authored, "the author date is not a year back: $authored")

            val log = git.log(main.path)
            assertEquals("rebased work", log.first().subject, "git no longer walks by committer time")
            assertFalse(
                "year" in log.first().relativeDate,
                "the column is quoting the author clock again: ${log.first().relativeDate}",
            )
        }
    }

    @Test
    fun `lists the tracked files and finds where one of them changed`() {
        if (!gitAvailable) return
        runBlocking {
            File(main, "src").mkdirs()
            write("src/Main.kt", "fun main() {}\n")
            git.stageAll(main.path)
            git.commit(main.path, "add Main.kt")
            write("src/Main.kt", "fun main() {\n    println(\"hi\")\n}\n")
            git.stageAll(main.path)
            git.commit(main.path, "print something")

            assertEquals(listOf("file.txt", "src/Main.kt"), git.listFiles(main.path).sorted())

            val history = git.fileHistory(main.path, "src/Main.kt")

            // Newest first, and only the commits that touched this path — the initial commit and
            // anything that touched file.txt must not be in here.
            assertEquals(listOf("print something", "add Main.kt"), history.map { it.subject })

            val patch = assertNotNull(git.commitFileDiff(main.path, history.first().hash, "src/Main.kt"))
            assertEquals("src/Main.kt", patch.path)
            // The one-line body became three; the old line is the single deletion.
            assertEquals(3, patch.added)
            assertEquals(1, patch.removed)
        }
    }

    @Test
    fun `a file's history reaches back past a rename`() {
        if (!gitAvailable) return
        runBlocking {
            write("before.txt", "content\n")
            git.stageAll(main.path)
            git.commit(main.path, "add before.txt")
            git.run(main.path, "mv", "before.txt", "after.txt")
            git.stageAll(main.path)
            git.commit(main.path, "rename it")

            // Without --follow git stops at the rename and reports one commit, which on a
            // repository that reorganises packages hides most of a file's history.
            val history = git.fileHistory(main.path, "after.txt")

            assertEquals(listOf("rename it", "add before.txt"), history.map { it.subject })
            // The rename commit itself still has a patch for the new path...
            assertNotNull(git.commitFileDiff(main.path, history[0].hash, "after.txt"))
            // ...while the one before it does not, because the file was called something else.
            assertNull(git.commitFileDiff(main.path, history[1].hash, "after.txt"))
        }
    }

    @Test
    fun `a commit's patch covers merges and the very first commit`() {
        if (!gitAvailable) return
        runBlocking {
            // A merge commit is the case worth pinning: `git show` prints an empty diff for one
            // unless it is asked for the first-parent view, and a log of a shared repository is
            // mostly merges.
            git.run(main.path, "checkout", "--quiet", "-b", "side")
            write("added-on-side.txt", "side\n")
            git.stageAll(main.path)
            git.commit(main.path, "work on the side branch")
            git.run(main.path, "checkout", "--quiet", "main")
            write("file.txt", "line1\nchanged\nline3\n")
            git.stageAll(main.path)
            git.commit(main.path, "work on main")
            val merged = git.run(main.path, "merge", "--no-ff", "-m", "Merge branch 'side'", "side")
            assertTrue(merged.ok, merged.message)

            val log = git.log(main.path)
            val merge = log.first { it.subject.startsWith("Merge branch") }
            val first = log.last()

            val mergeFiles = git.commitDiff(main.path, merge.hash)
            assertEquals(
                listOf("added-on-side.txt"),
                mergeFiles.map { it.path },
                "a merge must show what it brought over its first parent",
            )
            assertTrue(mergeFiles.single().isNew)

            // The root commit has no parent to diff against; git renders it as an added tree.
            val rootFiles = git.commitDiff(main.path, first.hash)
            assertEquals(listOf("file.txt"), rootFiles.map { it.path })
            assertEquals(3, rootFiles.single().added, "the whole file is an addition")
        }
    }

    @Test
    fun `a commit's patch names every file it touched`() {
        if (!gitAvailable) return
        runBlocking {
            write("file.txt", "line1\nline2 edited\nline3\n")
            write("second.txt", "new file\n")
            git.stageAll(main.path)
            git.commit(main.path, "touch two files")

            val files = git.commitDiff(main.path, git.log(main.path).first().hash)

            assertEquals(listOf("file.txt", "second.txt"), files.map { it.path }.sorted())
            // The patch comes back with the file list, so the pane needs no second command.
            assertTrue(files.all { it.hunks.isNotEmpty() }, "each file should carry its own hunks")
        }
    }

    @Test
    fun `lists the main worktree and the ones we add`() {
        if (!gitAvailable) return
        runBlocking {
            val added = git.addWorktree(main.path, File(root, "feature").path, newBranch = "feature/x")
            assertTrue(added.ok, added.message)

            val worktrees = git.worktrees(main.path)

            assertEquals(2, worktrees.size)
            assertTrue(worktrees[0].isMain)
            assertEquals("main", worktrees[0].branch)
            assertEquals("feature/x", worktrees[1].branch)
            assertTrue(File(root, "feature/file.txt").exists())
        }
    }

    @Test
    fun `opening a linked worktree resolves back to the main one`() {
        if (!gitAvailable) return
        runBlocking {
            val linked = File(root, "feature")
            git.addWorktree(main.path, linked.path, newBranch = "feature/x")

            assertEquals(main.canonicalPath, File(git.mainWorktree(linked.path)!!).canonicalPath)
        }
    }

    @Test
    fun `removing a worktree needs force when it is dirty`() {
        if (!gitAvailable) return
        runBlocking {
            val linked = File(root, "feature")
            git.addWorktree(main.path, linked.path, newBranch = "feature/x")
            write("file.txt", "dirty\n", dir = linked)

            assertTrue(!git.removeWorktree(main.path, linked.path).ok)
            assertTrue(git.removeWorktree(main.path, linked.path, force = true).ok)
            assertEquals(1, git.worktrees(main.path).size)
        }
    }

    @Test
    fun `status reports staged, unstaged and untracked separately`() {
        if (!gitAvailable) return
        runBlocking {
            write("file.txt", "line1\nchanged\nline3\n")
            write("staged.txt", "new\n")
            write("untracked.txt", "nope\n")
            git.stage(main.path, listOf("staged.txt"))

            val status = git.status(main.path)

            assertEquals("main", status.branch)
            assertEquals(listOf("staged.txt"), status.staged.map { it.path })
            assertEquals(listOf("file.txt", "untracked.txt"), status.unstaged.map { it.path }.sorted())
            assertTrue(status.files.single { it.path == "untracked.txt" }.untracked)
            assertEquals(RepoOperation.NONE, git.currentOperation(main.path))
        }
    }

    @Test
    fun `diff of a tracked file carries the changed lines`() {
        if (!gitAvailable) return
        runBlocking {
            write("file.txt", "line1\nchanged\nline3\n")

            val diff = git.diff(main.path, staged = false, path = "file.txt").single()

            assertEquals("file.txt", diff.path)
            assertEquals(1, diff.added)
            assertEquals(1, diff.removed)
        }
    }

    @Test
    fun `diff of an untracked file shows every line as added`() {
        if (!gitAvailable) return
        runBlocking {
            write("brand-new.txt", "a\nb\n")

            val diff = git.diffUntracked(main.path, "brand-new.txt").single()

            assertTrue(diff.isNew)
            assertEquals(2, diff.added)
        }
    }

    @Test
    fun `commit then diff against the base ref shows the branch's own changes`() {
        if (!gitAvailable) return
        runBlocking {
            val linked = File(root, "feature")
            git.addWorktree(main.path, linked.path, newBranch = "feature/x")
            write("file.txt", "line1\nfeature\nline3\n", dir = linked)
            git.stageAll(linked.path)
            git.commit(linked.path, "feature change")

            val diffs = git.diffRange(linked.path, "main", "HEAD")

            assertEquals(listOf("file.txt"), diffs.map { it.path })
            assertEquals(1, diffs.single().added)
        }
    }

    @Test
    fun `a conflicting merge is reported, resolvable and stageable`() {
        if (!gitAvailable) return
        runBlocking {
            // Two branches touching the same line: the classic conflict.
            git.run(main.path, "branch", "other")
            write("file.txt", "line1\nMAIN\nline3\n")
            git.stageAll(main.path)
            git.commit(main.path, "main change")

            git.run(main.path, "checkout", "--quiet", "other")
            write("file.txt", "line1\nOTHER\nline3\n")
            git.stageAll(main.path)
            git.commit(main.path, "other change")

            val merge = git.merge(main.path, "main")
            assertTrue(!merge.ok, "the merge was expected to conflict")

            val status = git.status(main.path)
            assertEquals(RepoOperation.MERGE, git.currentOperation(main.path))
            assertEquals(listOf("file.txt"), status.conflicts.map { it.path })
            assertEquals(listOf("file.txt"), git.conflictedPaths(main.path))

            // Stages 2 and 3 hold the two sides.
            assertTrue(git.conflictStage(main.path, 2, "file.txt")!!.contains("OTHER"))
            assertTrue(git.conflictStage(main.path, 3, "file.txt")!!.contains("MAIN"))

            // Resolve through the same path the UI uses: parse markers, choose, write back, stage.
            val conflicted = GitParsers.parseConflicts("file.txt", File(main, "file.txt").readText())
            assertEquals(1, conflicted.regions.size)
            val resolved = conflicted.copy(
                segments = conflicted.segments.map {
                    if (it is io.mainactor.worktree.model.ConflictSegment.Conflict) {
                        it.copy(resolution = Resolution.THEIRS)
                    } else {
                        it
                    }
                },
            )
            File(main, "file.txt").writeText(GitParsers.renderResolved(resolved))
            assertTrue(git.markResolved(main.path, listOf("file.txt")).ok)

            assertTrue(git.status(main.path).conflicts.isEmpty())
            assertTrue(git.continueOperation(main.path, RepoOperation.MERGE).ok)
            assertEquals(RepoOperation.NONE, git.currentOperation(main.path))
            assertTrue(File(main, "file.txt").readText().contains("MAIN"))
        }
    }

    @Test
    fun `an in-progress rebase is detected before any ref markers appear`() {
        if (!gitAvailable) return
        runBlocking {
            git.run(main.path, "checkout", "--quiet", "-b", "topic")
            write("file.txt", "line1\nTOPIC\nline3\n")
            git.stageAll(main.path)
            git.commit(main.path, "topic change")
            git.run(main.path, "checkout", "--quiet", "main")
            write("file.txt", "line1\nMAIN\nline3\n")
            git.stageAll(main.path)
            git.commit(main.path, "main change")
            git.run(main.path, "checkout", "--quiet", "topic")

            git.rebase(main.path, "main")

            // `rebase-merge` exists for the whole rebase; REBASE_HEAD only once it stops.
            assertEquals(RepoOperation.REBASE, git.currentOperation(main.path))
            git.abortOperation(main.path, RepoOperation.REBASE)
        }
    }

    @Test
    fun `aborting a conflicted rebase restores the branch`() {
        if (!gitAvailable) return
        runBlocking {
            git.run(main.path, "checkout", "--quiet", "-b", "topic")
            write("file.txt", "line1\nTOPIC\nline3\n")
            git.stageAll(main.path)
            git.commit(main.path, "topic change")

            git.run(main.path, "checkout", "--quiet", "main")
            write("file.txt", "line1\nMAIN\nline3\n")
            git.stageAll(main.path)
            git.commit(main.path, "main change")

            git.run(main.path, "checkout", "--quiet", "topic")
            git.rebase(main.path, "main")

            assertEquals(RepoOperation.REBASE, git.currentOperation(main.path))

            assertTrue(git.abortOperation(main.path, RepoOperation.REBASE).ok)
            assertEquals(RepoOperation.NONE, git.currentOperation(main.path))
            assertEquals("topic", git.status(main.path).branch)
            assertTrue(File(main, "file.txt").readText().contains("TOPIC"))
        }
    }

    @Test
    fun `branches report which worktree has them checked out`() {
        if (!gitAvailable) return
        runBlocking {
            git.addWorktree(main.path, File(root, "feature").path, newBranch = "feature/x")

            val branches = git.branches(main.path).filterNot { it.isRemote }.associateBy { it.name }

            assertNotNull(branches["main"]?.checkedOutIn)
            assertEquals(
                File(root, "feature").canonicalPath,
                File(branches.getValue("feature/x").checkedOutIn!!).canonicalPath,
            )
            assertTrue(branches.getValue("main").isCurrent)
        }
    }

    @Test
    fun `log returns the commits we made`() {
        if (!gitAvailable) return
        runBlocking {
            write("file.txt", "second\n")
            git.stageAll(main.path)
            git.commit(main.path, "second commit")

            val log = git.log(main.path)

            assertEquals(listOf("second commit", "initial commit"), log.map { it.subject })
            assertTrue(log.first().refs.any { "main" in it })
        }
    }
}
