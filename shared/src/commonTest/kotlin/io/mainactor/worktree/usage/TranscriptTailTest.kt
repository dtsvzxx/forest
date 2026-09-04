package io.mainactor.worktree.usage

import io.mainactor.worktree.platform.FileSystemAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tail is where the counting can silently go wrong: a response's lines can straddle a poll, a
 * file's last line is routinely half-written, and every line of one response repeats the same
 * usage. These drive it the way a running transcript does — a bit at a time.
 */
class TranscriptTailTest {

    private val fs = InMemoryFiles()
    private val path = "/t/session.jsonl"

    @Test
    fun `every line of one response is counted once`() {
        // A real transcript had 1050 assistant lines for 596 responses; counting lines rather than
        // responses over-reports by nearly a factor of two.
        fs.write(path, (1..4).joinToString("") { line("req_1", output = 100) })
        val tail = TranscriptTail(path)

        tail.poll(fs)

        assertEquals(1, tail.usage.requests)
        assertEquals(100, tail.usage.tokens.output)
    }

    @Test
    fun `a second poll reads only what was appended`() {
        val tail = TranscriptTail(path)
        fs.write(path, line("req_1", output = 100))
        tail.poll(fs)
        val afterFirst = tail.offset

        fs.append(path, line("req_2", output = 250))
        tail.poll(fs)

        assertTrue(tail.offset > afterFirst)
        assertEquals(2, tail.usage.requests)
        assertEquals(350, tail.usage.tokens.output)
        // The whole point: the first line's bytes were never handed over a second time.
        assertEquals(fs.reads.last().first, afterFirst)
    }

    @Test
    fun `a response split across two polls is still one response`() {
        val tail = TranscriptTail(path)
        fs.write(path, line("req_1", output = 100))
        tail.poll(fs)

        fs.append(path, line("req_1", output = 100))
        tail.poll(fs)

        assertEquals(1, tail.usage.requests)
        assertEquals(100, tail.usage.tokens.output)
    }

    @Test
    fun `a half-written trailing line is picked up once it is complete`() {
        val tail = TranscriptTail(path)
        val whole = line("req_1", output = 100)
        fs.write(path, whole.dropLast(30))

        tail.poll(fs)
        assertEquals(0, tail.usage.requests, "an incomplete line must not be counted")
        assertEquals(0, tail.offset, "and must not be skipped over either")

        fs.write(path, whole)
        tail.poll(fs)
        assertEquals(1, tail.usage.requests)
        assertEquals(100, tail.usage.tokens.output)
    }

    @Test
    fun `a file replaced by a shorter one starts over`() {
        val tail = TranscriptTail(path)
        fs.write(path, line("req_1", output = 100) + line("req_2", output = 100))
        tail.poll(fs)
        assertEquals(2, tail.usage.requests)

        fs.write(path, line("req_3", output = 7))
        tail.poll(fs)

        assertEquals(1, tail.usage.requests)
        assertEquals(7, tail.usage.tokens.output)
    }

    @Test
    fun `multi-byte characters survive the chunk boundary`() {
        // Offsets are in bytes, and a line is decoded only up to a newline — which is always a
        // character boundary, so no émoji can be sliced in half.
        val tail = TranscriptTail(path)
        fs.write(path, line("req_1", output = 100, note = "путь — ✅"))

        tail.poll(fs)

        assertEquals(1, tail.usage.requests)
        assertEquals(fs.size(path), tail.offset)
    }

    private fun line(requestId: String, output: Long, note: String = "x") = """
        {"type":"assistant","requestId":"$requestId","note":"$note","message":{"id":"m","model":"claude-opus-5",
        "usage":{"input_tokens":1,"output_tokens":$output,"cache_read_input_tokens":5}}}
    """.trimIndent().replace("\n", "") + "\n"
}

/** Enough of a filesystem to append to a file and read it back by byte offset. */
private class InMemoryFiles : FileSystemAccess {
    private val files = HashMap<String, ByteArray>()

    /** Every (offset, length) handed to [readFrom], so a test can assert nothing was re-read. */
    val reads = mutableListOf<Pair<Long, Int>>()

    fun write(path: String, text: String) { files[path] = text.encodeToByteArray() }

    fun append(path: String, text: String) {
        files[path] = (files[path] ?: ByteArray(0)) + text.encodeToByteArray()
    }

    fun size(path: String): Long = files[path]?.size?.toLong() ?: 0

    override fun fileSize(path: String): Long = size(path)

    override fun readFrom(path: String, offset: Long, maxBytes: Int): ByteArray {
        reads += offset to maxBytes
        val bytes = files[path] ?: return ByteArray(0)
        if (offset >= bytes.size) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + maxBytes).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }

    override fun listDirectory(path: String): List<String> = emptyList()
    override fun exists(path: String) = files.containsKey(path)
    override fun isDirectory(path: String) = false
    override fun readText(path: String) = files[path]?.decodeToString().orEmpty()
    override fun writeText(path: String, text: String) = write(path, text)
    override fun createDirectories(path: String) = Unit
    override fun homeDir() = "/home"
    override fun nameOf(path: String) = path.substringAfterLast('/')
    override fun parentOf(path: String): String? = path.substringBeforeLast('/').ifEmpty { null }
    override fun resolve(base: String, child: String) = "$base/$child"
    override fun canonicalPath(path: String) = path
    override fun lastModifiedAt(path: String) = 0L
    override fun now() = 0L
}
