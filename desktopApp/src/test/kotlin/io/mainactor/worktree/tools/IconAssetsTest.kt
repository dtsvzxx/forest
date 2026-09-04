package io.mainactor.worktree.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The packaged icons are committed files, and `nativeDistributions` points straight at them — so a
 * missing or truncated one only shows up when someone builds an installer. These checks put that
 * failure in the ordinary build instead.
 */
class IconAssetsTest {

    private val icons = File("icons")

    @Test
    fun `every platform's icon is present and is what it claims to be`() {
        val png = File(icons, "forest.png")
        val ico = File(icons, "forest.ico")
        val icns = File(icons, "forest.icns")

        listOf(png, ico, icns).forEach { file ->
            assertTrue(file.isFile, "${file.path} is missing — run ./gradlew :desktopApp:generateIcons")
            assertTrue(file.length() > 1_000, "${file.path} is suspiciously small at ${file.length()} bytes")
        }

        assertContentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
            png.readBytes().copyOfRange(0, 4), "the Linux icon is not a PNG")
        assertContentEquals(byteArrayOf(0, 0, 1, 0), ico.readBytes().copyOfRange(0, 4),
            "the Windows icon is not an ICO")
        assertEquals("icns", icns.readBytes().copyOfRange(0, 4).decodeToString(),
            "the macOS icon is not an ICNS")
    }

    @Test
    fun `the Windows icon carries every size Windows asks for`() {
        val bytes = File(icons, "forest.ico").readBytes()

        fun short(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun int(at: Int) = short(at) or (short(at + 2) shl 16)

        assertEquals(1, short(2), "ICO type must be 1")
        val count = short(4)
        assertEquals(6, count)

        val sizes = (0 until count).map { i ->
            val entry = 6 + i * 16
            val width = bytes[entry].toInt() and 0xFF
            val length = int(entry + 8)
            val offset = int(entry + 12)

            // Payloads are PNGs, which is what modern Windows and jpackage expect.
            assertContentEquals(
                byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
                bytes.copyOfRange(offset, offset + 4),
                "image $i is not a PNG",
            )
            assertTrue(offset + length <= bytes.size, "image $i runs past the end of the file")
            if (width == 0) 256 else width
        }
        assertEquals(listOf(16, 32, 48, 64, 128, 256), sizes)
    }

    @Test
    fun `the committed icons match what the drawing produces today`() {
        // Catches an icon left behind after the mark was edited.
        assertContentEquals(
            GenerateIcons.renderIcon(512),
            File(icons, "forest.png").readBytes(),
            "forest.png is stale — run ./gradlew :desktopApp:generateIcons",
        )
    }
}
