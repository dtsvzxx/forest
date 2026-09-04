package io.mainactor.worktree.usage

import io.mainactor.worktree.platform.FileSystemAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One transcript file, read forward only.
 *
 * Transcripts are append-only with a stable inode, so the whole file is read once and every later
 * poll costs only the bytes that were added. That matters: the median file is ~170 KB but the
 * largest on this machine is 59 MB, and re-reading that every few seconds is not a thing to do
 * behind a UI.
 */
class TranscriptTail(private val path: String) {

    /** Byte offset just past the last *complete* line consumed. */
    var offset: Long = 0
        private set

    /**
     * Request ids already counted.
     *
     * Needed across polls, not just within one: a response's lines can straddle the moment we stop
     * reading, and the second half would otherwise be counted as a second response.
     */
    private val counted = HashSet<String>()

    private val totals = HashMap<ModelKey, TokenUsage>()

    val usage: WorktreeUsage get() = WorktreeUsage(totals.toMap(), counted.size)

    /** Reads whatever has been appended since the last call. */
    fun poll(fs: FileSystemAccess) {
        // Shorter than where we stopped means a different file wearing the same name.
        if (fs.fileSize(path) < offset) reset()

        while (true) {
            val chunk = fs.readFrom(path, offset, CHUNK_BYTES)
            if (chunk.isEmpty()) return

            val lastNewline = chunk.lastIndexOf(NEWLINE)
            if (lastNewline < 0) {
                // No line ends inside this chunk. Either the file's tail is still being written —
                // leave the offset alone and pick it up next poll — or one line is longer than a
                // whole chunk, in which case skipping it is the only option; a line that big is an
                // inlined attachment, and its usage arrives again on the other lines of the same
                // response anyway.
                if (chunk.size < CHUNK_BYTES) return
                offset += chunk.size
                continue
            }

            // A newline is always a character boundary, so decoding up to one never splits UTF-8.
            chunk.decodeToString(0, lastNewline + 1)
                .lineSequence()
                .forEach { line -> record(UsageParser.parse(line)) }
            offset += lastNewline + 1

            if (chunk.size < CHUNK_BYTES) return
        }
    }

    private fun record(record: UsageRecord?) {
        if (record == null || !counted.add(record.requestId)) return
        totals[record.key] = (totals[record.key] ?: TokenUsage.NONE) + record.usage
    }

    private fun reset() {
        offset = 0
        counted.clear()
        totals.clear()
    }

    private companion object {
        /** Big enough that a poll is one read for any ordinary transcript. */
        const val CHUNK_BYTES = 4 * 1024 * 1024
        const val NEWLINE = '\n'.code.toByte()
    }
}

/**
 * Reads what Claude Code has spent inside a worktree.
 *
 * The source is the transcript Claude Code writes for itself under
 * `~/.claude/projects/<encoded cwd>/<sessionId>.jsonl`. Reading that rather than watching the
 * terminal is what makes this both accurate and free of side effects: it is the usage the API
 * actually reported, and looking at it cannot disturb a running agent. It also means *every*
 * session in the worktree is counted, including ones started from an ordinary terminal rather than
 * from a pane in this app.
 */
class UsageReader(private val fs: FileSystemAccess) {

    private val tails = HashMap<String, TranscriptTail>()

    /** Directory name to the working directory it was made from; see [cwdOf]. */
    private val cwds = HashMap<String, String>()

    /** Everything spent in [worktreePath], as of now. */
    fun read(worktreePath: String): WorktreeUsage {
        val root = fs.resolve(fs.homeDir(), PROJECTS_DIR)
        if (!fs.isDirectory(root)) return WorktreeUsage.NONE

        val worktree = fs.canonicalPath(worktreePath)
        val prefix = encodeProjectDir(worktree)
        val dirs = fs.listDirectory(root).filter { name -> claims(root, name, prefix, worktree) }

        var total = WorktreeUsage.NONE
        dirs.forEach { dir ->
            transcriptsIn(fs.resolve(root, dir)).forEach { file ->
                total = merge(total, tails.getOrPut(file) { TranscriptTail(file) }.also { it.poll(fs) }.usage)
            }
        }
        return total
    }

