package io.mainactor.worktree.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import io.mainactor.worktree.ui.components.drawForestIcon
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes the application icons from the same drawing the app uses on screen.
 *
 * Run with `./gradlew :desktopApp:generateIcons`. The results are committed, so nobody has to run
 * this to build the app — it exists so the icons can be regenerated when the mark changes, and so
 * the mark has exactly one definition ([drawForestIcon]) rather than a drawing plus a stale export.
 *
 * It lives in the test source set on purpose: it is a tool, and has no business in the shipped jar.
 */
object GenerateIcons {

    /** Sizes Windows expects inside an `.ico`. */
    private val ICO_SIZES = listOf(16, 32, 48, 64, 128, 256)

    /** `.iconset` members `iconutil` expects, as name to pixel size. */
    private val ICONSET = listOf(
        "icon_16x16.png" to 16,
        "icon_16x16@2x.png" to 32,
        "icon_32x32.png" to 32,
        "icon_32x32@2x.png" to 64,
        "icon_128x128.png" to 128,
        "icon_128x128@2x.png" to 256,
        "icon_256x256.png" to 256,
        "icon_256x256@2x.png" to 512,
        "icon_512x512.png" to 512,
        "icon_512x512@2x.png" to 1024,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.firstOrNull() ?: "icons").apply { mkdirs() }

        // Linux takes a plain PNG.
        File(outDir, "forest.png").writeBytes(renderIcon(512))
        println("wrote ${File(outDir, "forest.png")}")

        writeIco(File(outDir, "forest.ico"))
        writeIcns(File(outDir, "forest.icns"))
    }

    /** The icon at [side]×[side], as PNG bytes. */
    @OptIn(ExperimentalComposeUiApi::class)
    fun renderIcon(side: Int): ByteArray {
        val scene = ImageComposeScene(side, side, Density(1f), Dispatchers.Unconfined) {
            Canvas(Modifier.fillMaxSize()) { drawForestIcon(size.minDimension) }
        }
        val image = try {
            scene.render()
            scene.render()
        } finally {
            scene.close()
        }
        return image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: error("could not encode the icon at ${side}px")
    }

    /**
     * Writes a Windows `.ico`.
     *
     * Hand-rolled because neither ImageMagick nor Pillow is a reasonable thing to require for a
     * build. The container is simple: a directory header, one 16-byte entry per image, then the
     * images themselves — PNG payloads, which Windows has accepted since Vista and which jpackage
     * passes through untouched.
     */
    fun writeIco(target: File) {
        val images = ICO_SIZES.map(::renderIcon)
        val out = ByteArrayOutputStream()

        out.writeShortLe(0)                 // reserved
        out.writeShortLe(1)                 // type: icon
        out.writeShortLe(images.size)

        var offset = 6 + images.size * 16
        ICO_SIZES.forEachIndexed { index, side ->
            // 256 is stored as 0; the field is a single byte.
            out.write(if (side >= 256) 0 else side)
            out.write(if (side >= 256) 0 else side)
            out.write(0)                    // palette size, 0 for true colour
            out.write(0)                    // reserved
            out.writeShortLe(1)             // colour planes
            out.writeShortLe(32)            // bits per pixel
            out.writeIntLe(images[index].size)
            out.writeIntLe(offset)
            offset += images[index].size
        }
        images.forEach(out::write)

        target.writeBytes(out.toByteArray())
        println("wrote $target (${ICO_SIZES.joinToString()} px)")
    }

    /**
     * Writes a macOS `.icns` via `iconutil`, which only exists on macOS.
     *
     * Elsewhere the step is skipped rather than failed: the file is committed, so a build on Linux
     * or Windows still packages the right icon.
     */
    fun writeIcns(target: File) {
        val iconset = File(target.parentFile, "forest.iconset")
        if (!File("/usr/bin/iconutil").canExecute()) {
            println("skipped $target — iconutil is macOS-only, keeping the committed file")
            return
        }

        iconset.deleteRecursively()
        iconset.mkdirs()
        ICONSET.forEach { (name, side) -> File(iconset, name).writeBytes(renderIcon(side)) }

        val result = ProcessBuilder("iconutil", "-c", "icns", iconset.path, "-o", target.path)
            .redirectErrorStream(true)
            .start()
        val output = result.inputStream.readBytes().decodeToString()
        check(result.waitFor() == 0) { "iconutil failed: $output" }

        iconset.deleteRecursively()
        println("wrote $target")
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
