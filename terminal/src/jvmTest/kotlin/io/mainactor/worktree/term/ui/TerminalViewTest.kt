package io.mainactor.worktree.term.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import io.mainactor.worktree.term.TerminalModel
import io.mainactor.worktree.term.TerminalPane
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A pane with no process behind it: the model is fed by the test and input is recorded. */
private class FakePane(val model: TerminalModel) : TerminalPane {
    val sent = mutableListOf<String>()
    var lastSize: Pair<Int, Int>? = null

    override fun <T> withModel(block: (TerminalModel) -> T): T = block(model)

    override fun send(text: String) {
        sent += text
    }

    override fun resize(columns: Int, rows: Int) {
        lastSize = columns to rows
        model.resize(columns, rows)
    }
}

/**
 * Renders the terminal off screen.
 *
 * The pane this replaces is a heavyweight Swing component, and `AppRenderTest` has always had to
 * stand a coloured box in for it. That this test can exist at all is the point of the rewrite as
 * much as anything else is: a layout that throws at measure time, a font that resolves to nothing,
 * a painter that draws off the canvas are all caught here, without a display.
 */
class TerminalViewTest {

    private val escape = 27.toChar().toString()

    private fun scene(pane: FakePane, width: Int = 600, height: Int = 300, density: Float = 1f) =
        ImageComposeScene(width, height, Density(density), Dispatchers.Unconfined) {
            TerminalView(pane = pane, focused = true, modifier = Modifier.fillMaxSize())
        }

    @Test
    fun `a screen of coloured text draws`() {
        val model = TerminalModel(80, 20)
        model.feed("${escape}[1;32mgreen and bold${escape}[0m\r\n")
        model.feed("${escape}[38;2;255;128;0mtruecolour${escape}[0m plain\r\n")
        model.feed("${escape}[4munderlined${escape}[0m ${escape}[7mreversed${escape}[0m\r\n")
        model.feed("中文 and 🌲 wide characters\r\n")
        val pane = FakePane(model)

        val scene = scene(pane)
        val image = try {
            scene.render()
            scene.render()
        } finally {
            scene.close()
        }

        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty(), "the scene produced no image")
        File("build/reports/terminal-view.png").apply { parentFile?.mkdirs() }.writeBytes(png)

        val pixels = image.peekPixels()!!.buffer.bytes
        val colours = HashSet<Int>()
        var i = 0
        while (i + 3 < pixels.size) {
            colours += (pixels[i].toInt() and 0xFF shl 16) or
                (pixels[i + 1].toInt() and 0xFF shl 8) or (pixels[i + 2].toInt() and 0xFF)
            if (colours.size > 8) break
            i += 4 * 37
        }
        assertTrue(colours.size > 8, "the terminal rendered as a flat fill")
    }

    /**
     * The same pane on a screen with twice the pixels holds the same text, drawn twice as large.
     *
     * The check that would have caught text at half size: on a retina display a font size quoted in
     * points is twice as many pixels, and a renderer that hands Skia the point size directly draws
     * everything small enough to notice and hard enough to explain.
     */
    @Test
    fun `a denser screen draws the same text larger, not more of it`() {
        // Sized close to what the pane will ask for, so the resize that follows the first layout
        // grows the screen rather than pushing the text into history. Feeding afterwards would not
        // show: the view repaints on a frame, and an off-screen scene produces none between renders.
        val pane = FakePane(TerminalModel(40, 8))
        pane.model.feed("the quick brown fox")

        val scene = scene(pane, width = 600, height = 300, density = 2f)
        val image = try {
            scene.render()
            scene.render()
        } finally {
            scene.close()
        }
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes!!
        File("build/reports/terminal-view-retina.png").apply { parentFile?.mkdirs() }.writeBytes(png)

        // Twice the pixels per cell means half as many cells across the same canvas.
        val columns = pane.lastSize!!.first
        assertTrue(columns in 30..50, "expected about forty columns at density 2, got $columns")
    }

    /** The pane tells the emulator and the child how many cells it actually has room for. */
    @Test
    fun `laying the pane out sets the size in cells`() {
        val pane = FakePane(TerminalModel(80, 24))
        val scene = scene(pane, width = 600, height = 300)
        try {
            scene.render()
        } finally {
            scene.close()
        }
        val size = pane.lastSize
        assertTrue(size != null, "the pane was never given a size")
        assertTrue(size.first in 40..200, "unexpected column count: ${size.first}")
        assertTrue(size.second in 10..40, "unexpected row count: ${size.second}")
        assertEquals(size.first, pane.model.columns)
        assertEquals(size.second, pane.model.rows)
    }
}