    /** Forgets every file, so a stale worktree's tails are not kept alive for the session. */
    fun forgetAll() {
        tails.clear()
        cwds.clear()
    }

    /**
     * Whether the project directory [name] holds sessions belonging to [worktree].
     *
     * An exact name match is unambiguous. A longer name is not: the encoding maps `/` and `-` to
     * the same character, so the worktree `feature` and its *sibling* `feature-two` both encode to
     * something starting with `-repo-feature-`, and prefix matching alone would hand one
     * worktree's spend to the other. Branch names share prefixes constantly, so this is the normal
     * case rather than a corner. The transcript records the real working directory it was started
     * in, so that is what decides.
     */
    private fun claims(root: String, name: String, prefix: String, worktree: String): Boolean {
        if (name == prefix) return true
        if (!name.startsWith("$prefix-")) return false
        val cwd = cwdOf(fs.resolve(root, name)) ?: return false
        return cwd == worktree || cwd.startsWith("$worktree/")
    }

    /**
     * The working directory a project directory was created from, read out of its own transcript.
     *
     * One small read of one file, cached: a directory's name never changes meaning.
     */
    private fun cwdOf(dir: String): String? {
        cwds[dir]?.let { return it }
        val file = fs.listDirectory(dir).firstOrNull { it.endsWith(TRANSCRIPT_SUFFIX) } ?: return null
        val head = fs.readFrom(fs.resolve(dir, file), 0, CWD_PROBE_BYTES).decodeToString()
        val cwd = head.lineSequence()
            .mapNotNull { line ->
                // The last line of the probe is usually cut in half; that one simply fails to parse.
                runCatching { json.parseToJsonElement(line).jsonObject["cwd"]?.jsonPrimitive?.content }
                    .getOrNull()
            }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        cwds[dir] = cwd
        return cwd
    }

    /**
     * The session transcripts in one project directory, plus each session's subagent transcripts.
     *
     * Subagents get their own files under `<sessionId>/subagents/agent-*.jsonl` and carry the same
     * `usage` shape. Skipping them would under-report every agent that delegates — which, on this
     * app's own wall, is most of them.
     */
    private fun transcriptsIn(dir: String): List<String> {
        val sessions = fs.listDirectory(dir).filter { it.endsWith(TRANSCRIPT_SUFFIX) }
        return sessions.flatMap { name ->
            val main = fs.resolve(dir, name)
            val subagentDir = fs.resolve(fs.resolve(dir, name.removeSuffix(TRANSCRIPT_SUFFIX)), SUBAGENT_DIR)
            val subagents = if (fs.isDirectory(subagentDir)) {
                fs.listDirectory(subagentDir)
                    .filter { it.endsWith(TRANSCRIPT_SUFFIX) }
                    .map { fs.resolve(subagentDir, it) }
            } else {
                emptyList()
            }
            listOf(main) + subagents
        }
    }

    private fun merge(a: WorktreeUsage, b: WorktreeUsage): WorktreeUsage {
        if (b.isEmpty) return a
        val byModel = HashMap(a.byModel)
        b.byModel.forEach { (key, usage) -> byModel[key] = (byModel[key] ?: TokenUsage.NONE) + usage }
        return WorktreeUsage(byModel, a.requests + b.requests)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val PROJECTS_DIR = ".claude/projects"
        /** Enough of a transcript to reach the first line carrying a `cwd`. */
        private const val CWD_PROBE_BYTES = 64 * 1024
        private const val TRANSCRIPT_SUFFIX = ".jsonl"
        private const val SUBAGENT_DIR = "subagents"

        /**
         * The directory name Claude Code derives from a working directory.
         *
         * Every character outside `[A-Za-z0-9-]` becomes `-`, which is why the leading slash gives
         * a leading dash and `/.config` gives two. The mapping is lossy, so it cannot be reversed —
         * but it does preserve the separator, which is what makes the `"$prefix-"` test in [read]
         * exact: a session started in a *subdirectory* of the worktree gets its own directory, and
         * that directory's name is the worktree's name followed by a dash.
         */
        fun encodeProjectDir(path: String): String = buildString(path.length) {
            path.forEach { c ->
                append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-') c else '-')
            }
        }
    }
}
