package io.mainactor.worktree

import io.mainactor.worktree.git.Git
import io.mainactor.worktree.platform.DesktopSystemIntegration
import io.mainactor.worktree.platform.DirectoryChooser
import io.mainactor.worktree.platform.GitLocator
import io.mainactor.worktree.platform.JvmFileSystemAccess
import io.mainactor.worktree.platform.ProcessCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the cost of a refresh against the number of worktrees.
 *
 * Refreshing runs on every action, and a repository with dozens of worktrees is the whole point of
 * this app — so the number of child processes it spawns has to stay close to one per worktree.
 * Asserting on the command count rather than on wall-clock keeps this deterministic.
 */
class RefreshCostTest {

    private lateinit var root: File
    private lateinit var mainRepo: File
    private lateinit var homeDir: File

    private val gitPath = GitLocator.locate()
    private val gitAvailable: Boolean by lazy {
        runBlocking { Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath).version() != null }
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("worktree-refresh").toFile()
        mainRepo = File(root, "repo").apply { mkdirs() }
        homeDir = File(root, "home").apply { mkdirs() }
        if (!gitAvailable) return

        runBlocking {
            val git = Git(ProcessCommandRunner(), JvmFileSystemAccess(), gitPath)
            git.run(mainRepo.path, "init", "--quiet", "--initial-branch=main", ".")
            git.run(mainRepo.path, "config", "user.email", "test@example.com")
            git.run(mainRepo.path, "config", "user.name", "Test")
            git.run(mainRepo.path, "config", "commit.gpgsign", "false")
            File(mainRepo, "file.txt").writeText("hello\n")
            git.stageAll(mainRepo.path)
            git.commit(mainRepo.path, "initial commit")
            repeat(WORKTREES) { i ->
                git.addWorktree(mainRepo.path, File(root, "wt$i").path, newBranch = "wt$i")
            }
        }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun CoroutineScope.newState(): AppState {
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
        ).also { sink = it::recordGitLog }
    }

    @Test
    fun `the interactive part of a refresh does not grow with the number of worktrees`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        state.badgeRefresh?.join()

        val before = state.gitLog.size
        state.refresh().join()
        val foreground = state.gitLog.drop(before)
        state.badgeRefresh?.join()

        // What the user waits for: the worktree list, plus the selected worktree's own reads.
        assertTrue(
            foreground.size <= FOREGROUND_BUDGET,
            "a refresh blocked on ${foreground.size} git commands with ${state.worktrees.size} " +
                "worktrees (budget $FOREGROUND_BUDGET):\n" + foreground.joinToString("\n") { it.command },
        )
    }

    @Test
    fun `a refresh costs about one command per worktree`() = runBlocking {
        if (!gitAvailable) return@runBlocking
        val state = newState()
        state.openProject(mainRepo.path).join()
        // Let the sweep that opening kicked off finish, so it is not counted against the refresh.
        state.badgeRefresh?.join()
        assertEquals(WORKTREES + 1, state.worktrees.size)

        val before = state.gitLog.size
        state.refresh().join()
        // The badge sweep is deliberately off the interactive path; count it too.
        state.badgeRefresh?.join()
        val commands = state.gitLog.drop(before)

        // One `worktree list`, one status per worktree, plus a small fixed cost for the selected
        // one (its operation probes, branches, log, diff).
        val budget = state.worktrees.size + 16
        assertTrue(
            commands.size <= budget,
            "a refresh of ${state.worktrees.size} worktrees ran ${commands.size} git commands " +
                "(budget $budget):\n" + commands.groupingBy { it.command.substringBefore(" -") }
                    .eachCount().entries.sortedByDescending { it.value }.joinToString("\n"),
        )
    }

    private companion object {
        const val WORKTREES = 40

        /** worktree list + status/rev-parse/branches/log/diff for the one selected worktree. */
        const val FOREGROUND_BUDGET = 16
    }
}
