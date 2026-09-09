package io.mainactor.worktree

import io.mainactor.worktree.model.ChangedFile
import io.mainactor.worktree.model.RepoStatus
import io.mainactor.worktree.platform.JvmFileSystemAccess
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusStoreTest {

    private lateinit var home: File
    private lateinit var store: StatusStore

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("status-store").toFile()
        store = StatusStore(JvmFileSystemAccess(home = home.path))
    }

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun status(vararg files: ChangedFile, ahead: Int = 0, behind: Int = 0) =
        RepoStatus(ahead = ahead, behind = behind, files = files.toList())

    @Test
    fun `what a badge draws survives the round trip`() {
        val statuses = mapOf(
            "/repo/main" to status(
                ChangedFile("src/Main.kt", index = 'M'),
                ChangedFile("notes.txt", worktree = 'M'),
                ChangedFile("build/out", untracked = true, worktree = '?'),
                ChangedFile("Merge.kt", conflicted = true),
                ChangedFile("target/", ignored = true),
                ahead = 3,
                behind = 1,
            ),
        )

        store.save("/repo", statuses)
        val read = store.load("/repo").getValue("/repo/main")

        assertEquals(3, read.ahead)
        assertEquals(1, read.behind)
        // Exactly the three numbers the row paints: dirty, conflicted, and how far it has drifted.
        assertEquals(4, read.files.count { !it.ignored })
        assertEquals(1, read.conflicts.size)
        assertEquals(1, read.staged.size)
        // And the paths, because the list is ordered by the newest mtime among them.
        assertTrue("src/Main.kt" in read.files.map { it.path })
    }

    @Test
    fun `a clean worktree is remembered as clean, not as unknown`() {
        // The distinction matters: a missing entry means "never swept" and draws nothing, while an
        // empty one means "swept, and there was nothing" — which is most of a real repository.
        store.save("/repo", mapOf("/repo/main" to RepoStatus.EMPTY))

        val read = store.load("/repo")

        assertTrue("/repo/main" in read, "the entry went missing")
        assertTrue(read.getValue("/repo/main").isClean)
    }

    @Test
    fun `saving one project leaves the others alone`() {
        store.save("/a", mapOf("/a/main" to status(ChangedFile("one.txt", worktree = 'M'))))
        store.save("/b", mapOf("/b/main" to status(ChangedFile("two.txt", worktree = 'M'))))
        // And re-saving the first must not take the second with it.
        store.save("/a", mapOf("/a/main" to status(ahead = 9)))

        assertEquals(9, store.load("/a").getValue("/a/main").ahead)
        assertEquals(listOf("two.txt"), store.load("/b").getValue("/b/main").files.map { it.path })
    }

    @Test
    fun `a project is stored as the whole of its last sweep`() {
        store.save("/a", mapOf("/a/main" to status(ahead = 1), "/a/gone" to status(ahead = 2)))
        // The worktree was removed, so the next sweep does not mention it.
        store.save("/a", mapOf("/a/main" to status(ahead = 1)))

        assertEquals(setOf("/a/main"), store.load("/a").keys, "a removed worktree kept its badge")
    }

    @Test
    fun `forgetting a project takes its cache with it`() {
        store.save("/a", mapOf("/a/main" to status(ahead = 1)))
        store.save("/b", mapOf("/b/main" to status(ahead = 2)))

        store.forget("/a")

        assertTrue(store.load("/a").isEmpty())
        assertEquals(2, store.load("/b").getValue("/b/main").ahead)
    }

    @Test
    fun `nothing on disk, and nothing to say about it`() {
        assertTrue(store.load("/never/opened").isEmpty())
        // A file of nonsense is a cache, not a document: it is ignored rather than thrown over.
        File(home, ".worktree").mkdirs()
        File(home, ".worktree/status.json").writeText("{ this is not json")
        assertTrue(store.load("/repo").isEmpty())
    }
}
