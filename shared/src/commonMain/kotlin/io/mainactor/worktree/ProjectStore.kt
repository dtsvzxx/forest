package io.mainactor.worktree

import io.mainactor.worktree.model.Project
import io.mainactor.worktree.platform.FileSystemAccess

/**
 * The app's only persisted state, in `~/.worktree/`:
 *
 * - `recent` — the project list, one path per line, in the order the user added them.
 * - `last` — the project that was open when the app was last used.
 *
 * The list is deliberately *not* reordered by use. A sidebar whose entries move under the pointer
 * every time you open something is harder to navigate than a fixed one, so which project you
 * opened last is tracked separately instead of being encoded in the ordering.
 */
class ProjectStore(private val fs: FileSystemAccess) {

    private val dir get() = fs.resolve(fs.homeDir(), ".worktree")
    private val listFile get() = fs.resolve(dir, "recent")
    private val lastFile get() = fs.resolve(dir, "last")

    fun load(): List<Project> {
        if (!fs.exists(listFile)) return emptyList()
        return runCatching { fs.readText(listFile) }
            .getOrDefault("")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filter { fs.isDirectory(it) }
            .map { Project(path = it, name = fs.nameOf(it)) }
            .toList()
    }

    fun save(projects: List<Project>) {
        runCatching {
            fs.createDirectories(dir)
            fs.writeText(listFile, projects.joinToString("\n") { it.path })
        }
    }

    /**
     * Adds [path] to the end of the list if it is new, and leaves the list untouched if it is
     * already there. Returns the list to show.
     */
    fun add(projects: List<Project>, path: String): List<Project> {
        if (projects.any { same(it.path, path) }) return projects
        val next = projects + Project(path = path, name = fs.nameOf(path))
        save(next)
        return next
    }

    fun remove(projects: List<Project>, path: String): List<Project> {
        val next = projects.filterNot { same(it.path, path) }
        save(next)
        if (lastOpened()?.let { same(it, path) } == true) runCatching { fs.writeText(lastFile, "") }
        return next
    }

    /**
     * Paths reaching this class come from git, from dialogs and from the list itself, and the same
     * repository can be spelled differently by each — compare them resolved.
     */
    private fun same(a: String, b: String) = a == b || fs.canonicalPath(a) == fs.canonicalPath(b)

    /** The project to reopen on startup, or null when there is nothing usable to restore. */
    fun lastOpened(): String? {
        if (!fs.exists(lastFile)) return null
        return runCatching { fs.readText(lastFile).trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && fs.isDirectory(it) }
    }

    fun setLastOpened(path: String) {
        runCatching {
            fs.createDirectories(dir)
            fs.writeText(lastFile, path)
        }
    }
}
