package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals

class Utf8DecoderTest {

    private fun decode(vararg chunks: ByteArray): List<Int> {
        val decoder = Utf8Decoder()
        val out = mutableListOf<Int>()
        chunks.forEach { chunk -> decoder.decode(chunk, chunk.size) { out += it } }
        return out
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `ascii passes through`() {
        assertEquals(listOf(0x48, 0x69), decode("Hi".encodeToByteArray()))
    }

    @Test
    fun `multi-byte characters decode to one code point each`() {
        assertEquals(listOf(0x00E9), decode("é".encodeToByteArray()))
        assertEquals(listOf(0x4E2D), decode("中".encodeToByteArray()))
        assertEquals(listOf(0x1F332), decode("🌲".encodeToByteArray()))
    }

    /**
     * The bug this class exists for: a pty hands over whatever was in its buffer, so a character
     * routinely arrives in two pieces. Split at every point inside a four-byte tree and the result
     * has to be the tree, not a pair of replacement characters.
     */
    @Test
    fun `a character split across chunks still decodes to one character`() {
        val tree = "🌲".encodeToByteArray()
        for (cut in 1 until tree.size) {
            val head = tree.copyOfRange(0, cut)
            val tail = tree.copyOfRange(cut, tree.size)
            assertEquals(listOf(0x1F332), decode(head, tail), "split after $cut bytes")
        }
    }

    @Test
    fun `an unfinished character at the end of the stream becomes one replacement`() {
        val decoder = Utf8Decoder()
        val out = mutableListOf<Int>()
        val head = "🌲".encodeToByteArray().copyOfRange(0, 2)
        decoder.decode(head, head.size) { out += it }
        assertEquals(emptyList(), out, "an incomplete character must wait for the next chunk")
        decoder.flush { out += it }
        assertEquals(listOf(Utf8Decoder.REPLACEMENT), out)
    }

    /**
     * One replacement per maximal subpart, and the byte that ended the bad sequence is reconsidered
     * — so the `A` survives instead of being swallowed with the sequence that failed.
     */
    @Test
    fun `a broken sequence costs one replacement and does not eat the next character`() {
        assertEquals(listOf(Utf8Decoder.REPLACEMENT, 0x41), decode(bytes(0xE0, 0x41)))
        assertEquals(listOf(Utf8Decoder.REPLACEMENT, 0x41), decode(bytes(0xF0, 0x9F, 0x41)))
    }

    @Test
    fun `overlong encodings and surrogates are rejected`() {
        // C0 80 is an overlong NUL, ED A0 80 is half a surrogate pair, F5 is beyond U+10FFFF.
        assertEquals(listOf(Utf8Decoder.REPLACEMENT, Utf8Decoder.REPLACEMENT), decode(bytes(0xC0, 0x80)))
        assertEquals(
            listOf(Utf8Decoder.REPLACEMENT, Utf8Decoder.REPLACEMENT, Utf8Decoder.REPLACEMENT),
            decode(bytes(0xED, 0xA0, 0x80)),
        )
        assertEquals(listOf(Utf8Decoder.REPLACEMENT), decode(bytes(0xF5)))
    }

    @Test
    fun `a continuation byte with nothing to continue is one replacement`() {
        assertEquals(listOf(Utf8Decoder.REPLACEMENT, 0x41), decode(bytes(0x80, 0x41)))
    }
}
