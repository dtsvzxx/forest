package io.mainactor.worktree

import io.mainactor.worktree.model.Note
import io.mainactor.worktree.platform.FileSystemAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The notes each project has, in `~/.worktree/notes.json`.
 *
 * Beside the machine's other preferences rather than inside the repository, for the same reason the
 * recent-projects list is: these are half-formed thoughts, and a half-formed thought is not
 * something to put in front of everyone who clones the repository. A note that has become worth
 * sharing is a note that belongs in an issue.
 *
 * JSON and keyed by project path, exactly like `agents.json` — a prompt is many lines of arbitrary
 * text, which is precisely what a line-per-entry format cannot hold.
 */
class NotesStore(private val fs: FileSystemAccess) {

    private val dir get() = fs.resolve(fs.homeDir(), ".worktree")
    private val file get() = fs.resolve(dir, "notes.json")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): Map<String, List<Note>> {
        if (!fs.exists(file)) return emptyMap()
        return runCatching {
            json.parseToJsonElement(fs.readText(file)).jsonObject.mapValues { (_, value) ->
                value.jsonArray.map { entry ->
                    val note = entry.jsonObject
                    Note(
                        id = note["id"]?.jsonPrimitive?.content ?: "",
                        body = note["body"]?.jsonPrimitive?.content.orEmpty(),
                        updatedAt = note["updatedAt"]?.jsonPrimitive?.long ?: 0,
                    )
                }.filter { it.id.isNotEmpty() }
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * Writes the whole file.
     *
     * Every edit rewrites it, which for a few kilobytes of text is cheaper than working out what
     * changed — and it means a crash between two keystrokes loses one keystroke rather than the
     * file.
     */
    fun save(all: Map<String, List<Note>>) {
        runCatching {
            fs.createDirectories(dir)
            val root = buildJsonObject {
                all.forEach { (path, notes) ->
                    if (notes.isEmpty()) return@forEach
                    put(path, buildJsonArray {
                        notes.forEach { note ->
                            add(buildJsonObject {
                                put("id", note.id)
                                put("body", note.body)
                                put("updatedAt", JsonPrimitive(note.updatedAt))
                            })
                        }
                    })
                }
            }
            fs.writeText(file, json.encodeToString(JsonObject.serializer(), root))
        }
    }
}
