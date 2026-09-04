package io.mainactor.worktree.usage

import io.mainactor.worktree.platform.FileSystemAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One Codex rollout file — `~/.codex/sessions/YYYY/MM/DD/rollout-<time>-<uuid>.jsonl`.
 *
 * Codex records usage in a way that is the opposite of Claude Code's, and getting this backwards
 * is the whole risk here. Every `token_count` event carries `total_token_usage`, the **running
 * total for the session so far** — so the value is taken, not added. Summing looks tempting
 * because each event also carries a `last_token_usage` delta, but on this machine one session in
 * four had those deltas add up to more than the total the same file reports (1,479,976 against
 * 1,404,207): a retry counts in the delta and is rolled back out of the total. The total is what
 * the session was billed.
 *
 * A session's model can change mid-way; the cumulative total cannot be split when it does, so it
 * is attributed to the model in force at the end. Between two gpt-5.x models that moves the
 * estimate by a factor of two at worst, and only for a session where the model was switched.
 */
class CodexRollout(private val path: String) {

    private val tail = JsonlTail(path)

    /** The working directory the session was started in, from its first line. */
    var cwd: String? = null
        private set

    private var model: String? = null
    private var total: TokenUsage? = null
    private var turns = 0

    val usage: WorktreeUsage
        get() {
            val counts = total ?: return WorktreeUsage.NONE
            val key = ModelKey(model ?: UNKNOWN_MODEL, tool = AgentTool.CODEX)
            return WorktreeUsage(mapOf(key to counts), requests = turns, sessions = 1)
        }

    fun poll(fs: FileSystemAccess) = tail.poll(fs, onReset = { reset() }) { line -> consume(line) }

    private fun consume(line: String) {
        // Most of a rollout is `response_item` lines holding the conversation itself, and none of
        // them carry any of this. Skipping them by substring keeps the JSON parser off the bulk of
        // the file.
        if (!line.contains(TOKEN_COUNT) && !line.contains(SESSION_META) && !line.contains(TURN_CONTEXT)) return

        runCatching {
            val root = json.parseToJsonElement(line).jsonObject
            val payload = root["payload"]?.jsonObject ?: return
            when (root.string("type")) {
                SESSION_META -> cwd = payload.string("cwd") ?: cwd
                // The model lives on the turn, not the session: it is what a `/model` switch changes.
                TURN_CONTEXT -> model = payload.string("model") ?: model
                EVENT_MSG -> {
                    if (payload.string("type") != TOKEN_COUNT) return
                    val counts = payload["info"]?.jsonObject?.get("total_token_usage")?.jsonObject ?: return
                    total = counts.toUsage()
                    turns++
                }
            }
        }
    }

    /**
     * Maps Codex's counters onto the shared shape.
     *
     * `input_tokens` *includes* `cached_input_tokens` — verified by arithmetic on real files, where
     * `input + output == total` — so the uncached remainder is what gets billed at the full input
     * rate and the cached part at the discounted one. Charging both would roughly double the input
     * line. `reasoning_output_tokens` is likewise already inside `output_tokens`.
     */
    private fun JsonObject.toUsage(): TokenUsage {
        val input = long("input_tokens")
        val cached = long("cached_input_tokens")
        return TokenUsage(
            input = (input - cached).coerceAtLeast(0),
            output = long("output_tokens"),
            cacheRead = cached,
            cacheWrite5m = long("cache_write_input_tokens"),
        )
    }

    private fun reset() {
        model = null
        total = null
        turns = 0
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        const val SESSION_META = "session_meta"
        const val TURN_CONTEXT = "turn_context"
        const val EVENT_MSG = "event_msg"
        const val TOKEN_COUNT = "token_count"
        const val UNKNOWN_MODEL = "codex"

        fun JsonObject.string(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

        fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L
    }
}

/**
 * What Codex has spent in a worktree.
 *
 * Codex files its sessions by *date* rather than by working directory, so unlike Claude Code there
 * is no directory name to match — the working directory is inside the file, on its first line. That
 * line is read once per file and cached: a rollout's directory never changes.
 */
class CodexUsageSource(private val fs: FileSystemAccess) : UsageSource {

    override val tool = AgentTool.CODEX

    private val rollouts = HashMap<String, CodexRollout>()

    /** File to the directory it was started in. Cached because a rollout's directory never moves. */
    private val cwds = HashMap<String, String>()

    override fun read(worktreePath: String): WorktreeUsage {
        var total = WorktreeUsage.NONE
        ours(worktreePath).forEach { file ->
            val rollout = rollouts.getOrPut(file) { CodexRollout(file) }
            rollout.poll(fs)
            total = total.merge(rollout.usage)
        }
        return total
    }

    override fun hasSessions(worktreePath: String): Boolean = ours(worktreePath).isNotEmpty()

    override fun forgetAll() {
        rollouts.clear()
        cwds.clear()
    }

    /**
     * The rollouts belonging to [worktree].
     *
     * The directory is decided from the file's *first line* before anything else is read. Doing it
     * the other way round — poll, then look at the cwd the poll happened to load — meant reading
     * every rollout on the machine in full to find the handful that matter, which here is 733 MB
     * for a few megabytes of answer.
     *
     * Membership is worked out per call rather than remembered, because one reader serves every
     * worktree on the wall: a file that belongs to none of them today belongs to one of them the
     * moment that worktree gets a pane.
     */
    private fun ours(worktree: String): List<String> {
        val root = fs.resolve(fs.homeDir(), SESSIONS_DIR)
        if (!fs.isDirectory(root)) return emptyList()
        return rolloutFiles(root).filter { file ->
            val cwd = cwdOf(file)
            cwd == worktree || cwd?.startsWith("$worktree/") == true
        }
    }

    private fun cwdOf(file: String): String? {
        cwds[file]?.let { return it }
        val head = fs.readFrom(file, 0, META_PROBE_BYTES).decodeToString()
        val line = head.lineSequence().firstOrNull() ?: return null
        val cwd = runCatching {
            val root = json.parseToJsonElement(line).jsonObject
            if (root["type"]?.jsonPrimitive?.content != SESSION_META) return null
            root["payload"]?.jsonObject?.get("cwd")?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        cwds[file] = cwd
        return cwd
    }

    /** `sessions/<year>/<month>/<day>/rollout-*.jsonl`, the layout Codex writes. */
    private fun rolloutFiles(root: String): List<String> =
        fs.listDirectory(root).flatMap { year ->
            val yearDir = fs.resolve(root, year)
            fs.listDirectory(yearDir).flatMap { month ->
                val monthDir = fs.resolve(yearDir, month)
                fs.listDirectory(monthDir).flatMap { day ->
                    val dayDir = fs.resolve(monthDir, day)
                    fs.listDirectory(dayDir)
                        .filter { it.startsWith(ROLLOUT_PREFIX) && it.endsWith(".jsonl") }
                        .map { fs.resolve(dayDir, it) }
                }
            }
        }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private companion object {
        const val SESSIONS_DIR = ".codex/sessions"
        const val ROLLOUT_PREFIX = "rollout-"
        const val SESSION_META = "session_meta"

        /** Comfortably past the longest `session_meta` line on this machine, which is 27 KB. */
        const val META_PROBE_BYTES = 128 * 1024
    }
}
