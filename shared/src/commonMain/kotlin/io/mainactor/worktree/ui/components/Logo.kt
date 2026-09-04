package io.mainactor.worktree.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Forest mark: a git branch graph shaped like a tree.
 *
 * Three trees, each a trunk with branches arcing off it and a commit node on every tip — the same
 * drawing reads as a stand of trees and as the branch graphs the app is about.
 *
 * Drawn in code for the same reason the toolbar glyphs are (see [drawIcon]): one source paints it
 * at 16px in a title bar and at 1024px in an installer, with no assets to keep in step. It does
 * *not* reuse the glyph stroke ratio, though — `s * 0.09` is a 92px stroke at 1024, far too heavy
 * for a mark that has to hold detail.
 */
private const val MARK_STROKE = 0.05f

/** Node radius, as a fraction of the mark's side. */
private const val NODE_RADIUS = 0.05f

/** New UI green, the colour the app already uses for "added". */
val ForestGreen = Color(0xFF5FAD65)

/** The plate an app icon sits on: the New UI panel grey. */
val ForestPlate = Color(0xFF2B2D30)

/**
 * Draws the mark alone, on whatever is behind it, filling a [s]×[s] square.
 *
 * Coordinates are fractions of [s], so the shape is resolution-independent by construction.
 */
fun DrawScope.drawForestMark(s: Float, color: Color = ForestGreen) {
    val width = (s * MARK_STROKE).coerceAtLeast(1f)

    // One tree, drawn large: four pairs of branches plus the crown.
    tree(s, cx = 0.5f, baseY = 0.94f, topY = 0.13f, spread = 0.29f, tiers = 4,
        color = color, width = width, nodeRadius = s * NODE_RADIUS)

    // Ground it, so the trunk is planted rather than floating.
    drawLine(
        color = color,
        start = Offset(s * 0.31f, s * 0.94f),
        end = Offset(s * 0.69f, s * 0.94f),
        strokeWidth = width,
        cap = StrokeCap.Round,
    )
}

/**
 * One tree: a trunk, [tiers] pairs of branches, and a commit node on every tip.
 *
 * Branches lower on the trunk reach further out, which gives the crown its taper and keeps the tips
 * from colliding at small sizes.
 */
private fun DrawScope.tree(
    s: Float,
    cx: Float,
    baseY: Float,
    topY: Float,
    spread: Float,
    tiers: Int,
    color: Color,
    width: Float,
    nodeRadius: Float,
) {
    val stroke = Stroke(width = width, cap = StrokeCap.Round)
    val height = baseY - topY
    val crownGap = height * 0.06f

    drawLine(
        color = color,
        start = Offset(s * cx, s * baseY),
        end = Offset(s * (cx), s * (topY + crownGap)),
        strokeWidth = width,
        cap = StrokeCap.Round,
    )

    repeat(tiers) { level ->
        // 0 at the top tier, 1 at the bottom one.
        val depth = if (tiers == 1) 0.5f else level / (tiers - 1f)
        // The top pair starts well below the apex: any closer and its nodes merge with the crown
        // into a single three-lobed blob.
        val attachY = topY + height * (0.32f + 0.56f * depth)
        val reach = spread * (0.42f + 0.58f * depth)
        val tipY = attachY - height * 0.19f

        // Both sides at the same height. Staggering them reads as a grown tree, but a mark this
        // small is read as a shape rather than a specimen, and the shape wants to be symmetrical.
        listOf(-1f, 1f).forEach { side ->
            val tipX = cx + side * reach
            drawPath(
                Path().apply {
                    moveTo(s * cx, s * attachY)
                    cubicTo(
                        s * (cx + side * reach * 0.18f), s * (attachY - height * 0.05f),
                        s * (cx + side * reach * 0.7f), s * (tipY + height * 0.03f),
                        s * tipX, s * tipY,
                    )
                },
                color,
                style = stroke,
            )
            drawCircle(color, radius = nodeRadius, center = Offset(s * tipX, s * tipY))
        }
    }

    drawCircle(color, radius = nodeRadius * 1.2f, center = Offset(s * cx, s * topY))
}

/**
 * The app-icon form: the mark on a rounded plate.
 *
 * The mark is inset well inside the plate — app icons are read at a glance among others, and macOS
 * in particular expects the artwork to breathe rather than run to the edge.
 */
fun DrawScope.drawForestIcon(s: Float) {
    val plateInset = s * 0.06f
    val plateSide = s - plateInset * 2
    drawRoundRect(
        color = ForestPlate,
        topLeft = Offset(plateInset, plateInset),
        size = Size(plateSide, plateSide),
        cornerRadius = CornerRadius(s * 0.22f, s * 0.22f),
    )

    val markInset = s * 0.17f
    translate(left = markInset, top = markInset) {
        drawForestMark(s - markInset * 2)
    }
}

/** The mark on its own, for use inside the app. */
@Composable
fun ForestLogo(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = ForestGreen) {
    Canvas(modifier.size(size)) { drawForestMark(this.size.minDimension, color) }
}

/**
 * The app icon as a [Painter], which is what `Window(icon = …)` takes.
 *
 * Nothing is rasterised ahead of time: the window asks for whatever size it needs and the mark is
 * drawn at exactly that size.
 */
class ForestIconPainter : Painter() {

    override val intrinsicSize: Size = Size(ICON_SIDE, ICON_SIDE)

    override fun DrawScope.onDraw() {
        drawForestIcon(size.minDimension)
    }

    private companion object {
        const val ICON_SIDE = 512f
    }
}
