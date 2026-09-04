package io.mainactor.worktree.usage

import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeTranscriptTest {

    private val fs = InMemoryFiles()
    private val path = "/t/session.jsonl"

    @Test
    fun `every line of one response is counted once`() {
        // A real transcript had 1050 assistant lines for 596 responses; counting lines rather than
        // responses over-reports by nearly a factor of two.
        fs.write(path, (1..4).joinToString("") { line("req_1", output = 100) })
        val transcript = ClaudeTranscript(path)

        transcript.poll(fs)

        assertEquals(1, transcript.usage.requests)
        assertEquals(100, transcript.usage.tokens.output)
    }

    @Test
    fun `a response split across two polls is still one response`() {
        val transcript = ClaudeTranscript(path)
        fs.write(path, line("req_1", output = 100))
        transcript.poll(fs)

        fs.append(path, line("req_1", output = 100))
        transcript.poll(fs)

        assertEquals(1, transcript.usage.requests)
        assertEquals(100, transcript.usage.tokens.output)
    }

    @Test
    fun `a later poll adds what the agent has since spent`() {
        val transcript = ClaudeTranscript(path)
        fs.write(path, line("req_1", output = 100))
        transcript.poll(fs)

        fs.append(path, line("req_2", output = 250))
        transcript.poll(fs)

        assertEquals(2, transcript.usage.requests)
        assertEquals(350, transcript.usage.tokens.output)
    }

    @Test
    fun `a file replaced by a shorter one starts over`() {
        val transcript = ClaudeTranscript(path)
        fs.write(path, line("req_1", output = 100) + line("req_2", output = 100))
        transcript.poll(fs)
        assertEquals(2, transcript.usage.requests)

        fs.write(path, line("req_3", output = 7))
        transcript.poll(fs)

        assertEquals(1, transcript.usage.requests)
        assertEquals(7, transcript.usage.tokens.output)
    }

    private fun line(requestId: String, output: Long) = """
        {"type":"assistant","requestId":"$requestId","message":{"id":"m","model":"claude-opus-5",
        "usage":{"input_tokens":1,"output_tokens":$output,"cache_read_input_tokens":5}}}
    """.trimIndent().replace("\n", "") + "\n"
}
