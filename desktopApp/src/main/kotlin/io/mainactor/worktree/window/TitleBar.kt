package io.mainactor.worktree.window

import io.mainactor.worktree.platform.Os
import java.awt.AWTEvent
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.JRootPane

/**
 * Hides the macOS title bar and leaves its close/minimise/zoom buttons behind.
 *
 * Three client properties, and each does a separate part of it: `fullWindowContent` lets the
 * content pane reach up into the strip the title bar occupied, `transparentTitleBar` stops AppKit
 * painting that bar and the hairline under it over the content, and `windowTitleVisible` removes
 * the centred title text. The buttons are drawn by the window server rather than by the bar, so
 * they survive all three — which is also why [io.mainactor.worktree.ui.WindowChrome.controlsWidth]
 * then has to hold the toolbar out from under them.
 *
 * This is not `undecorated = true`: an undecorated window loses the buttons along with the bar,
 * and with them the native resize edges, the shadow and the rounded corners.
 */
fun hideTitleBar(rootPane: JRootPane) {
    if (!Os.isMac) return
    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
}

/**
 * Moves [window] with the pointer, from a press until the button comes up.
 *
 * Dragging the window used to be the title bar's job; with the content extending into that strip
 * the toolbar owns it and has to move the window itself.
 *
 * The position is computed from the pointer's *screen* coordinates against where the drag started,
 * never accumulated from per-event deltas. A delta is measured against a window that has already
 * moved under the pointer, so it drifts — and should AppKit turn out to still drag the window
 * underneath us, an absolute target is the same place it would arrive at rather than twice the
 * distance.
 *
 * The listener is global rather than attached to the window: Compose's canvas is what AWT delivers
 * mouse events to, and a listener on the window itself would never see them — nor the part of the
 * drag that leaves the window, which is most of a drag that moves it.
 */
class WindowDrag(private val window: Window) {
    private var listener: AWTEventListener? = null

    fun start() {
        if (listener != null) return
        val pointerStart = MouseInfo.getPointerInfo()?.location ?: return
        val windowStart = window.location
        val listener = AWTEventListener { event ->
            when (event.id) {
                MouseEvent.MOUSE_DRAGGED -> moveTo(pointerStart, windowStart)
                MouseEvent.MOUSE_RELEASED -> stop()
            }
        }
        this.listener = listener
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )
    }

    private fun moveTo(pointerStart: Point, windowStart: Point) {
        val now = MouseInfo.getPointerInfo()?.location ?: return
        window.setLocation(
            windowStart.x + (now.x - pointerStart.x),
            windowStart.y + (now.y - pointerStart.y),
        )
    }

    private fun stop() {
        listener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        listener = null
    }
}
