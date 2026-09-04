package io.mainactor.worktree.usage

import io.mainactor.worktree.platform.JvmFileSystemAccess
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsageReaderTest {

    private val fs = JvmFileSystemAccess()

    @Test
    fun `the project directory name is the working directory with everything else dashed out`() {
        assertEquals(
            "-Users-dev-AndroidStudioProjects-Worktree",
            ClaudeUsageSource.encodeProjectDir("/Users/dev/AndroidStudioProjects/Worktree"),
        )
        // A dot is dashed like a slash, which is why a hidden directory yields a double dash.
        assertEquals(
            "-Users-dev--config-zellij",
            ClaudeUsageSource.encodeProjectDir("/Users/dev/.config/zellij"),
        )
        assertEquals("-a-b-c", ClaudeUsageSource.encodeProjectDir("/a/b_c"))
    }

    @Test
    fun `a session below the worktree counts, a sibling with the same prefix does not`() {
        // The encoding maps both '/' and '-' to '-', so `feature` and `feature-two` produce
        // directory names where one is a prefix of the other. Branch names share prefixes all the
        // time, so this decides whether one worktree is handed another's spend.
        val home = Files.createTempDirectory("usage-home").toFile()
        project(home, "-repo-feature", cwd = "/repo/feature", output = 100)
        project(home, "-repo-feature-src", cwd = "/repo/feature/src", output = 20)
        project(home, "-repo-feature-two", cwd = "/repo/feature-two", output = 9_000)

        val usage = UsageReader(JvmFileSystemAccess(home = home.path)).read("/repo/feature")

        assertEquals(120, usage.tokens.output, "expected the worktree and the session below it")
        assertEquals(2, usage.requests)
        home.deleteRecursively()
    }

    @Test
    fun `subagent transcripts are counted too`() {
        val home = Files.createTempDirectory("usage-home").toFile()
        val dir = project(home, "-repo-feature", cwd = "/repo/feature", output = 100)
        // Subagents write beside the session, under <sessionId>/subagents/.
        File(dir, "session/subagents").mkdirs()
        File(dir, "session/subagents/agent-abc.jsonl")
            .writeText(assistantLine("req_sub", cwd = "/repo/feature", output = 55))

        val usage = UsageReader(JvmFileSystemAccess(home = home.path)).read("/repo/feature")

        assertEquals(155, usage.tokens.output, "an agent that delegates would otherwise under-report")
        home.deleteRecursively()
    }

    @Test
    fun `polling again picks up what an agent has since spent`() {
        val home = Files.createTempDirectory("usage-home").toFile()
        val dir = project(home, "-repo-feature", cwd = "/repo/feature", output = 100)
        val reader = UsageReader(JvmFileSystemAccess(home = home.path))
        assertEquals(100, reader.read("/repo/feature").tokens.output)

        File(dir, "session.jsonl").appendText(assistantLine("req_2", cwd = "/repo/feature", output = 40))

        assertEquals(140, reader.read("/repo/feature").tokens.output)
        home.deleteRecursively()
    }

    @Test
    fun `reads this machine's own Claude transcripts`() {
        // Fixtures cannot catch a change in where Claude Code writes or how it shapes a line; this
        // can, and it skips wherever there is nothing to read. The files are copied first because
        // the live one is being appended to by the session running this test, and a response
        // written between the two passes below would make them disagree by one.
        val here = File("").absoluteFile.parentFile.path
        val live = File(fs.homeDir(), ".claude/projects/${ClaudeUsageSource.encodeProjectDir(here)}")
        if (!live.isDirectory) return

        val home = Files.createTempDirectory("usage-real").toFile()
        val copy = File(home, ".claude/projects/${live.name}")
        live.copyRecursively(copy)

        val usage = UsageReader(JvmFileSystemAccess(home = home.path)).read(here)

        assertTrue(usage.requests > 0, "no responses found under $live")
        assertTrue(usage.tokens.output > 0)
        assertTrue(usage.costUsd > 0.0)

        // An independent count, by text rather than by JSON, of the responses that should be there.
        val expected = copy.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .flatMap { it.readLines().asSequence() }
            .filter { "\"type\":\"assistant\"" in it && "\"usage\"" in it && "\"<synthetic>\"" !in it }
            .mapNotNull { REQUEST_ID.find(it)?.groupValues?.get(1) }
            .toSet()
        assertEquals(expected.size, usage.requests, "responses were double-counted or missed")
        home.deleteRecursively()
    }

    @Test
    fun `reads this machine's own Codex rollouts`() {
        // Codex files sessions by date, so the worktree comes from inside the file rather than
        // from a directory name. Rather than hardcode a path from this machine, take a real
        // rollout and ask it which directory it belongs to.
        val sessions = File(fs.homeDir(), ".codex/sessions")
        if (!sessions.isDirectory) return
        val rollout = sessions.walkTopDown()
            .filter { it.isFile && it.name.startsWith("rollout-") && it.name.endsWith(".jsonl") }
            .maxByOrNull { it.lastModified() } ?: return
        val meta = Json.parseToJsonElement(rollout.useLines { it.first() }).jsonObject
        if (meta["type"]?.jsonPrimitive?.content != "session_meta") return
        val cwd = meta["payload"]!!.jsonObject["cwd"]!!.jsonPrimitive.content

        val usage = UsageReader(fs).read(cwd)

        val codex = usage.byModel.filterKeys { it.tool == AgentTool.CODEX }
        assertTrue(codex.isNotEmpty(), "no Codex usage found for $cwd")

        // The session's own last cumulative total has to be inside what the reader reports; the
        // directory may hold several sessions, but never fewer tokens than this one.
        val last = rollout.readLines().asSequence()
            .filter { "token_count" in it }
            .mapNotNull { line ->
                runCatching {
                    Json.parseToJsonElement(line).jsonObject["payload"]!!.jsonObject["info"]!!
                        .jsonObject["total_token_usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.long
                }.getOrNull()
            }
            .lastOrNull() ?: return
        val codexTokens = codex.values.fold(TokenUsage.NONE, TokenUsage::plus).total
        assertTrue(
            codexTokens >= last,
            "reader reported $codexTokens for $cwd, less than the $last one of its sessions records",
        )
    }

    @Test
    fun `a worktree nobody has run an agent in reports nothing`() {
        val usage = UsageReader(fs).read("/nowhere/at/all/${System.nanoTime()}")

        assertTrue(usage.isEmpty)
        assertEquals(0.0, usage.costUsd)
    }

    /** A project directory holding one session transcript with one response in it. */
    private fun project(home: File, dirName: String, cwd: String, output: Long): File {
        val dir = File(home, ".claude/projects/$dirName").apply { mkdirs() }
        File(dir, "session.jsonl").writeText(assistantLine("req_$dirName", cwd, output))
        return dir
    }

    private fun assistantLine(requestId: String, cwd: String, output: Long) = """
        {"type":"assistant","cwd":"$cwd","sessionId":"session","requestId":"$requestId",
        "message":{"id":"m","model":"claude-opus-5",
        "usage":{"input_tokens":1,"output_tokens":$output,"cache_read_input_tokens":0}}}
    """.trimIndent().replace("\n", "") + "\n"

    private companion object {
        val REQUEST_ID = Regex("\"requestId\":\"([^\"]+)\"")
    }
}
