package io.mainactor.worktree

import io.mainactor.worktree.git.Git
import io.mainactor.worktree.model.Branch
import io.mainactor.worktree.model.Project
import io.mainactor.worktree.model.SplitAxis
import io.mainactor.worktree.model.ConflictSegment
import io.mainactor.worktree.model.RepoOperation
import io.mainactor.worktree.model.Resolution
import io.mainactor.worktree.platform.DesktopSystemIntegration
import io.mainactor.worktree.platform.DirectoryChooser
import io.mainactor.worktree.platform.GitLocator
import io.mainactor.worktree.platform.JvmFileSystemAccess
import io.mainactor.worktree.platform.ProcessCommandRunner
import kotlinx.coroutines.CoroutineScope
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
 * Drives [AppState] exactly the way the buttons do, against a real repository.
 *
 * The value here is the wiring: that "create worktree" reaches git with the right arguments, that
 * the panes are refreshed afterwards, and that a conflict flows from merge through the resolution
 * UI's own state transitions back to a clean tree.
 */
class AppStateIntegrationTest {

    private lateinit var root: File
    private lateinit var mainRepo: File
    private lateinit var homeDir: File

    private val gitPath = GitLocator.locate()
    private val gitAvailable: Boolean by lazy {
        runBlocking { Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath).version() != null }
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("worktree-appstate").toFile()
        mainRepo = File(root, "repo").apply { mkdirs() }
        homeDir = File(root, "home").apply { mkdirs() }

        if (!gitAvailable) return
        runBlocking {
            val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
            git.run(mainRepo.path, "init", "--quiet", "--initial-branch=main", ".")
            git.run(mainRepo.path, "config", "user.email", "test@example.com")
            git.run(mainRepo.path, "config", "user.name", "Test")
            git.run(mainRepo.path, "config", "commit.gpgsign", "false")
            File(mainRepo, "file.txt").writeText("line1\nline2\nline3\n")
            git.stageAll(mainRepo.path)
            git.commit(mainRepo.path, "initial commit")
        }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    /** A second repository, for the tests that care about how several projects interact. */
    private fun newRepository(name: String): File = File(root, name).apply { mkdirs() }.also { dir ->
        runBlocking {
            val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
            git.run(dir.path, "init", "--quiet", "--initial-branch=main", ".")
            git.run(dir.path, "config", "user.email", "test@example.com")
            git.run(dir.path, "config", "user.name", "Test")
            git.run(dir.path, "config", "commit.gpgsign", "false")
            File(dir, "file.txt").writeText("hello\n")
            git.stageAll(dir.path)
            git.commit(dir.path, "initial commit")
        }
    }

    /** An [AppState] whose "home" is inside the temp tree, so the recent list never escapes it. */
    private fun CoroutineScope.newState(onTerminalDisposed: (String) -> Unit = {}): AppState {
        val fs = JvmFileSystemAccess(home = homeDir.path)
        var sink: ((io.mainactor.worktree.git.GitLogEntry) -> Unit)? = null
        return AppState(
            git = Git(ProcessCommandRunner(), fs, gitPath, onLog = { sink?.invoke(it) }),
            fs = fs,
            store = ProjectStore(fs),
            chooser = object : DirectoryChooser {
                override suspend fun chooseDirectory(title: String, startIn: String?): String? = null
            },
            system = DesktopSystemIntegration(),
            scope = this,
            onTerminalDisposed = onTerminalDisposed,
        ).also { sink = it::recordGitLog }
    }

    @Test
    fun `opening a project lists its worktrees and remembers it`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()

        state.openProject(mainRepo.path).join()

