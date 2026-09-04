package io.mainactor.worktree.usage

import io.mainactor.worktree.platform.FileSystemAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One agent CLI's on-disk record of what it spent. */
interface UsageSource {
    val tool: AgentTool

    /** Everything that tool has spent in [worktreePath], as of now. */
    fun read(worktreePath: String): WorktreeUsage

    /**
     * Whether the tool has ever run in [worktreePath].
     *
     * Cheaper than [read] and answers a different question: it decides whether starting an agent
     * there should resume the last session or begin a new one, so it must not depend on any of
     * those sessions having spent a token.
     */
    fun hasSessions(worktreePath: String): Boolean

    /** Drops every cached file position. */
    fun forgetAll()
}

/**
 * One Claude Code transcript — `~/.claude/projects/<encoded cwd>/<sessionId>.jsonl`.
 *
 * **One response is written as several lines**, one per content block, each repeating the identical
 * `usage` object. A real transcript held 1050 assistant lines for 596 responses, so adding up lines
 * over-reports by about 1.8x. The request ids already counted are therefore kept *across* polls,
 * not merely within one: a response's lines straddle the moment a poll stops reading.
 */
class ClaudeTranscript(path: String) {

    private val tail = JsonlTail(path)
    private val counted = HashSet<String>()
    private val totals = HashMap<ModelKey, TokenUsage>()

    /** This file's contribution. Sessions are counted by the source, since one has several files. */
    val usage: WorktreeUsage get() = WorktreeUsage(totals.toMap(), counted.size)

    val hasUsage: Boolean get() = counted.isNotEmpty()

    fun poll(fs: FileSystemAccess) = tail.poll(fs, onReset = { reset() }) { line ->
        val record = UsageParser.parse(line)
        if (record != null && counted.add(record.requestId)) {
            totals[record.key] = (totals[record.key] ?: TokenUsage.NONE) + record.usage
        }
    }

    private fun reset() {
        counted.clear()
        totals.clear()
    }
}

/**
 * What Claude Code has spent in a worktree.
 *
 * Claude Code files its sessions under a directory named after the working directory, so the
 * matching is by name — with one correction, see [claims].
 */
class ClaudeUsageSource(private val fs: FileSystemAccess) : UsageSource {

    override val tool = AgentTool.CLAUDE

    private val transcripts = HashMap<String, ClaudeTranscript>()
    private val cwds = HashMap<String, String>()

    override fun read(worktreePath: String): WorktreeUsage {
        val root = fs.resolve(fs.homeDir(), PROJECTS_DIR)
        if (!fs.isDirectory(root)) return WorktreeUsage.NONE

        val prefix = encodeProjectDir(worktreePath)
        var total = WorktreeUsage.NONE
        fs.listDirectory(root)
            .filter { name -> claims(root, name, prefix, worktreePath) }
            .forEach { dir ->
                sessionsIn(fs.resolve(root, dir)).forEach { files ->
                    var session = WorktreeUsage.NONE
                    files.forEach { file ->
                        val transcript = transcripts.getOrPut(file) { ClaudeTranscript(file) }
                        transcript.poll(fs)
                        session = session.merge(transcript.usage)
                    }
                    // One session however many files it wrote — the main transcript plus one per
                    // subagent — so the count is set here rather than per file.
                    if (!session.isEmpty) total = total.merge(session.copy(sessions = 1))
                }
            }
        return total
    }

    override fun hasSessions(worktreePath: String): Boolean {
        val root = fs.resolve(fs.homeDir(), PROJECTS_DIR)
        if (!fs.isDirectory(root)) return false
        val prefix = encodeProjectDir(worktreePath)
        return fs.listDirectory(root)
            .filter { name -> claims(root, name, prefix, worktreePath) }
            .any { dir -> fs.listDirectory(fs.resolve(root, dir)).any { it.endsWith(TRANSCRIPT_SUFFIX) } }
    }

    override fun forgetAll() {
        transcripts.clear()
        cwds.clear()
    }

