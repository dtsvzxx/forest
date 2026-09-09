package io.mainactor.worktree

import io.mainactor.worktree.model.ChangedFile
import io.mainactor.worktree.model.RepoStatus
import io.mainactor.worktree.platform.FileSystemAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The last badge sweep of each project, in `~/.worktree/status.json`.
 *
 * **This exists because the sweep is bounded by the disk and cannot be made fast.** One
 * `git status` on a large worktree is ~110 ms warm, sixty-two of them are 5.3 s however many run
 * at once, and none of that changes by asking git differently: the cost is one `lstat` per tracked
 * file per worktree. So the list showed no state at all for five seconds after a project opened,
 * and then filled in. Publishing each badge as it landed took the *first* one down to 737 ms;
 * this takes it to nothing, by drawing what the sweep found last time while the new one runs.
 *
 * **A cached badge is a claim about the past, and that is the whole of the trade.** It is what the
 * worktree looked like when the project was last open — right almost always, because a checkout
 * nobody has touched has not changed, and wrong for as long as the sweep takes when somebody
 * edited it from a terminal in the meantime. The alternative on offer is not a correct badge, it
 * is five seconds of no badge, and a stale count that corrects itself inside a few seconds is the
 * better of the two. Nothing destructive reads these numbers — they size a dialog's warning and
 * paint a chip.
 *
 * What is stored is what a badge and the ordering need and nothing else: how far the branch has
 * drifted from its upstream, and the changed files. The files matter as much as the counts —
 * `AppState.withActivity` dates a worktree by the newest mtime among them, so without them the
 * list opens in commit-date order and re-sorts itself when the sweep lands, which is the same jump
 * this is meant to remove. The branch, the head and the in-progress operation are deliberately
 * absent: the row reads those off the `Worktree`, and a stale *branch* would be a different kind
 * of wrong.
 *
 * Beside the machine's other preferences rather than in the repository, like `tasks.json` and
 * `agents.json`, and for a stronger reason than either: this is a cache, and a cache in a worktree
 * is a merge conflict waiting to happen.
 */
class StatusStore(private val fs: FileSystemAccess) {

    private val dir get() = fs.resolve(fs.homeDir(), ".worktree")
    private val file get() = fs.resolve(dir, "status.json")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** What the sweep found last time in [project], keyed by worktree path. */
    fun load(project: String): Map<String, RepoStatus> = runCatching {
        if (!fs.exists(file)) return emptyMap()
        val projects = json.parseToJsonElement(fs.readText(file)).jsonObject
        val worktrees = projects[project]?.jsonObject ?: return emptyMap()
        worktrees.mapValues { (_, entry) -> entry.jsonObject.toStatus() }
    }.getOrDefault(emptyMap())

    /**
     * Replaces what is remembered about [project], leaving every other project alone.
     *
     * The whole file is rewritten because it is a few kilobytes and because working out what
     * changed costs more than writing it. A project is stored as the *whole* of its last sweep, so
     * a worktree that has been removed loses its entry rather than lingering.
     */
    fun save(project: String, statuses: Map<String, RepoStatus>) {
        runCatching {
            val existing = if (fs.exists(file)) {
                runCatching { json.parseToJsonElement(fs.readText(file)).jsonObject }.getOrNull()
            } else {
                null
            }

            fs.createDirectories(dir)
            val root = buildJsonObject {
                existing?.forEach { (path, value) -> if (path != project) put(path, value) }
                put(project, buildJsonObject {
                    statuses.forEach { (worktree, status) -> put(worktree, status.toJson()) }
                })
            }
            fs.writeText(file, json.encodeToString(JsonObject.serializer(), root))
        }
    }

    /** Forgets a project entirely, so removing one from the list does not leave its cache behind. */
    fun forget(project: String) {
        runCatching {
            if (!fs.exists(file)) return
            val existing = json.parseToJsonElement(fs.readText(file)).jsonObject
            if (project !in existing) return
            val root = buildJsonObject {
                existing.forEach { (path, value) -> if (path != project) put(path, value) }
            }
            fs.writeText(file, json.encodeToString(JsonObject.serializer(), root))
        }
    }

    private fun RepoStatus.toJson() = buildJsonObject {
        if (ahead != 0) put("ahead", JsonPrimitive(ahead))
        if (behind != 0) put("behind", JsonPrimitive(behind))
        put("files", buildJsonArray {
            // A ceiling rather than a budget: the summary status collapses an untracked directory
            // into one entry, so a real working tree is a handful of these and only something
            // pathological reaches the cap. It is here so a single strange repository cannot grow
            // the file without bound.
            files.take(MAX_FILES).forEach { file ->
                add(buildJsonObject {
                    put("path", file.path)
                    // Defaults are left out. It keeps the file small and, more usefully, keeps a
                    // line readable: what is written is what is true of that file.
                    if (file.index != '.') put("index", file.index.toString())
                    if (file.worktree != '.') put("worktree", file.worktree.toString())
                    if (file.conflicted) put("conflicted", JsonPrimitive(true))
                    if (file.untracked) put("untracked", JsonPrimitive(true))
                    if (file.ignored) put("ignored", JsonPrimitive(true))
                })
            }
        })
    }

    private fun JsonObject.toStatus() = RepoStatus(
        ahead = this["ahead"]?.jsonPrimitive?.int ?: 0,
        behind = this["behind"]?.jsonPrimitive?.int ?: 0,
        files = this["files"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val file = entry.jsonObject
            val path = file["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ChangedFile(
                path = path,
                index = file["index"]?.jsonPrimitive?.content?.firstOrNull() ?: '.',
                worktree = file["worktree"]?.jsonPrimitive?.content?.firstOrNull() ?: '.',
                conflicted = file["conflicted"]?.jsonPrimitive?.booleanOrNull ?: false,
                untracked = file["untracked"]?.jsonPrimitive?.booleanOrNull ?: false,
                ignored = file["ignored"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        },
    )

    private companion object {
        const val MAX_FILES = 500
    }
}
