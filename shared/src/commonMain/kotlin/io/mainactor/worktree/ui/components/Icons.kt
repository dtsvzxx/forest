package io.mainactor.worktree.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn directly on a [Canvas].
 *
 * Hand-drawing a dozen 16dp glyphs keeps the app free of an icon-font or icon-library dependency
 * and lets every stroke land on the same 1dp grid the IDE toolbars use, which is most of what
 * makes a toolbar read as "native" at this size.
 */
private val DefaultIconSize = 14.dp

@Composable
fun IdeIcon(
    icon: IconKind,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = DefaultIconSize,
) {
    Canvas(modifier.size(size)) {
        drawIcon(icon, tint, this.size.minDimension)
    }
}

enum class IconKind {
    PLUS, MINUS, REFRESH, ARROW_DOWN, ARROW_UP, FETCH, COMMIT, MERGE, REBASE, BRANCH,
    FOLDER, TERMINAL, CLOSE, CHEVRON_DOWN, LOCK, WARNING, CHECK, STAGE, UNSTAGE, REVERT, HOME,
    SPLIT_RIGHT, SPLIT_DOWN, GOTO, SETTINGS,
}

internal fun DrawScope.drawIcon(icon: IconKind, tint: Color, s: Float) {
    val w = s * 0.09f
    val stroke = Stroke(width = w.coerceAtLeast(1f), cap = StrokeCap.Round)
    fun p(x: Float, y: Float) = Offset(s * x, s * y)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)

    when (icon) {
        IconKind.PLUS -> {
            line(0.5f, 0.18f, 0.5f, 0.82f)
            line(0.18f, 0.5f, 0.82f, 0.5f)
        }
        IconKind.MINUS -> line(0.18f, 0.5f, 0.82f, 0.5f)
        // Sliders rather than a cogwheel: at 14dp a cogwheel's teeth are one pixel of mush.
        IconKind.SETTINGS -> {
            listOf(0.28f to 0.62f, 0.5f to 0.36f, 0.72f to 0.7f).forEach { (y, knob) ->
                line(0.16f, y, 0.84f, y)
                drawCircle(tint, radius = s * 0.09f, center = p(knob, y), style = stroke)
            }
        }
        IconKind.CLOSE -> {
            line(0.24f, 0.24f, 0.76f, 0.76f)
            line(0.76f, 0.24f, 0.24f, 0.76f)
        }
        IconKind.CHECK -> {
            line(0.2f, 0.52f, 0.42f, 0.74f)
            line(0.42f, 0.74f, 0.8f, 0.26f)
        }
        IconKind.REFRESH -> {
            drawArc(
                color = tint,
                startAngle = 60f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(s * 0.2f, s * 0.2f),
                size = Size(s * 0.6f, s * 0.6f),
                style = stroke,
            )
            val head = Path().apply {
                moveTo(s * 0.74f, s * 0.28f)
                lineTo(s * 0.9f, s * 0.34f)
                lineTo(s * 0.72f, s * 0.46f)
                close()
            }
            drawPath(head, tint)
        }
        IconKind.ARROW_DOWN -> {
            line(0.5f, 0.16f, 0.5f, 0.74f)
            line(0.26f, 0.52f, 0.5f, 0.8f)
            line(0.74f, 0.52f, 0.5f, 0.8f)
        }
        IconKind.ARROW_UP -> {
            line(0.5f, 0.84f, 0.5f, 0.26f)
            line(0.26f, 0.48f, 0.5f, 0.2f)
            line(0.74f, 0.48f, 0.5f, 0.2f)
        }
        IconKind.FETCH -> {
            // A cloud-ish arc with a down arrow: "bring refs down without touching my tree".
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.12f, s * 0.16f),
                size = Size(s * 0.76f, s * 0.6f),
                style = stroke,
            )
            line(0.5f, 0.4f, 0.5f, 0.86f)
            line(0.32f, 0.68f, 0.5f, 0.88f)
            line(0.68f, 0.68f, 0.5f, 0.88f)
        }
        IconKind.COMMIT -> {
            drawCircle(tint, radius = s * 0.19f, center = Offset(s * 0.5f, s * 0.5f), style = stroke)
            line(0.02f, 0.5f, 0.29f, 0.5f)
            line(0.71f, 0.5f, 0.98f, 0.5f)
        }
        IconKind.MERGE -> {
            line(0.28f, 0.12f, 0.28f, 0.88f)
            drawCircle(tint, radius = s * 0.12f, center = Offset(s * 0.72f, s * 0.24f), style = stroke)
            drawCircle(tint, radius = s * 0.12f, center = Offset(s * 0.28f, s * 0.78f), style = stroke)
            val curve = Path().apply {
                moveTo(s * 0.72f, s * 0.36f)
                cubicTo(s * 0.72f, s * 0.6f, s * 0.4f, s * 0.55f, s * 0.32f, s * 0.68f)
            }
            drawPath(curve, tint, style = stroke)
        }
        IconKind.REBASE -> {
            line(0.26f, 0.88f, 0.26f, 0.34f)
            line(0.12f, 0.46f, 0.26f, 0.3f)
            line(0.4f, 0.46f, 0.26f, 0.3f)
            drawCircle(tint, radius = s * 0.11f, center = Offset(s * 0.72f, s * 0.3f), style = stroke)
            drawCircle(tint, radius = s * 0.11f, center = Offset(s * 0.72f, s * 0.72f), style = stroke)
            line(0.72f, 0.41f, 0.72f, 0.61f)
        }
        IconKind.BRANCH -> {
            drawCircle(tint, radius = s * 0.12f, center = Offset(s * 0.3f, s * 0.22f), style = stroke)
            drawCircle(tint, radius = s * 0.12f, center = Offset(s * 0.3f, s * 0.8f), style = stroke)
            drawCircle(tint, radius = s * 0.12f, center = Offset(s * 0.74f, s * 0.22f), style = stroke)
            line(0.3f, 0.34f, 0.3f, 0.68f)
            val curve = Path().apply {
                moveTo(s * 0.74f, s * 0.34f)
                cubicTo(s * 0.74f, s * 0.6f, s * 0.46f, s * 0.5f, s * 0.34f, s * 0.7f)
            }
            drawPath(curve, tint, style = stroke)
        }
        IconKind.FOLDER -> {
            val path = Path().apply {
                moveTo(s * 0.1f, s * 0.78f)
                lineTo(s * 0.1f, s * 0.26f)
                lineTo(s * 0.42f, s * 0.26f)
                lineTo(s * 0.5f, s * 0.38f)
                lineTo(s * 0.9f, s * 0.38f)
                lineTo(s * 0.9f, s * 0.78f)
                close()
            }
            drawPath(path, tint, style = stroke)
        }
        IconKind.HOME -> {
            val path = Path().apply {
                moveTo(s * 0.12f, s * 0.5f)
                lineTo(s * 0.5f, s * 0.16f)
                lineTo(s * 0.88f, s * 0.5f)
            }
            drawPath(path, tint, style = stroke)
            drawRect(
                tint,
                topLeft = Offset(s * 0.24f, s * 0.5f),
                size = Size(s * 0.52f, s * 0.34f),
                style = stroke,
            )
        }
        IconKind.TERMINAL -> {
            drawRect(
                tint,
                topLeft = Offset(s * 0.08f, s * 0.16f),
                size = Size(s * 0.84f, s * 0.68f),
                style = stroke,
            )
            line(0.22f, 0.36f, 0.4f, 0.5f)
            line(0.4f, 0.5f, 0.22f, 0.64f)
            line(0.5f, 0.66f, 0.76f, 0.66f)
        }
        IconKind.GOTO -> {
            // A frame with an arrow leaving it: take this somewhere else in the app.
            val frame = Path().apply {
                moveTo(s * 0.52f, s * 0.16f)
                lineTo(s * 0.14f, s * 0.16f)
                lineTo(s * 0.14f, s * 0.84f)
                lineTo(s * 0.82f, s * 0.84f)
                lineTo(s * 0.82f, s * 0.46f)
            }
            drawPath(frame, tint, style = stroke)
            line(0.46f, 0.52f, 0.86f, 0.14f)
            line(0.6f, 0.14f, 0.86f, 0.14f)
            line(0.86f, 0.14f, 0.86f, 0.4f)
        }
        // A frame divided the way the split will divide it, with the new half filled in.
        IconKind.SPLIT_RIGHT -> {
            drawRect(
                tint,
                topLeft = Offset(s * 0.12f, s * 0.16f),
                size = Size(s * 0.76f, s * 0.68f),
                style = stroke,
            )
            line(0.5f, 0.16f, 0.5f, 0.84f)
            drawRect(tint.copy(alpha = 0.45f), topLeft = Offset(s * 0.53f, s * 0.19f), size = Size(s * 0.32f, s * 0.62f))
        }
        IconKind.SPLIT_DOWN -> {
            drawRect(
                tint,
                topLeft = Offset(s * 0.12f, s * 0.16f),
                size = Size(s * 0.76f, s * 0.68f),
                style = stroke,
            )
            line(0.12f, 0.5f, 0.88f, 0.5f)
            drawRect(tint.copy(alpha = 0.45f), topLeft = Offset(s * 0.15f, s * 0.53f), size = Size(s * 0.7f, s * 0.28f))
        }
        IconKind.CHEVRON_DOWN -> {
            line(0.24f, 0.4f, 0.5f, 0.66f)
            line(0.5f, 0.66f, 0.76f, 0.4f)
        }
        IconKind.LOCK -> {
            drawRect(
                tint,
                topLeft = Offset(s * 0.22f, s * 0.46f),
                size = Size(s * 0.56f, s * 0.38f),
                style = stroke,
            )
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.32f, s * 0.2f),
                size = Size(s * 0.36f, s * 0.4f),
                style = stroke,
            )
        }
        IconKind.WARNING -> {
            val path = Path().apply {
                moveTo(s * 0.5f, s * 0.14f)
                lineTo(s * 0.92f, s * 0.84f)
                lineTo(s * 0.08f, s * 0.84f)
                close()
            }
            drawPath(path, tint, style = stroke)
            line(0.5f, 0.4f, 0.5f, 0.6f)
            drawCircle(tint, radius = s * 0.05f, center = Offset(s * 0.5f, s * 0.72f))
        }
        IconKind.STAGE -> {
            line(0.5f, 0.82f, 0.5f, 0.24f)
            line(0.28f, 0.44f, 0.5f, 0.2f)
            line(0.72f, 0.44f, 0.5f, 0.2f)
            line(0.16f, 0.9f, 0.84f, 0.9f)
        }
        IconKind.UNSTAGE -> {
            line(0.5f, 0.18f, 0.5f, 0.76f)
            line(0.28f, 0.56f, 0.5f, 0.8f)
            line(0.72f, 0.56f, 0.5f, 0.8f)
            line(0.16f, 0.1f, 0.84f, 0.1f)
        }
        IconKind.REVERT -> {
            drawArc(
                color = tint,
                startAngle = 300f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(s * 0.2f, s * 0.2f),
                size = Size(s * 0.6f, s * 0.6f),
                style = stroke,
            )
            val head = Path().apply {
                moveTo(s * 0.26f, s * 0.28f)
                lineTo(s * 0.1f, s * 0.34f)
                lineTo(s * 0.28f, s * 0.46f)
                close()
            }
            drawPath(head, tint)
        }
    }
}
