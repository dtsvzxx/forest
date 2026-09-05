package io.mainactor.worktree.term

/**
 * Turns a stream of bytes into code points, one chunk at a time.
 *
 * The whole reason this is a class with state rather than a function is the chunk boundary: a pty
 * hands over whatever happened to be in its buffer, so a four-byte emoji is routinely split across
 * two `read(2)`s. A decoder that started fresh on every chunk would turn the halves into two
 * replacement characters, and the mojibake would appear only under load — exactly when it is
 * hardest to reproduce.
 *
 * Invalid input follows the WHATWG rule: one U+FFFD per *maximal subpart*, not one per byte, and
 * the byte that ended a bad sequence is reconsidered as the start of the next one. So `E0 41`
 * yields U+FFFD and then `A`, rather than swallowing the `A`.
 */
class Utf8Decoder {

    private var needed = 0
    private var seen = 0
    private var accumulated = 0
    private var lowerBound = 0x80
    private var upperBound = 0xBF

    /** Called for each code point; [REPLACEMENT] stands in for anything malformed. */
    fun interface Sink {
        fun codePoint(value: Int)
    }

    fun decode(bytes: ByteArray, length: Int = bytes.size, sink: Sink) {
        var i = 0
        while (i < length) {
            val byte = bytes[i].toInt() and 0xFF
            if (needed == 0) {
                i++
                when (byte) {
                    in 0x00..0x7F -> sink.codePoint(byte)
                    // 0xC0 and 0xC1 could only ever encode an overlong ASCII byte.
                    in 0xC2..0xDF -> begin(byte and 0x1F, needed = 1)
                    in 0xE0..0xEF -> {
                        // E0 A0..BF excludes overlong forms; ED 80..9F excludes the surrogates.
                        if (byte == 0xE0) lowerBound = 0xA0
                        if (byte == 0xED) upperBound = 0x9F
                        begin(byte and 0x0F, needed = 2, keepBounds = true)
                    }
                    in 0xF0..0xF4 -> {
                        if (byte == 0xF0) lowerBound = 0x90
                        if (byte == 0xF4) upperBound = 0x8F
                        begin(byte and 0x07, needed = 3, keepBounds = true)
                    }
                    else -> sink.codePoint(REPLACEMENT)
                }
            } else if (byte < lowerBound || byte > upperBound) {
                // The sequence ends here and this byte is not part of it: emit one replacement for
                // what came before and look at this byte again from the top.
                reset()
                sink.codePoint(REPLACEMENT)
            } else {
                i++
                lowerBound = 0x80
                upperBound = 0xBF
                accumulated = (accumulated shl 6) or (byte and 0x3F)
                if (++seen == needed) {
                    val value = accumulated
                    reset()
                    sink.codePoint(value)
                }
            }
        }
    }

    /**
     * Ends the stream: an unfinished sequence becomes one replacement character.
     *
     * Only for a stream that is actually over. Calling it between chunks would reintroduce the bug
     * this class exists to avoid.
     */
    fun flush(sink: Sink) {
        if (needed != 0) {
            reset()
            sink.codePoint(REPLACEMENT)
        }
    }

    private fun begin(bits: Int, needed: Int, keepBounds: Boolean = false) {
        accumulated = bits
        this.needed = needed
        seen = 0
        if (!keepBounds) {
            lowerBound = 0x80
            upperBound = 0xBF
        }
    }

    private fun reset() {
        needed = 0
        seen = 0
        accumulated = 0
        lowerBound = 0x80
        upperBound = 0xBF
    }

    companion object {
        const val REPLACEMENT = 0xFFFD
    }
}
