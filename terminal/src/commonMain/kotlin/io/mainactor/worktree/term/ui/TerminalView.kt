package io.mainactor.worktree.term.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import io.mainactor.worktree.term.Ascii
import io.mainactor.worktree.term.KeyEncoder
import io.mainactor.worktree.term.KeyModifiers
import io.mainactor.worktree.term.MouseButton
import io.mainactor.worktree.term.MouseEncoder
import io.mainactor.worktree.term.MouseEventKind
import io.mainactor.worktree.term.Modes
import io.mainactor.worktree.term.Selection
import io.mainactor.worktree.term.TerminalClipboard
import io.mainactor.worktree.term.TerminalKey
import io.mainactor.worktree.term.TerminalLine
import io.mainactor.worktree.term.TerminalPane
import io.mainactor.worktree.term.TerminalPosition
import io.mainactor.worktree.term.documentLine
import io.mainactor.worktree.term.documentLines
import io.mainactor.worktree.term.textBetween

/**
 * The font for a pane, at the size it should be on this screen.
 *
 * Separate from the view so the density is read once, in a composable, rather than being a
 * parameter every caller has to remember to pass — and getting it wrong is not subtle: the text
 * comes out at half size on every retina display.
 */
@Composable
fun rememberTerminalGlyphs(
    pointSize: Float = TerminalGlyphs.DEFAULT_SIZE,
    lineSpacing: Float = TerminalGlyphs.DEFAULT_LINE_SPACING,
): TerminalGlyphs {
    val density = LocalDensity.current.density
    return remember(pointSize, lineSpacing, density) {
        TerminalGlyphs(pointSize = pointSize, lineSpacing = lineSpacing, density = density)
    }
}

/**
 * A terminal pane, drawn by Compose rather than hosted from Swing.
 *
 * Being an ordinary composable is most of the point. The pane it replaces is a heavyweight Swing
 * component, and everything awkward about it follows from that: modals paint underneath it, its
 * focus lives in AWT where Compose cannot see it, shortcuts need a global key hook to be noticed at
 * all, and no test can render it off screen. None of that applies here.
 *
 * **Repainting is driven by frames, not by output.** The model's generation counter is compared
 * once per frame, so a program printing a megabyte causes one repaint rather than a thousand — the
 * flood is survived in the parser, which is fast, instead of in the renderer, which is not. While a
 * program holds synchronized output open the comparison is skipped, so its half-drawn screen is
 * never shown.
 *
 * **The pointer belongs to the program only when it asked for it**, and even then not while `Shift`
 * is held. That override is how every real terminal lets you select text inside a full-screen
 * program that has taken the mouse — without it, `vim` makes copying impossible.
 */
