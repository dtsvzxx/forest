package io.mainactor.worktree.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both tools' session logs are read by tailing them while they are being written, which is where
 * the reading can silently go wrong: the last line is routinely half-written, and re-reading from
 * the start would make a 59 MB transcript cost that much on every poll.
 */
class JsonlTailTest {

    private val fs = InMemoryFiles()
    private val path = "/t/session.jsonl"

    @Test
    fun `a second poll reads only what was appended`() {
        val tail = JsonlTail(path)
        val seen = mutableListOf<String>()
        fs.write(path, "one\n")
        tail.poll(fs) { seen += it }
        val afterFirst = tail.offset

        fs.append(path, "two\n")
        tail.poll(fs) { seen += it }

        assertEquals(listOf("one", "two"), seen.filter { it.isNotBlank() })
        assertTrue(tail.offset > afterFirst)
        // The point of the whole class: the first line's bytes were never handed over twice.
        assertEquals(afterFirst, fs.reads.last().first)
    }

    @Test
    fun `a half-written trailing line waits until it is complete`() {
        val tail = JsonlTail(path)
        val seen = mutableListOf<String>()
        fs.write(path, "one\ntw")

        tail.poll(fs) { seen += it }
        assertEquals(listOf("one"), seen.filter { it.isNotBlank() })
        assertEquals(4, tail.offset, "the offset must stop at the last complete line")

        fs.append(path, "o\n")
        tail.poll(fs) { seen += it }
        assertEquals(listOf("one", "two"), seen.filter { it.isNotBlank() })
    }

    @Test
    fun `a file replaced by a shorter one is read again from the start`() {
        val tail = JsonlTail(path)
        var resets = 0
        fs.write(path, "one\ntwo\nthree\n")
        tail.poll(fs, onReset = { resets++ }) { }

        fs.write(path, "new\n")
        val seen = mutableListOf<String>()
        tail.poll(fs, onReset = { resets++ }) { seen += it }

        assertEquals(1, resets)
        assertEquals(listOf("new"), seen.filter { it.isNotBlank() })
    }

    @Test
    fun `multi-byte characters survive being read by byte offset`() {
        val tail = JsonlTail(path)
        val seen = mutableListOf<String>()
        // Offsets are in bytes, and a line is decoded only up to a newline — always a character
        // boundary — so no path with an em dash or an emoji is ever sliced in half.
        fs.write(path, "путь — ✅\nsecond\n")

        tail.poll(fs) { seen += it }

        assertEquals(listOf("путь — ✅", "second"), seen.filter { it.isNotBlank() })
        assertEquals(fs.fileSize(path), tail.offset)
    }
}
