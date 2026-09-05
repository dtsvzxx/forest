package io.mainactor.worktree.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the window's own frame still occupies once its title bar is gone.
 *
 * macOS is the only platform this has anything to say about, and it is set from the platform layer
 * for the same reason [AgentShortcuts] is: the window is created there, and only there is it known
 * that the title bar was hidden. Everything here is inert by default, so the off-screen render
 * tests and the other two platforms draw the toolbar exactly as before.
 */
object WindowChrome {

    /**
     * The strip at the top-left the platform draws its close/minimise/zoom buttons into.
     *
     * The buttons belong to the window server and cannot be moved, so the toolbar has to start
     * to the right of them; the value is the whole distance from the window's left edge, not a
     * gap to add to the padding already there.
     */
    var controlsWidth: Dp by mutableStateOf(0.dp)

    /**
     * Begins moving the window with the pointer, or null while the title bar still does that.
     *
     * The press is noticed here because the toolbar now covers the strip the title bar used to
     * own; the drag itself belongs to the platform, which is the only side that can see where the
     * pointer is on the screen rather than inside the window.
     */
    var onDragStart: (() -> Unit)? = null

    /** Double-clicking the bar, which on a title bar zooms the window. */
    var onToggleZoom: (() -> Unit)? = null
}

/**
 * Makes a bar behave like the title bar it replaced: drag to move, double-click to zoom.
 *
 * A press consumed by something inside the bar — any button — never reaches this, so the controls
 * keep working and only the bar's own background drags the window.
 */
@Composable
fun Modifier.windowHandle(): Modifier {
    if (WindowChrome.onDragStart == null && WindowChrome.onToggleZoom == null) return this
    return pointerInput(Unit) {
        detectTapGestures(
            onPress = { WindowChrome.onDragStart?.invoke() },
            onDoubleTap = { WindowChrome.onToggleZoom?.invoke() },
        )
    }
}