@Composable
fun TerminalView(
    pane: TerminalPane,
    focused: Boolean,
    modifier: Modifier = Modifier,
    glyphs: TerminalGlyphs = rememberTerminalGlyphs(),
    clipboard: TerminalClipboard = TerminalClipboard.None,
    onFocusGained: () -> Unit = {},
    /** A hyperlink the program printed with `OSC 8`, clicked. Opening it is the application's job. */
    onHyperlink: (String) -> Unit = {},
) {
    val painter = remember(glyphs) { TerminalPainter(glyphs) }
    val focusRequester = remember { FocusRequester() }
    val selection = remember(pane) { Selection() }
    var frame by remember { mutableStateOf(0) }
    var drawn by remember { mutableStateOf(-1) }
    var scrollOffset by remember(pane) { mutableStateOf(0) }
    var hoveredLink by remember(pane) { mutableStateOf(TerminalLine.NO_LINK) }

    fun repaint() {
        frame++
    }

    LaunchedEffect(pane) {
        while (true) {
            withFrameNanos { }
            val generation = pane.withModel { model ->
                if (model.modes.synchronizedOutput) drawn else model.generation
            }
            if (generation != drawn) {
                drawn = generation
                frame++
            }
        }
    }

    // Selecting a pane anywhere else — its header, a shortcut — has to move the caret too, or the
    // highlighted pane and the one taking keystrokes drift apart.
    LaunchedEffect(focused) {
        if (focused) runCatching { focusRequester.requestFocus() }
        // `DECSET 1004`: a program that asked to know about focus is told. Editors use it to stop
        // a blinking cursor in an inactive pane, and shells to redraw a dimmed prompt.
        val report = pane.withModel { model ->
            if (model.modes.focusReporting) (if (focused) "I" else "O") else null
        }
        if (report != null) pane.send(Ascii.CSI + report)
    }

    /** Where a pointer landed, in the document rather than on the screen. */
    fun positionOf(offset: Offset): TerminalPosition = pane.withModel { model ->
        val row = (offset.y / glyphs.cellHeight).toInt().coerceIn(0, model.rows - 1)
        val column = (offset.x / glyphs.cellWidth).toInt().coerceIn(0, model.columns)
        TerminalPosition(painter.firstVisibleLine(model, scrollOffset) + row, column)
    }

    fun copySelection() {
        val range = selection.range() ?: return
        val text = pane.withModel { it.buffer.textBetween(range.first, range.second) }
        if (text.isNotEmpty()) clipboard.write(text)
    }

    Canvas(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged { if (it.isFocused) onFocusGained() }
            // Preview, so the pane claims a key before Compose's own focus traversal does: `Tab`
            // belongs to the shell, not to the next widget.
            .onPreviewKeyEvent { event ->
                handleKey(event, pane, clipboard, selection, ::copySelection) {
                    // Typing means you want to be where the prompt is.
                    if (scrollOffset != 0) {
                        scrollOffset = 0
                        repaint()
                    }
                }
            }
            .pointerInput(pane, glyphs) {
                awaitPointerEventScope {
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val modifiers = modifiersOf(event.keyboardModifiers.isShiftPressed, event)
                        // Shift always takes the pointer back: it is how every terminal lets you
                        // select text inside a program that has claimed the mouse.
                        val reporting = pane.withModel { model ->
                            !modifiers.shift && (
                                model.modes.isDecSet(Modes.MOUSE_CLICK) ||
                                    model.modes.isDecSet(Modes.MOUSE_DRAG) ||
                                    model.modes.isDecSet(Modes.MOUSE_ANY)
                                )
                        }
                        val position = positionOf(change.position)
                        val link = pane.withModel { model ->
                            val row = (change.position.y / glyphs.cellHeight).toInt()
                            val column = (change.position.x / glyphs.cellWidth).toInt()
                            val line = model.buffer.documentLine(
                                painter.firstVisibleLine(model, scrollOffset) + row,
                            )
                            if (line != null && column in 0 until line.width) line.linkAt(column)
                            else TerminalLine.NO_LINK
                        }
                        if (link != hoveredLink) {
                            hoveredLink = link
                            repaint()
                        }
                        val cell = pane.withModel { model ->
                            val row = (change.position.y / glyphs.cellHeight).toInt()
                                .coerceIn(0, model.rows - 1)
                            val column = (change.position.x / glyphs.cellWidth).toInt()
                                .coerceIn(0, model.columns - 1)
                            column to row
                        }
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val delta = change.scrollDelta.y
                                if (delta != 0f) {
                                    val wheel = if (delta < 0) MouseButton.WHEEL_UP else MouseButton.WHEEL_DOWN
                                    val reported = if (reporting) {
                                        pane.withModel { model ->
                                            MouseEncoder.encode(
                                                MouseEventKind.PRESS, wheel,
                                                cell.first, cell.second, modifiers, model.modes,
                                            )
                                        }
                                    } else {
                                        null
                                    }
                                    if (reported != null) {
                                        // A program holding the mouse gets the wheel: scrolling our
                                        // own history under a full-screen program would show the
                                        // shell output it is painted over.
                                        repeat(WHEEL_LINES) { pane.send(reported) }
                                    } else {
                                        val maximum = pane.withModel { it.buffer.documentLines - it.rows }
                                        val next = (scrollOffset - (delta * WHEEL_LINES).toInt())
                                            .coerceIn(0, maxOf(maximum, 0))
                                        if (next != scrollOffset) {
                                            scrollOffset = next
                                            repaint()
                                        }
                                    }
                                }
                                change.consume()
                            }
                            PointerEventType.Press -> {
                                runCatching { focusRequester.requestFocus() }
                                val target = if (link != TerminalLine.NO_LINK) {
                                    pane.withModel { it.emulator.linkTarget(link) }
                                } else {
                                    null
                                }
                                if (target != null && !reporting) {
                                    onHyperlink(target)
                                    change.consume()
                                    continue
                                }
                                if (reporting) {
                                    sendMouse(pane, MouseEventKind.PRESS, cell, modifiers)
                                } else {
                                    dragging = true
                                    selection.start(position)
                                    repaint()
                                }
                                change.consume()
                            }
                            PointerEventType.Move -> {
                                if (reporting) {
                                    sendMouse(pane, MouseEventKind.MOVE, cell, modifiers)
                                } else if (dragging) {
                                    selection.extendTo(position)
                                    repaint()
                                }
                            }
                            PointerEventType.Release -> {
                                if (reporting) {
                                    sendMouse(pane, MouseEventKind.RELEASE, cell, modifiers)
                                } else if (dragging) {
                                    dragging = false
                                    selection.extendTo(position)
                                    // Copy on release rather than on a shortcut: a selection that
                                    // vanishes when you reach for the keyboard is no selection.
                                    copySelection()
                                    repaint()
                                }
                                change.consume()
                            }
                        }
                    }
                }
            }
            .onSizeChanged { size ->
                val columns = (size.width / glyphs.cellWidth).toInt()
                val rows = (size.height / glyphs.cellHeight).toInt()
                pane.resize(maxOf(columns, 1), maxOf(rows, 1))
            },
    ) {
        @Suppress("UNUSED_EXPRESSION")
        frame // the drawing depends on it, which is what makes a new generation repaint
        drawIntoCanvas { canvas ->
            pane.withModel { model ->
                painter.paint(
                    canvas = canvas.nativeCanvas,
                    model = model,
                    width = size.width,
                    height = size.height,
                    showCursor = focused,
                    scrollOffset = scrollOffset,
                    selection = selection,
                    hoveredLink = hoveredLink,
                )
            }
        }
    }
}

