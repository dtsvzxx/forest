package io.mainactor.worktree

import io.mainactor.worktree.model.Task
import io.mainactor.worktree.platform.FileSystemAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The tasks each project has, in `~/.worktree/tasks.json`.
 *
 * Beside the machine's other preferences rather than inside the repository, for the same reason the
 * recent-projects list is: this is a person's own working list, and a working list is not something
 * to put in front of everyone who clones the repository — nor into the diffs and merge conflicts
 * that living in a worktree would cost it. A task that has become worth sharing belongs in an
 * issue.
 *
 * JSON and keyed by project path, exactly like `agents.json` — a prompt is many lines of arbitrary
 * text, which is precisely what a line-per-entry format cannot hold.
 */
class TaskStore(private val fs: FileSystemAccess) {

    private val dir get() = fs.resolve(fs.homeDir(), ".worktree")
    private val file get() = fs.resolve(dir, "tasks.json")

    /**
     * What this file was called when a task was still called a note.
     *
     * Read only when there is no `tasks.json` yet, and never written back: the entries are the same
     * shape, so the whole migration is reading the old name once. Leaving the old file where it is
     * costs a few kilobytes and means a downgrade still finds its data.
     */
    private val legacyFile get() = fs.resolve(dir, "notes.json")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): Map<String, List<Task>> = when {
        fs.exists(file) -> parse(file)
        fs.exists(legacyFile) -> parse(legacyFile)
        else -> emptyMap()
    }

    private fun parse(path: String): Map<String, List<Task>> = runCatching {
        json.parseToJsonElement(fs.readText(path)).jsonObject.mapValues { (_, value) ->
            value.jsonArray.map { entry ->
                val task = entry.jsonObject
                Task(
                    id = task["id"]?.jsonPrimitive?.content ?: "",
                    body = task["body"]?.jsonPrimitive?.content.orEmpty(),
                    // Absent in a migrated note, which is right: nothing written before there was
                    // a way to finish a task can have been finished.
                    done = task["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                    updatedAt = task["updatedAt"]?.jsonPrimitive?.long ?: 0,
                )
            }.filter { it.id.isNotEmpty() }
        }
    }.getOrDefault(emptyMap())

    /**
     * Writes the whole file.
     *
     * Every edit rewrites it, which for a few kilobytes of text is cheaper than working out what
     * changed — and it means a crash between two keystrokes loses one keystroke rather than the
     * file.
     */
    fun save(all: Map<String, List<Task>>) {
        runCatching {
            fs.createDirectories(dir)
            val root = buildJsonObject {
                all.forEach { (path, tasks) ->
                    if (tasks.isEmpty()) return@forEach
                    put(path, buildJsonArray {
                        tasks.forEach { task ->
                            add(buildJsonObject {
                                put("id", task.id)
                                put("body", task.body)
                                put("done", JsonPrimitive(task.done))
                                put("updatedAt", JsonPrimitive(task.updatedAt))
                            })
                        }
                    })
                }
            }
            fs.writeText(file, json.encodeToString(JsonObject.serializer(), root))
        }
    }
}
