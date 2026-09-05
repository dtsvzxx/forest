package io.mainactor.worktree

import io.mainactor.worktree.model.Task
import io.mainactor.worktree.platform.JvmFileSystemAccess
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two files, and what happens at the moment the second one appears.
 *
 * The tab was called Notes for one release, so `notes.json` has to keep being read — and the
 * interesting case is not the first start after the rename but every one after it, once some other
 * project has caused `tasks.json` to exist.
 */
class TaskStoreTest {

    private lateinit var home: File
    private val store get() = TaskStore(JvmFileSystemAccess(home = home.path))

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("worktree-tasks").toFile()
        File(home, ".worktree").mkdirs()
    }

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun write(name: String, json: String) = File(home, ".worktree/$name").writeText(json)

    @Test
    fun `a note written before the tracker existed loads as an unfinished task`() {
        write("notes.json", """{"/repo":[{"id":"note-1","body":"the old idea","updatedAt":7}]}""")

        val task = store.load().getValue("/repo").single()

        assertEquals("the old idea", task.body)
        assertTrue(!task.done, "nothing written before there was a way to finish a task can be done")
    }

    /**
     * The case the either-or version gets wrong.
     *
     * `tasks.json` appears the moment *any one* project is edited. Read instead of the old file
     * rather than behind it, every other project's entries go quiet at that moment — still on disk,
     * and nothing on screen.
     */
    @Test
    fun `a project left in the old file is still read once the new one exists`() {
        write("notes.json", """{"/old-repo":[{"id":"note-1","body":"the old idea","updatedAt":7}]}""")
        write("tasks.json", """{"/new-repo":[{"id":"task-1","body":"the new task","updatedAt":9}]}""")

        val loaded = store.load()

        assertEquals(listOf("the old idea"), loaded.getValue("/old-repo").map { it.body })
        assertEquals(listOf("the new task"), loaded.getValue("/new-repo").map { it.body })
    }

    /** Where both files know a project, the one being written to wins. */
    @Test
    fun `the new file wins for a project in both`() {
        write("notes.json", """{"/repo":[{"id":"note-1","body":"stale","updatedAt":7}]}""")
        write("tasks.json", """{"/repo":[{"id":"task-1","body":"current","done":true,"updatedAt":9}]}""")

        assertEquals(listOf("current"), store.load().getValue("/repo").map { it.body })
    }

    /**
     * Emptying a migrated project has to stay empty.
     *
     * This is the price of merging rather than replacing: with the project's entry gone from
     * `tasks.json` altogether, the old file would hand them straight back.
     */
    @Test
    fun `deleting every migrated task does not bring them back`() {
        write("notes.json", """{"/repo":[{"id":"note-1","body":"the old idea","updatedAt":7}]}""")

        store.save(store.load() + ("/repo" to emptyList()))

        assertEquals(emptyList(), store.load().getValue("/repo"))
    }

    /** The first write of any project folds every migrated one into the new file. */
    @Test
    fun `writing one project carries the others over`() {
        write("notes.json", """{"/old-repo":[{"id":"note-1","body":"the old idea","updatedAt":7}]}""")

        store.save(store.load() + ("/new-repo" to listOf(Task("task-1", "the new task"))))

        val written = File(home, ".worktree/tasks.json").readText()
        assertTrue("the old idea" in written, "the migrated project should be in the new file: $written")
        assertTrue(File(home, ".worktree/notes.json").exists(), "the old file is left where it is")
    }
}