private const val WHEEL_LINES = 3

private fun sendMouse(
    pane: TerminalPane,
    kind: MouseEventKind,
    cell: Pair<Int, Int>,
    modifiers: KeyModifiers,
) {
    val text = pane.withModel { model ->
        MouseEncoder.encode(kind, MouseButton.LEFT, cell.first, cell.second, modifiers, model.modes)
    }
    if (text != null) pane.send(text)
}

private fun modifiersOf(shift: Boolean, event: PointerEvent) =
    KeyModifiers(
        shift = shift,
        alt = event.keyboardModifiers.isAltPressed,
        control = event.keyboardModifiers.isCtrlPressed,
        meta = event.keyboardModifiers.isMetaPressed,
    )

/**
 * Encodes a key press and sends it, or leaves the event alone.
 *
 * Returning false is how the window keeps its own shortcuts: anything with the platform's command
 * modifier is not the pane's to consume — except the two chords that belong to a terminal
 * everywhere, copy and paste.
 */
private fun handleKey(
    event: KeyEvent,
    pane: TerminalPane,
    clipboard: TerminalClipboard,
    selection: Selection,
    copy: () -> Unit,
    scrollToBottom: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val modifiers = KeyModifiers(
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
        control = event.isCtrlPressed,
        meta = event.isMetaPressed,
    )

    // Copy and paste, in both the platform spellings. Control-shift on Linux and Windows because a
    // bare `Ctrl+C` is the interrupt and always will be.
    val editing = modifiers.meta || (modifiers.control && modifiers.shift)
    if (editing) {
        when (event.key) {
            Key.C -> if (!selection.isEmpty) {
                copy()
                return true
            }
            Key.V -> {
                val text = clipboard.read()
                if (!text.isNullOrEmpty()) {
                    pane.send(pane.withModel { KeyEncoder.paste(text, it.modes) })
                    scrollToBottom()
                }
                return true
            }
        }
    }
    if (modifiers.meta) return false

    val special = terminalKeyOf(event.key)
    val text = if (special != null) {
        KeyEncoder.encode(special, modifiers, pane.withModel { it.modes })
    } else {
        val codePoint = event.utf16CodePoint
        if (codePoint == 0) return false
        KeyEncoder.encodeCharacter(codePoint, modifiers) ?: return false
    }
    scrollToBottom()
    pane.send(text)
    return true
}

private fun terminalKeyOf(key: Key): TerminalKey? = when (key) {
    Key.DirectionUp -> TerminalKey.ARROW_UP
    Key.DirectionDown -> TerminalKey.ARROW_DOWN
    Key.DirectionLeft -> TerminalKey.ARROW_LEFT
    Key.DirectionRight -> TerminalKey.ARROW_RIGHT
    Key.MoveHome -> TerminalKey.HOME
    Key.MoveEnd -> TerminalKey.END
    Key.PageUp -> TerminalKey.PAGE_UP
    Key.PageDown -> TerminalKey.PAGE_DOWN
    Key.Insert -> TerminalKey.INSERT
    Key.Delete -> TerminalKey.DELETE
    Key.Backspace -> TerminalKey.BACKSPACE
    Key.Tab -> TerminalKey.TAB
    Key.Enter, Key.NumPadEnter -> TerminalKey.ENTER
    Key.Escape -> TerminalKey.ESCAPE
    Key.F1 -> TerminalKey.F1
    Key.F2 -> TerminalKey.F2
    Key.F3 -> TerminalKey.F3
    Key.F4 -> TerminalKey.F4
    Key.F5 -> TerminalKey.F5
    Key.F6 -> TerminalKey.F6
    Key.F7 -> TerminalKey.F7
    Key.F8 -> TerminalKey.F8
    Key.F9 -> TerminalKey.F9
    Key.F10 -> TerminalKey.F10
    Key.F11 -> TerminalKey.F11
    Key.F12 -> TerminalKey.F12
    else -> null
}
