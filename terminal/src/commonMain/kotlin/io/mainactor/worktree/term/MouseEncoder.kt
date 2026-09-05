package io.mainactor.worktree.term

enum class MouseButton { LEFT, MIDDLE, RIGHT, WHEEL_UP, WHEEL_DOWN }

enum class MouseEventKind { PRESS, RELEASE, MOVE }

/**
 * Turns a pointer event into the bytes a program that asked for the mouse expects.
 *
 * A terminal only reports the mouse when a program has asked, and *which* events it reports depends
 * on which mode was asked for — click only, click and drag, or every movement. Reporting more than
 * was asked for is not harmless: a program in click mode that receives motion reports reads them as
 * gibberish keystrokes.
 *
 * The encodings are a museum. The original packs coordinates into single bytes offset by 32, which
 * silently breaks past column 223 — the reason `SGR` exists, and the reason a program that turns it
 * on gets it back the same way. `Modes` decides; nothing here guesses.
 */
object MouseEncoder {

    /**
     * The bytes for one event, or null when the program did not ask for it.
     *
     * [column] and [row] are zero-based; the wire format is one-based, which is exactly the kind of
     * off-by-one that puts a program's menu highlight one row above the pointer.
     */
    fun encode(
        kind: MouseEventKind,
        button: MouseButton,
        column: Int,
        row: Int,
        modifiers: KeyModifiers,
        modes: Modes,
    ): String? {
        if (!reports(kind, button, modes)) return null

        var code = when (button) {
            MouseButton.LEFT -> 0
            MouseButton.MIDDLE -> 1
            MouseButton.RIGHT -> 2
            MouseButton.WHEEL_UP -> 64
            MouseButton.WHEEL_DOWN -> 65
        }
        if (modifiers.shift) code += 4
        if (modifiers.alt) code += 8
        if (modifiers.control) code += 16
        if (kind == MouseEventKind.MOVE) code += 32

        val x = column + 1
        val y = row + 1
        return when {
            modes.isDecSet(Modes.MOUSE_SGR) || modes.isDecSet(Modes.MOUSE_SGR_PIXELS) ->
                "$CSI<$code;$x;$y${if (kind == MouseEventKind.RELEASE) 'm' else 'M'}"

            modes.isDecSet(Modes.MOUSE_URXVT) ->
                "$CSI${releaseCode(kind, code) + 32};$x;$y" + "M"

            else -> {
                // The original form: three bytes, each offset by 32, and unusable past column 223.
                if (x > X10_LIMIT || y > X10_LIMIT) return null
                "${CSI}M" + (releaseCode(kind, code) + 32).toChar() + (x + 32).toChar() + (y + 32).toChar()
            }
        }
    }

    /** Only `SGR` can say which button was let go; the older forms report a nameless release. */
    private fun releaseCode(kind: MouseEventKind, code: Int): Int =
        if (kind == MouseEventKind.RELEASE) (code and 0xFC) or 3 else code

    private fun reports(kind: MouseEventKind, button: MouseButton, modes: Modes): Boolean {
        val click = modes.isDecSet(Modes.MOUSE_CLICK)
        val drag = modes.isDecSet(Modes.MOUSE_DRAG)
        val any = modes.isDecSet(Modes.MOUSE_ANY)
        if (!click && !drag && !any) return false
        val wheel = button == MouseButton.WHEEL_UP || button == MouseButton.WHEEL_DOWN
        return when (kind) {
            MouseEventKind.PRESS -> true
            MouseEventKind.RELEASE -> !wheel
            MouseEventKind.MOVE -> drag || any
        }
    }

    private val CSI = Ascii.CSI

    /** Past this a coordinate does not fit in one offset byte, and the report is dropped instead. */
    private const val X10_LIMIT = 223
}
