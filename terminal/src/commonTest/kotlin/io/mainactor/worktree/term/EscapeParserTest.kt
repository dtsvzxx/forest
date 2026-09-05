package io.mainactor.worktree.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Records what the parser reported, as text, so a test reads as the sequence it feeds.
 *
 * `print` runs are joined, because what matters is which characters reached the screen and in what
 * order — not that they arrived one call at a time.
 */
private class Recorder : ParserSink {
    val events = mutableListOf<String>()
    private val text = StringBuilder()

    private fun flushText() {
        if (text.isNotEmpty()) {
            events += "print(${text})"
            text.clear()
        }
    }

    override fun print(codePoint: Int) {
        text.append(codePoint.toChar())
    }

    override fun execute(control: Int) {
        flushText()
        events += "exec(${control.toString(16)})"
    }

    override fun escape(intermediates: Int, intermediateCount: Int, final: Int) {
        flushText()
        events += "esc(${intermediateText(intermediates, intermediateCount)}${final.toChar()})"
    }

    override fun csi(params: Params, intermediates: Int, intermediateCount: Int, private: Int, final: Int) {
        flushText()
        val marker = if (private == 0) "" else private.toChar().toString()
        events += "csi($marker$params${intermediateText(intermediates, intermediateCount)}${final.toChar()})"
    }

    override fun osc(data: String) {
        flushText()
        events += "osc($data)"
    }

    override fun dcsHook(params: Params, intermediates: Int, intermediateCount: Int, final: Int) {
        flushText()
        events += "dcs($params${intermediateText(intermediates, intermediateCount)}${final.toChar()})"
    }

    override fun dcsPut(codePoint: Int) {
        events += "put(${codePoint.toChar()})"
    }

    override fun dcsUnhook() {
        events += "unhook"
    }

    private fun intermediateText(packed: Int, count: Int): String =
        (0 until count).map { ((packed shr (8 * it)) and 0xFF).toChar() }.joinToString("")

    fun done(): List<String> {
        flushText()
        return events
    }
}

class EscapeParserTest {

    private fun parse(input: String): List<String> {
        val parser = EscapeParser()
        val recorder = Recorder()
        input.forEach { parser.advance(it.code, recorder) }
        return recorder.done()
    }

    private val esc = ""

    @Test
    fun `plain text prints`() {
        assertEquals(listOf("print(hello)"), parse("hello"))
    }

    @Test
    fun `control characters execute rather than print`() {
        assertEquals(listOf("print(a)", "exec(a)", "exec(d)", "print(b)"), parse("a\n\rb"))
    }

    @Test
    fun `a simple control sequence carries its parameters`() {
        assertEquals(listOf("csi(1;31m)"), parse("$esc[1;31m"))
        assertEquals(listOf("csi(H)"), parse("$esc[H"))
        assertEquals(listOf("csi(?25l)"), parse("$esc[?25l"))
        assertEquals(listOf("csi(>4;2m)"), parse("$esc[>4;2m"))
    }

    /** `CSI ; 5 H` means "row default, column 5"; an omitted parameter is not a zero. */
    @Test
    fun `an omitted parameter is not the same as a zero`() {
        val parser = EscapeParser()
        var absentAsSeven = -1
        var explicitZero = -1
        val sink = object : ParserSink by Recorder() {
            override fun csi(params: Params, intermediates: Int, intermediateCount: Int, private: Int, final: Int) {
                absentAsSeven = params.at(0, ifAbsent = 7)
                explicitZero = params.at(1, ifAbsent = 7)
            }
        }
        "$esc[;0H".forEach { parser.advance(it.code, sink) }
        assertEquals(7, absentAsSeven, "an absent parameter must fall back to its default")
        assertEquals(0, explicitZero, "an explicit zero is a zero")
    }

    /**
     * The modern colour form. The 1970s table sends a colon to the ignore state, which would throw
     * away every truecolour SGR written this way — and Claude Code writes them this way.
     */
    @Test
    fun `colons separate sub-parameters instead of aborting the sequence`() {
        assertEquals(listOf("csi(38:2::255:0:0m)"), parse("$esc[38:2::255:0:0m"))

        val parser = EscapeParser()
        val joined = mutableListOf<Boolean>()
        val sink = object : ParserSink by Recorder() {
            override fun csi(params: Params, intermediates: Int, intermediateCount: Int, private: Int, final: Int) {
                (0 until params.size).forEach { joined += params.continues(it) }
            }
        }
        "$esc[4:3m".forEach { parser.advance(it.code, sink) }
        assertEquals(listOf(false, true), joined, "the second value continues the first")
    }

    @Test
    fun `escapes with intermediates dispatch with them`() {
        assertEquals(listOf("esc((0)"), parse("$esc(0"))
        assertEquals(listOf("esc(=)"), parse("$esc="))
        assertEquals(listOf("csi(1 q)"), parse("$esc[1 q"))
    }

    @Test
    fun `an operating-system command ends on either terminator`() {
        assertEquals(listOf("osc(0;a title)"), parse("$esc]0;a title"))
        // `ESC \` hands the string over on the ESC, and then dispatches ST like any other escape.
        // The table has no way to know the two belong together; the emulator ignores a bare ST.
        assertEquals(listOf("osc(0;a title)", "esc(\\)"), parse("$esc]0;a title$esc\\"))
        assertEquals(
            listOf("osc(8;;https://example.com)", "esc(\\)", "print(x)"),
            parse("$esc]8;;https://example.com$esc\\x"),
        )
    }

    @Test
    fun `a device-control string is opened, filled and closed`() {
        assertEquals(
            listOf("dcs(1\$q)", "put(m)", "unhook", "esc(\\)", "print(x)"),
            parse("${esc}P1\$qm$esc\\x"),
        )
    }

    /**
     * What a terminal actually spends its undefined moments on. Each of these has to leave the
     * parser back on the ground, printing text, rather than swallowing everything that follows.
     */
    @Test
    fun `malformed input is recovered from rather than swallowed`() {
        // Cancelled halfway through.
        assertEquals(listOf("exec(18)", "print(ok)"), parse("$esc[12;ok"))
        // A sequence interrupted by the start of another one.
        assertEquals(listOf("csi(1m)", "print(ok)"), parse("$esc[99$esc[1mok"))
        // More parameters than any real sequence carries: consumed, and nothing dispatched.
        val many = (1..40).joinToString(";")
        assertEquals(listOf("print(ok)"), parse("$esc[${many}mok"))
        // A cancelled OSC hands over nothing.
        assertEquals(listOf("exec(18)", "print(ok)"), parse("$esc]0;halfok"))
    }

    @Test
    fun `a flood of random bytes leaves the parser on the ground`() {
        val parser = EscapeParser()
        val recorder = Recorder()
        var seed = 0x5EED
        repeat(200_000) {
            seed = seed * 1103515245 + 12345
            parser.advance((seed ushr 16) and 0xFF, recorder)
        }
        // Whatever it saw, ordinary text has to work afterwards.
        val after = mutableListOf<String>()
        val plain = Recorder()
        "$esc[0m" .forEach { parser.advance(it.code, plain) }
        "hello".forEach { parser.advance(it.code, plain) }
        after += plain.done()
        assertTrue(after.contains("print(hello)"), "the parser stopped printing text: $after")
    }
}