        assertEquals("repo", state.project?.name)
        assertEquals(1, state.worktrees.size)
        assertTrue(state.worktrees.single().isMain)
        assertEquals("main", state.selectedWorktree?.branch)
        assertTrue(File(homeDir, ".worktree/recent").readText().contains(mainRepo.canonicalPath))
    }

    @Test
    fun `searching finds a file by name without a git command per keystroke`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
        File(mainRepo, "src").mkdirs()
        File(mainRepo, "src/Greeting.kt").writeText("fun greet() {}\n")
        File(mainRepo, "src/Unrelated.kt").writeText("fun other() {}\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "add sources")
        val state = newState()
        state.openProject(mainRepo.path).join()
        val before = state.gitLog.size

        // Typing, one character at a time, the way the field delivers it.
        "Greet".fold("") { typed, c -> (typed + c).also { state.search(it) } }
        state.searchIndexJob?.join()
        state.search("Greet")

        assertEquals(listOf("src/Greeting.kt"), state.searchResults)
        assertEquals(
            1,
            state.gitLog.drop(before).count { it.command.startsWith("git ls-files") },
            "the file index must be read once, not once per keystroke",
        )
    }

    @Test
    fun `a search result opens the commits that touched that file`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
        File(mainRepo, "notes.md").writeText("first\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "add notes")
        File(mainRepo, "notes.md").writeText("first\nsecond\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "extend notes")
        val state = newState()
        state.openProject(mainRepo.path).join()
        state.search("notes")
        state.searchIndexJob?.join()

        state.selectSearchFile("notes.md").join()

        // Newest first, and nothing that only touched file.txt.
        assertEquals(listOf("extend notes", "add notes"), state.fileCommits.map { it.subject })
        // The newest commit is opened for the user, with that file's patch and no other's.
        assertEquals("extend notes", state.fileCommit?.subject)
        assertEquals("notes.md", state.fileDiff?.path)
        assertEquals(1, state.fileDiff?.added)

        state.selectFileCommit(state.fileCommits.last()).join()
        assertEquals("notes.md", state.fileDiff?.path)
    }

    @Test
    fun `moving to another worktree forgets the file index and the history`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        state.createWorktree(
            path = File(root, "feature").path,
            newBranch = "feature/search",
            existingBranch = null,
            baseRef = null,
            force = false,
        ).join()
        state.search("file")
        state.searchIndexJob?.join()
        state.selectSearchFile("file.txt").join()
        assertTrue(state.fileCommits.isNotEmpty())

        state.selectWorktree(state.worktrees.first { !it.isMain }).join()

        // The index belongs to one worktree; a branch with different files must not be searched
        // through the previous one's list.
        assertEquals("", state.searchQuery)
        assertTrue(state.searchResults.isEmpty())
        assertNull(state.searchFile)
        assertTrue(state.fileCommits.isEmpty())
        assertNull(state.fileDiff)
    }

    @Test
    fun `selecting a commit loads the files it changed, and only when asked`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
        File(mainRepo, "file.txt").writeText("line1\nedited\nline3\n")
        File(mainRepo, "extra.txt").writeText("new\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "edit one file and add another")
        val state = newState()

        state.openProject(mainRepo.path).join()

        // Opening a project must not cost a `git show`: the Log tab is opened to scan subjects far
        // more often than to read a patch.
        assertNull(state.selectedCommit, "no commit should be loaded until one is clicked")
        assertTrue(state.commitFiles.isEmpty())

        state.selectCommit(state.commits.first()).join()

        assertEquals(listOf("extra.txt", "file.txt"), state.commitFiles.map { it.path }.sorted())
        assertFalse(state.commitDiffLoading)
        // The first file is selected for the user, and arrives with its patch already parsed.
        assertNotNull(state.commitFile)
        assertTrue(state.commitFile!!.hunks.isNotEmpty())
    }

    @Test
    fun `moving to another worktree drops the commit the log was showing`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        state.createWorktree(
            path = File(root, "feature").path,
            newBranch = "feature/x",
            existingBranch = null,
            baseRef = null,
            force = false,
        ).join()
        state.selectCommit(state.commits.first()).join()
        assertNotNull(state.selectedCommit)

        state.selectWorktree(state.worktrees.first { !it.isMain }).join()

        // The commit belonged to the worktree we left; leaving its patch on screen under another
        // worktree's history is how the pane starts lying.
        assertNull(state.selectedCommit)
        assertTrue(state.commitFiles.isEmpty())
        assertNull(state.commitFile)
    }

    @Test
    fun `opening a project never reorders the list`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val second = newRepository("other")
        val state = newState()

        state.openProject(mainRepo.path).join()
        state.openProject(second.path).join()
        val order = state.projects.map { it.name }

        // Re-opening the first one must leave it where it is.
        state.openProject(mainRepo.path).join()

        assertEquals(listOf("repo", "other"), order)
        assertEquals(order, state.projects.map { it.name })
    }

    @Test
    fun `startup restores the last opened project, not the first in the list`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val second = newRepository("other")
        with(newState()) {
            openProject(mainRepo.path).join()
            openProject(second.path).join()
        }

        val restarted = newState()
        restarted.start()?.join()

        assertEquals(listOf("repo", "other"), restarted.projects.map { it.name })
        assertEquals("other", restarted.project?.name)
    }

    @Test
    fun `forgetting the restored project falls back to the first one`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val second = newRepository("other")
        with(newState()) {
            openProject(mainRepo.path).join()
            openProject(second.path).join()
            forgetProject(second.path)
        }

        val restarted = newState()
        restarted.start()?.join()

        assertEquals("repo", restarted.project?.name)
    }

    @Test
    fun `opening a folder that is not a repository reports an error`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        val plain = File(root, "not-a-repo").apply { mkdirs() }

        state.openProject(plain.path).join()

        assertNull(state.project)
        assertTrue(state.notice?.isError == true)
    }

    @Test
    fun `creating a worktree adds it and selects it`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()

        val path = File(root, "wt-login").path
        state.createWorktree(
            path = path,
            newBranch = "feature/login",
            existingBranch = null,
            baseRef = "main",
            force = false,
        ).join()

        assertEquals(2, state.worktrees.size)
        assertEquals("feature/login", state.selectedWorktree?.branch)
        assertEquals(File(path).canonicalPath, File(state.selectedWorktree!!.path).canonicalPath)
        assertTrue(File(path, "file.txt").exists())
    }

    @Test
    fun `worktrees are ordered by their last commit, most recent first`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)

        // Three worktrees whose HEADs are a day apart, created oldest-first so that creation order
        // and commit order disagree. The dates must be *committer* dates — that is what `%ct`
        // reports and therefore what the ordering uses — and GIT_COMMITTER_DATE wants a strict
        // timestamp, not an approxidate.
        val runner = ProcessCommandRunner()
        listOf("oldest" to 3L, "middle" to 2L, "newest" to 1L).forEach { (name, daysAgo) ->
            val path = File(root, name).path
            state.createWorktree(path, name, null, "main", force = false).join()
            File(path, "$name.txt").writeText("x\n")
            git.stageAll(path)
            val result = runner.exec(
                workDir = path,
                command = listOf(gitPath, "commit", "-m", "commit for $name"),
                env = mapOf(
                    "GIT_COMMITTER_DATE" to Instant.now().minus(daysAgo, ChronoUnit.DAYS).toString(),
                ),
            )
            assertTrue(result.ok, "commit for $name failed: ${result.message}")
        }
        state.refresh().join()
        // The edit signal only exists once the sweep has re-read every worktree's status; until
        // then the ordering is provisional.
        state.badgeRefresh?.join()

        // The main worktree keeps the initial commit, made moments ago by setUp.
        assertEquals(listOf("main", "newest", "middle", "oldest"), state.worktrees.map { it.label })
        assertTrue(state.worktrees.all { it.lastActivityAt != null })
        assertTrue(state.worktrees.all { !it.lastActivityLabel.isNullOrBlank() })
        val times = state.worktrees.map { it.lastActivityAt!! }
        assertEquals(times.sortedDescending(), times)
    }

    @Test
    fun `the main worktree stays on top however stale it is`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
        val runner = ProcessCommandRunner()

        // Backdate the repository's own commit, then give a linked worktree fresh work.
        runner.exec(
            workDir = mainRepo.path,
            command = listOf(gitPath, "commit", "--amend", "--no-edit", "--date", "1 year ago"),
            env = mapOf("GIT_COMMITTER_DATE" to Instant.now().minus(365, ChronoUnit.DAYS).toString()),
        )
        val busy = File(root, "busy").path
        state.refresh().join()
        state.createWorktree(busy, "busy", null, "HEAD", force = false).join()
        File(busy, "now.txt").writeText("fresh\n")
        git.stageAll(busy)
        git.commit(busy, "brand new work")
        state.refresh().join()
        state.badgeRefresh?.join()

        val order = state.worktrees.map { it.label }
        assertEquals(listOf("main", "busy"), order)
        // Not because it is newer — it plainly is not.
        val main = state.worktrees.first()
        val other = state.worktrees.last()
        assertTrue(
            main.lastActivityAt!! < other.lastActivityAt!!,
            "the fixture should leave main older than the linked worktree",
        )
    }

    @Test
    fun `an uncommitted edit lifts a worktree above newer commits`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)

        val stalePath = File(root, "stale").path
        state.createWorktree(stalePath, "stale", null, "main", force = false).join()
        File(stalePath, "old.txt").writeText("x\n")
        git.stageAll(stalePath)
        ProcessCommandRunner().exec(
            workDir = stalePath,
            command = listOf(gitPath, "commit", "-m", "an old commit"),
            env = mapOf("GIT_COMMITTER_DATE" to Instant.now().minus(30, ChronoUnit.DAYS).toString()),
        )
        state.refresh().join()
        state.badgeRefresh?.join()

        // Everything about git says this worktree is a month stale, and it sorts last.
        assertEquals("stale", state.worktrees.last().label)
        assertTrue(state.worktrees.first().isMain, "the main worktree should be pinned to the top")

        // Now edit a file in it and commit nothing. Only the filesystem knows.
        // Timestamps have one-second resolution, so put the edit in a later second than the
        // initial commit on main — otherwise the two tie and the name breaks it.
        Thread.sleep(1_100)
        File(stalePath, "old.txt").writeText("edited right now\n")
        state.refresh().join()
        state.badgeRefresh?.join()

        val stale = state.worktrees.first { it.label == "stale" }
        assertEquals(
            "stale",
            state.worktrees.first { !it.isMain }.label,
            "an edited worktree should lead the linked worktrees",
        )
        assertTrue(
            stale.activityReason!!.contains("uncommitted edit"),
            "the age should be attributed to the edit, was: ${stale.activityReason}",
        )
        // The commit itself is still correctly reported as a month old.
        assertTrue(stale.lastActivityAt!! > stale.lastCommitAt!!)
    }

    @Test
    fun `switching a worktree to another branch checks it out`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
        git.run(mainRepo.path, "branch", "spare")

        val path = File(root, "wt").path
        state.createWorktree(path, "wt-branch", null, "main", force = false).join()
        val worktree = state.worktrees.single { it.branch == "wt-branch" }

        state.switchBranch(worktree, Branch(name = "spare", isRemote = false)).join()

        assertEquals("spare", state.worktrees.single { it.path == worktree.path }.branch)
        assertEquals("spare", state.status.branch)
    }

    @Test
    fun `switching to a branch held by another worktree is refused, with git's reason`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()

        val path = File(root, "wt").path
        state.createWorktree(path, "held", null, "main", force = false).join()
        val worktree = state.worktrees.single { it.branch == "held" }

        // "main" is checked out in the main worktree; git will not allow a second checkout.
        state.switchBranch(worktree, Branch(name = "main", isRemote = false)).join()

        assertEquals("held", state.worktrees.single { it.path == worktree.path }.branch)
        assertTrue(state.notice?.isError == true)
        assertTrue(
            state.notice!!.text.contains("already", ignoreCase = true) ||
                state.notice!!.text.contains("worktree", ignoreCase = true),
            "expected git's own explanation, got: ${state.notice?.text}",
        )
    }

    @Test
    fun `creating a branch from the switch dialog checks it out here`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()

        val path = File(root, "wt").path
        state.createWorktree(path, "base", null, "main", force = false).join()
        val worktree = state.worktrees.single { it.branch == "base" }

        state.switchToNewBranch(worktree, "feature/fresh", "main").join()

        assertEquals("feature/fresh", state.worktrees.single { it.path == worktree.path }.branch)
        // The branch it came from is untouched and still available.
        assertTrue(state.branches.any { it.name == "base" })
    }

    @Test
    fun `picking a remote branch creates a local branch that tracks it`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)

        // A second repository standing in for the remote.
        val remote = newRepository("remote-origin")
        git.run(remote.path, "branch", "shipped")
        git.run(mainRepo.path, "remote", "add", "origin", remote.path)
        git.fetch(mainRepo.path)
        state.openProject(mainRepo.path).join()

        val path = File(root, "wt").path
        state.createWorktree(path, "local-only", null, "main", force = false).join()
        val worktree = state.worktrees.single { it.branch == "local-only" }
        val remoteBranch = state.branches.single { it.isRemote && it.shortName == "shipped" }

        state.switchBranch(worktree, remoteBranch).join()

        val after = state.worktrees.single { it.path == worktree.path }
        assertEquals("shipped", after.branch, "expected a local branch, not a detached HEAD")
        assertEquals("origin/shipped", state.status.upstream)
    }

    @Test
    fun `removing a worktree drops it and its terminal tab`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val disposed = mutableListOf<String>()
        val state = newState(onTerminalDisposed = { disposed += it })
        state.openProject(mainRepo.path).join()
        val path = File(root, "wt-tmp").path
        state.createWorktree(path, "tmp", null, "main", force = false).join()
        val worktree = state.worktrees.single { it.branch == "tmp" }
        state.openTerminal(worktree)

        state.removeWorktree(worktree, force = false).join()

        assertEquals(1, state.worktrees.size)
        assertFalse(File(path).exists())
        assertTrue(disposed.isNotEmpty(), "the worktree's terminal should have been torn down")
    }

    @Test
    fun `staging, unstaging and committing move files through the index`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        File(mainRepo, "file.txt").writeText("line1\nedited\nline3\n")
        File(mainRepo, "brand-new.txt").writeText("hello\n")
        state.refresh().join()

        assertEquals(2, state.status.unstaged.size)
        assertTrue(state.status.staged.isEmpty())

        state.stage(state.status.unstaged).join()
        assertEquals(2, state.status.staged.size)

        state.unstage(state.status.staged.filter { it.path == "brand-new.txt" }).join()
        assertEquals(listOf("file.txt"), state.status.staged.map { it.path })

        state.commit("edit the file", amend = false, stageAll = false).join()
        assertTrue(state.status.staged.isEmpty())
        assertEquals("edit the file", state.commits.first().subject)
    }

    @Test
    fun `discarding restores tracked files and deletes untracked ones`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        File(mainRepo, "file.txt").writeText("ruined\n")
        File(mainRepo, "junk.txt").writeText("junk\n")
        state.refresh().join()

        state.discard(state.status.unstaged).join()

        assertTrue(state.status.isClean, "expected a clean tree, got ${state.status.files}")
        assertEquals("line1\nline2\nline3\n", File(mainRepo, "file.txt").readText())
        assertFalse(File(mainRepo, "junk.txt").exists())
    }

    @Test
    fun `selecting a file loads its diff`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        File(mainRepo, "file.txt").writeText("line1\nedited\nline3\n")
        state.refresh().join()

        state.selectFile(state.status.files.single { it.path == "file.txt" }).join()

        val diff = assertNotNull(state.diff)
        assertEquals("file.txt", diff.path)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
    }

    @Test
    fun `comparing a worktree against its base shows the branch's commits`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val path = File(root, "wt-api").path
        state.createWorktree(path, "feature/api", null, "main", force = false).join()

        File(path, "api.txt").writeText("new api\n")
        state.stageAll().join()
        state.commit("add the api", amend = false, stageAll = false).join()

        state.setBaseRef("main").join()
        state.setDiffMode(DiffMode.AGAINST_BASE).join()

        assertEquals(listOf("api.txt"), state.rangeDiffs.map { it.path })
        assertTrue(state.rangeDiffs.single().isNew)
    }

    @Test
    fun `a conflicting merge routes to the conflicts tab and resolves cleanly`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()

        // Build the conflict in a linked worktree, which is the shape this app is about.
        val path = File(root, "wt-topic").path
        state.createWorktree(path, "topic", null, "main", force = false).join()
        File(path, "file.txt").writeText("line1\nTOPIC\nline3\n")
        state.stageAll().join()
        state.commit("topic change", amend = false, stageAll = false).join()

        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
        File(mainRepo, "file.txt").writeText("line1\nMAIN\nline3\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "main change")

        state.merge("main", noFastForward = false).join()

        assertEquals(RepoOperation.MERGE, state.status.operation)
        assertEquals(listOf("file.txt"), state.status.conflicts.map { it.path })
        assertEquals(RightTab.CONFLICTS, state.rightTab)

        state.openConflict("file.txt").join()
        val conflict = assertNotNull(state.conflictFile)
        assertEquals(1, conflict.regions.size)
        assertFalse(conflict.isFullyResolved)

        state.resolveRegion(conflict.regions.single().id, Resolution.OURS)
        assertTrue(state.conflictFile!!.isFullyResolved)

        state.applyConflictResolution().join()

        assertTrue(state.status.conflicts.isEmpty())
        assertEquals("line1\nTOPIC\nline3\n", File(path, "file.txt").readText())

        state.continueOperation().join()
        assertEquals(RepoOperation.NONE, state.status.operation)
        assertEquals(RightTab.CHANGES, state.rightTab)
    }

    @Test
    fun `taking one whole side of a conflicted file resolves it`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)

        git.run(mainRepo.path, "checkout", "--quiet", "-b", "side")
        File(mainRepo, "file.txt").writeText("line1\nSIDE\nline3\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "side change")
        git.run(mainRepo.path, "checkout", "--quiet", "main")
        File(mainRepo, "file.txt").writeText("line1\nMAIN\nline3\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "main change")
        state.refresh().join()

        state.merge("side", noFastForward = false).join()
        assertEquals(1, state.status.conflicts.size)

        state.takeWholeSide("file.txt", ours = true).join()

        assertTrue(state.status.conflicts.isEmpty())
        assertTrue(File(mainRepo, "file.txt").readText().contains("MAIN"))
    }

    @Test
    fun `aborting a rebase puts the worktree back`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)

        val path = File(root, "wt-rebase").path
        state.createWorktree(path, "rebase-me", null, "main", force = false).join()
        File(path, "file.txt").writeText("line1\nREBASE\nline3\n")
        state.stageAll().join()
        state.commit("rebase change", amend = false, stageAll = false).join()

        File(mainRepo, "file.txt").writeText("line1\nMAIN\nline3\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "main change")

        state.rebase("main").join()
        assertEquals(RepoOperation.REBASE, state.status.operation)

        state.abortOperation().join()

        assertEquals(RepoOperation.NONE, state.status.operation)
        assertEquals("rebase-me", state.status.branch)
        assertEquals("line1\nREBASE\nline3\n", File(path, "file.txt").readText())
    }

    @Test
    fun `nothing starts a shell on its own`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()

        state.start()?.join()

        // Opening a repository must not spawn a terminal, and neither must entering the wall.
        assertTrue(state.terminals.isEmpty(), "opening a project started a terminal")
        assertFalse(state.terminalVisible)

        state.switchTo(AppMode.AGENTS)
        assertTrue(state.agents.isEmpty(), "entering the agent wall started an agent")
    }

    @Test
    fun `an agent can take you to its worktree in the project view`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val path = File(root, "side").path
        state.createWorktree(path, "side", null, "main", force = false).join()
        val side = state.worktrees.single { it.branch == "side" }
        state.startAgentFor(side)
        // Look somewhere else first, so the assertion cannot pass by accident.
        state.selectWorktree(state.worktrees.single { it.isMain }).join()

        state.showWorktreeInProject(state.focusedAgentSession!!).join()

        assertEquals(AppMode.PROJECT, state.mode)
        assertEquals("side", state.selectedWorktree?.branch)
    }

    @Test
    fun `an agent from another repository opens that repository on the way`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        val other = newRepository("other-app")
        state.openProject(mainRepo.path).join()

        var otherWorktrees: List<io.mainactor.worktree.model.Worktree> = emptyList()
        state.worktreesOf(Project(path = other.path, name = other.name)) { otherWorktrees = it }.join()
        state.switchTo(AppMode.AGENTS)
        state.openAgent(otherWorktrees.first(), projectPath = other.path)

        state.showWorktreeInProject(state.focusedAgentSession!!).join()

        assertEquals(AppMode.PROJECT, state.mode)
        assertEquals(other.canonicalPath, File(state.project!!.path).canonicalPath)
        assertEquals(
            other.canonicalPath,
            File(state.selectedWorktree!!.path).canonicalPath,
            "the pane's own worktree should be the one selected",
        )
        // The agents keep running while the project view moves.
        assertEquals(1, state.agents.size)
    }

    @Test
    fun `the worktree menu starts an agent and then takes you back to it`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val path = File(root, "side").path
        state.createWorktree(path, "side", null, "main", force = false).join()
        val side = state.worktrees.single { it.branch == "side" }
        val main = state.worktrees.single { it.isMain }

        assertEquals(0, state.agentCountFor(side))

        state.startAgentFor(side)

        assertEquals(AppMode.AGENTS, state.mode, "starting an agent should show the wall")
        assertEquals(1, state.agentCountFor(side))
        assertEquals(side.path, state.focusedAgentSession?.workDir)

        // Work elsewhere, then come back to the worktree's agent from its menu.
        state.startAgentFor(main)
        assertEquals(main.path, state.focusedAgentSession?.workDir)

        state.focusAgentFor(side)
        assertEquals(side.path, state.focusedAgentSession?.workDir)
    }

    @Test
    fun `focusing a worktree's agent un-zooms whatever was filling the wall`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val path = File(root, "side").path
        state.createWorktree(path, "side", null, "main", force = false).join()
        val side = state.worktrees.single { it.branch == "side" }
        val main = state.worktrees.single { it.isMain }

        state.startAgentFor(side)
        state.startAgentFor(main)
        state.toggleAgentZoom(state.focusedAgentSession!!.id)
        assertEquals(main.path, state.agents.first { it.id == state.zoomedAgent }.workDir)

        state.focusAgentFor(side)

        // Otherwise the wall would still show the zoomed pane and the focus move would be invisible.
        assertEquals(null, state.zoomedAgent)
        assertEquals(side.path, state.focusedAgentSession?.workDir)
    }

    @Test
    fun `focusing a worktree with no agent does nothing`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val main = state.worktrees.single()

        state.focusAgentFor(main)

        assertEquals(AppMode.PROJECT, state.mode)
        assertTrue(state.agents.isEmpty())
    }

    @Test
    fun `many agents can share one worktree`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val worktree = state.worktrees.single()

        state.switchTo(AppMode.AGENTS)
        // Entering the mode starts nothing on its own.
        assertTrue(state.agents.isEmpty())

        repeat(3) { state.openAgent(worktree) }

        assertEquals(3, state.agents.size)
        assertTrue(state.agents.all { it.workDir == worktree.path })
        assertEquals(listOf("main · 1", "main · 2", "main · 3"), state.agents.map { it.title })
        assertEquals(state.agents.last().id, state.focusedAgent)
    }

    @Test
    fun `splitting asks where the new pane runs instead of inheriting`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        state.switchTo(AppMode.AGENTS)
        state.openAgent()
        val before = state.agents.size

        state.requestNewAgent(SplitAxis.ROW)

        // Nothing is created yet: the window turns the request into a picker.
        assertEquals(before, state.agents.size)
        assertEquals(SplitAxis.ROW, state.agentRequest?.axis)

        state.clearAgentRequest()
        assertEquals(null, state.agentRequest)
    }

    @Test
    fun `the picker orders worktrees exactly like the worktrees pane`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
        val runner = ProcessCommandRunner()

        // Three worktrees with staggered commit dates, plus an uncommitted edit in the oldest —
        // enough that raw `git worktree list` order and the pane's order disagree.
        listOf("older" to 20L, "oldest" to 40L, "newer" to 5L).forEach { (name, daysAgo) ->
            val path = File(root, name).path
            state.createWorktree(path, name, null, "main", force = false).join()
            File(path, "$name.txt").writeText("x\n")
            git.stageAll(path)
            runner.exec(
                workDir = path,
                command = listOf(gitPath, "commit", "-m", name),
                env = mapOf(
                    "GIT_COMMITTER_DATE" to Instant.now().minus(daysAgo, ChronoUnit.DAYS).toString(),
                ),
            )
        }
        File(root, "oldest/oldest.txt").writeText("edited just now\n")
        state.refresh().join()
        state.badgeRefresh?.join()

        var picked: List<io.mainactor.worktree.model.Worktree> = emptyList()
        state.worktreesOf(Project(path = mainRepo.path, name = "repo")) { picked = it }.join()

        assertEquals(state.worktrees.map { it.label }, picked.map { it.label })
        assertTrue(picked.first().isMain, "the main worktree stays pinned in the picker too")
        // The edited one outranks worktrees with far newer commits, exactly as in the pane.
        assertEquals("oldest", picked[1].label)
        assertTrue(picked.all { !it.lastActivityLabel.isNullOrBlank() })
    }

    @Test
    fun `agents from different repositories share one wall`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        val other = newRepository("other-app")
        state.openProject(mainRepo.path).join()
        state.switchTo(AppMode.AGENTS)
        state.openAgent()

        // The picker can reach any remembered project, so a pane may live outside the open one.
        var otherWorktrees: List<io.mainactor.worktree.model.Worktree> = emptyList()
        state.worktreesOf(Project(path = other.path, name = other.name)) { otherWorktrees = it }.join()
        assertTrue(otherWorktrees.isNotEmpty(), "the other repository should report a worktree")

        state.openAgent(otherWorktrees.first(), SplitAxis.ROW, projectPath = other.path)

        assertEquals(2, state.agents.size)
        val guest = state.agents.last()
        assertEquals(other.canonicalPath, File(guest.workDir).canonicalPath)
        assertEquals(other.path, guest.projectPath)
        // The pane on the open project is untouched.
        assertEquals(mainRepo.canonicalPath, File(state.agents.first().workDir).canonicalPath)
    }

    @Test
    fun `the picker starts from the pane a split would divide`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val path = File(root, "wt").path
        state.createWorktree(path, "side", null, "main", force = false).join()
        val side = state.worktrees.single { it.branch == "side" }

        state.switchTo(AppMode.AGENTS)
        state.openAgent(side)

        assertEquals(side.path, state.focusedAgentSession?.workDir)
    }

    @Test
    fun `closing an agent tears down its shell and moves the focus`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val disposed = mutableListOf<String>()
        val state = newState(onTerminalDisposed = { disposed += it })
        state.openProject(mainRepo.path).join()
        state.switchTo(AppMode.AGENTS)
        state.openAgent()
        state.openAgent()
        val doomed = state.agents.last()

        state.closeAgent(doomed.id)

        assertEquals(1, state.agents.size)
        assertTrue(doomed.id in disposed, "the agent's shell should have been terminated")
        assertEquals(state.agents.single().id, state.focusedAgent)
    }

    @Test
    fun `zooming a pane is a toggle and survives new agents`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        state.switchTo(AppMode.AGENTS)
        state.openAgent()
        val first = state.agents.single()

        state.toggleAgentZoom(first.id)
        assertEquals(first.id, state.zoomedAgent)

        state.toggleAgentZoom(first.id)
        assertEquals(null, state.zoomedAgent)

        // Opening another pane has to leave the wall visible, not stuck zoomed on the old one.
        state.toggleAgentZoom(first.id)
        state.openAgent()
        assertEquals(null, state.zoomedAgent)
    }

    @Test
    fun `removing a worktree closes the agents running in it`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val disposed = mutableListOf<String>()
        val state = newState(onTerminalDisposed = { disposed += it })
        state.openProject(mainRepo.path).join()
        // Enter the mode first, so its automatic first agent lands on the main worktree and this
        // test can also show that agents elsewhere are left alone.
        state.switchTo(AppMode.AGENTS)
        state.openAgent()
        val onMain = state.agents.single()

        val path = File(root, "wt").path
        state.createWorktree(path, "agents-here", null, "main", force = false).join()
        val worktree = state.worktrees.single { it.branch == "agents-here" }
        state.openAgent(worktree)
        state.openAgent(worktree)
        val here = state.agents.filter { it.workDir == worktree.path }
        assertEquals(2, here.size)

        state.removeWorktree(worktree, force = true).join()

        assertTrue(state.agents.none { it.workDir == worktree.path })
        assertTrue(here.all { it.id in disposed }, "agents in a removed worktree must be stopped")
        assertEquals(listOf(onMain.id), state.agents.map { it.id })
        assertTrue(onMain.id !in disposed, "agents elsewhere must keep running")
    }

    @Test
    fun `switching back to project mode leaves the agents running`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val disposed = mutableListOf<String>()
        val state = newState(onTerminalDisposed = { disposed += it })
        state.openProject(mainRepo.path).join()
        state.switchTo(AppMode.AGENTS)
        state.openAgent()
        state.openAgent()

        state.switchTo(AppMode.PROJECT)

        assertEquals(AppMode.PROJECT, state.mode)
        assertEquals(2, state.agents.size)
        assertTrue(disposed.isEmpty(), "leaving the wall must not kill the agents on it")
    }

    @Test
    fun `every git command reaches the console`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()

        state.openProject(mainRepo.path).join()

        assertTrue(state.gitLog.isNotEmpty())
        assertTrue(state.gitLog.any { it.command.startsWith("git worktree list") })
        assertTrue(state.gitLog.any { it.command.startsWith("git status") })
        assertTrue(state.gitLog.all { it.workDir.isNotEmpty() || it.command.contains("--version") })
    }

    @Test
    fun `resolving every region at once marks the file resolved`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)

        File(mainRepo, "multi.txt").writeText("a\nb\nc\nd\ne\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "add multi")

        git.run(mainRepo.path, "checkout", "--quiet", "-b", "other")
        File(mainRepo, "multi.txt").writeText("A\nb\nc\nd\nE\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "other edits")
        git.run(mainRepo.path, "checkout", "--quiet", "main")
        File(mainRepo, "multi.txt").writeText("1\nb\nc\nd\n5\n")
        git.stageAll(mainRepo.path)
        git.commit(mainRepo.path, "main edits")
        state.refresh().join()

        state.merge("other", noFastForward = false).join()
        state.openConflict("multi.txt").join()

        val conflict = assertNotNull(state.conflictFile)
        assertEquals(2, conflict.regions.size)

        state.resolveAll(Resolution.THEIRS)
        assertTrue(state.conflictFile!!.segments.filterIsInstance<ConflictSegment.Conflict>().all {
            it.resolution == Resolution.THEIRS
        })

        state.applyConflictResolution().join()

        assertTrue(state.status.conflicts.isEmpty())
        assertEquals("A\nb\nc\nd\nE\n", File(mainRepo, "multi.txt").readText())
    }
}