    /**
     * Whether the project directory [name] holds sessions belonging to [worktree].
     *
     * An exact name match is unambiguous. A longer name is not: the encoding maps `/` and `-` to
     * the same character, so the worktree `feature` and its *sibling* `feature-two` both encode to
     * something starting with `-repo-feature-`, and prefix matching alone would hand one worktree's
     * spend to the other. Branch names share prefixes constantly, so this is the normal case rather
     * than a corner. The transcript records the working directory it was started in; that decides.
     */
    private fun claims(root: String, name: String, prefix: String, worktree: String): Boolean {
        if (name == prefix) return true
        if (!name.startsWith("$prefix-")) return false
        val cwd = cwdOf(fs.resolve(root, name)) ?: return false
        return cwd == worktree || cwd.startsWith("$worktree/")
    }

    /** The working directory a project directory was made from, read from its own transcript. */
    private fun cwdOf(dir: String): String? {
        cwds[dir]?.let { return it }
        val file = fs.listDirectory(dir).firstOrNull { it.endsWith(TRANSCRIPT_SUFFIX) } ?: return null
        val head = fs.readFrom(fs.resolve(dir, file), 0, CWD_PROBE_BYTES).decodeToString()
        val cwd = head.lineSequence()
            .mapNotNull { line ->
                // The last line of the probe is usually cut in half; that one fails to parse.
                runCatching { json.parseToJsonElement(line).jsonObject["cwd"]?.jsonPrimitive?.content }
                    .getOrNull()
            }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        cwds[dir] = cwd
        return cwd
    }

    /**
     * Each session in a project directory, as its main transcript plus its subagents'.
     *
     * Subagents write their own files under `<sessionId>/subagents/agent-*.jsonl` and carry the
     * same `usage` shape. Skipping them would under-report every agent that delegates — which, on
     * this app's own wall, is most of them.
     */
    private fun sessionsIn(dir: String): List<List<String>> =
        fs.listDirectory(dir).filter { it.endsWith(TRANSCRIPT_SUFFIX) }.map { name ->
            val main = fs.resolve(dir, name)
            val subagentDir =
                fs.resolve(fs.resolve(dir, name.removeSuffix(TRANSCRIPT_SUFFIX)), SUBAGENT_DIR)
            val subagents = if (fs.isDirectory(subagentDir)) {
                fs.listDirectory(subagentDir)
                    .filter { it.endsWith(TRANSCRIPT_SUFFIX) }
                    .map { fs.resolve(subagentDir, it) }
            } else {
                emptyList()
            }
            listOf(main) + subagents
        }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val PROJECTS_DIR = ".claude/projects"
        private const val TRANSCRIPT_SUFFIX = ".jsonl"
        private const val SUBAGENT_DIR = "subagents"

        /** Enough of a transcript to reach the first line carrying a `cwd`. */
        private const val CWD_PROBE_BYTES = 64 * 1024

        /**
         * The directory name Claude Code derives from a working directory.
         *
         * Every character outside `[A-Za-z0-9-]` becomes `-`, which is why the leading slash gives
         * a leading dash and `/.config` gives two. The mapping is lossy and cannot be reversed,
         * which is what [claims] has to work around.
         */
        fun encodeProjectDir(path: String): String = buildString(path.length) {
            path.forEach { c ->
                append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-') c else '-')
            }
        }
    }
}

/**
 * What every supported agent CLI has spent in a worktree.
 *
 * The source is always the session log the tool writes for *itself* — Claude Code's transcripts
 * under `~/.claude/projects/`, Codex's rollouts under `~/.codex/sessions/`. Reading those rather
 * than watching the terminal is what makes this accurate and free of side effects: it is the usage
 * the API actually reported, and looking at it cannot disturb a running agent. It also means every
 * session in the worktree is counted, including ones started from an ordinary terminal rather than
 * from a pane in this app.
 */
class UsageReader(private val fs: FileSystemAccess) {

    private val sources: List<UsageSource> = listOf(ClaudeUsageSource(fs), CodexUsageSource(fs))

    /** Everything spent in [worktreePath], across every tool. */
    fun read(worktreePath: String): WorktreeUsage {
        val canonical = fs.canonicalPath(worktreePath)
        return sources.fold(WorktreeUsage.NONE) { total, source -> total.merge(source.read(canonical)) }
    }

    /** Whether [tool] has ever run in [worktreePath] — what decides resume against a fresh start. */
    fun hasSessions(worktreePath: String, tool: AgentTool): Boolean {
        val canonical = fs.canonicalPath(worktreePath)
        return sources.any { it.tool == tool && it.hasSessions(canonical) }
    }

    /** Forgets every file position, so a closed worktree's state is not kept for the session. */
    fun forgetAll() = sources.forEach { it.forgetAll() }
}
