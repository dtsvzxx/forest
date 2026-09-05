package io.mainactor.worktree.term

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays what real programs actually printed.
 *
 * Every other test feeds the emulator input written by hand, which means it only ever sees what the
 * author thought to write. These fixtures were recorded from `vim`, `less`, `tmux` and a shell
 * through our own pty (`./gradlew :terminal:recordFixtures`), and each carries a committed screen
 * dump reviewed once by eye. After that the dump is what catches a refactor that moves every colour
 * by one, or a scroll region that starts a row late.
 *
 * The two derived tests below are worth more than the goldens themselves.
 */
class RecordedStreamTest {

    private val fixtures = File("src/jvmTest/resources/term")

    private fun streams(): List<File> =
        fixtures.listFiles { file -> file.name.endsWith(".raw") }?.sortedBy { it.name }.orEmpty()

    private fun replay(bytes: ByteArray, chunk: Int = bytes.size): TerminalModel {
        val model = TerminalModel(80, 24, scrollback = 2000)
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(chunk, bytes.size - offset)
            model.feed(bytes.copyOfRange(offset, offset + length), length)
            offset += length
        }
        return model
    }

    @Test
    fun `the fixtures are there to be replayed`() {
        assertTrue(
            streams().size >= 4,
            "no recordings: run ./gradlew :terminal:recordFixtures — found ${streams().map { it.name }}",
        )
    }

    @Test
    fun `a recorded stream still paints the screen it painted when it was recorded`() {
        streams().forEach { stream ->
            val golden = File(fixtures, stream.name.removeSuffix(".raw") + ".golden")
            if (!golden.isFile) return@forEach
            val screen = replay(stream.readBytes()).screenText()
            assertEquals(golden.readText(), screen, "${stream.name} paints a different screen now")
        }
    }

    /**
     * The highest-yield test in the suite.
     *
     * Almost every real emulator bug is a parser state that did not survive a `read(2)` boundary: a
     * four-byte character split in half, a `CSI` whose parameters arrived in the next chunk. A pty
     * hands over whatever happened to be in its buffer, so those splits happen constantly under
     * load and never while anyone is watching. Chopping each recording at five hundred random
     * places, with a fixed seed so a failure can be reproduced, is what makes them happen on demand.
     */
    @Test
    fun `a recorded stream paints the same screen however it is chopped up`() {
        val random = Random(0x5EED)
        streams().forEach { stream ->
            val bytes = stream.readBytes()
            val whole = replay(bytes).screenText()
            repeat(20) { attempt ->
                val cuts = List(25) { random.nextInt(bytes.size + 1) }.sorted()
                val model = TerminalModel(80, 24, scrollback = 2000)
                var offset = 0
                (cuts + bytes.size).forEach { cut ->
                    if (cut > offset) {
                        val piece = bytes.copyOfRange(offset, cut)
                        model.feed(piece, piece.size)
                        offset = cut
                    }
                }
                assertEquals(
                    whole,
                    model.screenText(),
                    "${stream.name} paints differently when chopped (attempt $attempt, cuts $cuts)",
                )
            }
            // And the pathological case: one byte at a time.
            assertEquals(whole, replay(bytes, chunk = 1).screenText(), "${stream.name} broke byte by byte")
        }
    }

    /**
     * What a terminal spends its undefined moments on.
     *
     * A `cat` of a binary is not a hypothetical; it is what happens the first time anyone opens the
     * wrong file. Afterwards the prompt has to come back.
     */
    @Test
    fun `a megabyte of random bytes leaves the terminal usable`() {
        val model = TerminalModel(80, 24, scrollback = 200)
        val random = Random(0xC0FFEE)
        model.feed(random.nextBytes(1 shl 20))

        model.feed("${27.toChar()}c")   // RIS, which is what `reset` sends
        model.feed("still here")
        assertEquals("still here", model.buffer.line(0).text())
    }

    /** A recording with an alternate screen in it must leave the primary one intact underneath. */
    @Test
    fun `a full-screen program leaves the shell output beneath it alone`() {
        val vim = File(fixtures, "vim.raw")
        if (!vim.isFile) return
        val model = TerminalModel(80, 24, scrollback = 200)
        model.feed("a line from the shell\r\n")
        model.feed(vim.readBytes())
        assertTrue(model.buffer.onAlternateScreen, "vim should be on the alternate screen")

        model.feed("${27.toChar()}[?1049l")
        assertEquals("a line from the shell", model.buffer.line(0).text())
    }
}
