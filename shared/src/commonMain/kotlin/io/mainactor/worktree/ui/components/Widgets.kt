package io.mainactor.worktree.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mainactor.worktree.ui.theme.CodeTextStyle
import io.mainactor.worktree.ui.theme.Dimens
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * Flat square icon button, the kind that fills IDE tool-window headers.
 *
 * [tooltip] is not decoration: at this size an icon alone rarely says what a git action will do,
 * so every one of these carries a label that appears on hover.
 */
@Composable
fun ToolButton(
    icon: IconKind,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    detail: String? = null,
) {
    val colors = LocalWorktreeColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip(tooltip, detail) {
        Box(
            modifier = modifier
                .size(Dimens.iconButton)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hovered && enabled) colors.hoverOverlay else Color.Transparent)
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            IdeIcon(
                icon = icon,
                tint = when {
                    !enabled -> colors.textDisabled
                    tint != null -> tint
                    else -> colors.text
                },
            )
        }
    }
}

/**
 * Hover tooltip, styled like the IDE's: a dark card just below the control, after a short delay.
 *
 * A blank [text] disables it, so callers can pass an optional label without branching.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip(
    text: String,
    detail: String? = null,
    content: @Composable () -> Unit,
) {
    if (text.isBlank()) {
        content()
        return
    }
    val colors = LocalWorktreeColors.current
    TooltipArea(
        tooltip = {
            Column(
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.panelAlt)
                    .border(1.dp, colors.selectionInactive, RoundedCornerShape(6.dp))
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(text, color = colors.text, style = MaterialTheme.typography.bodySmall)
                // The exact command the button runs: this app never hides what it does to a repo.
                if (detail != null) {
                    Text(detail, color = colors.textDim, style = CodeTextStyle)
                }
            }
        },
        delayMillis = TOOLTIP_DELAY_MS,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomCenter,
            alignment = Alignment.BottomCenter,
            offset = DpOffset(0.dp, 4.dp),
        ),
        content = content,
    )
}

/** Matches the IDE's own hover delay closely enough not to feel twitchy. */
private const val TOOLTIP_DELAY_MS = 600

/** Text button with the IntelliJ "default" (accent) and plain variants. */
@Composable
fun IdeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    icon: IconKind? = null,
) {
    val colors = LocalWorktreeColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        !enabled -> Color.Transparent
        primary && hovered -> colors.link
        primary -> colors.accent
        hovered -> colors.hover
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> colors.textDisabled
        primary -> Color.White
        else -> colors.text
    }
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(Dimens.arc))
            .background(background)
            .border(
                width = 1.dp,
                color = when {
                    primary -> Color.Transparent
                    enabled -> colors.controlBorder
                    else -> colors.separator
                },
                shape = RoundedCornerShape(Dimens.arc),
            )
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) IdeIcon(icon, content)
        Text(text, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Header strip of a tool window: a title, optional tabs on the left, actions on the right. */
@Composable
fun ToolWindowHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: RowScopeActions = {},
) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.tabHeight)
            .background(colors.panel)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            actions()
        }
    }
}

typealias RowScopeActions = @Composable () -> Unit

/** The 1px rules the IDE uses between panes and headers. */
@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    color: Color = LocalWorktreeColors.current.border,
) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun VerticalDivider(
    modifier: Modifier = Modifier,
    color: Color = LocalWorktreeColors.current.border,
) {
    Box(modifier.fillMaxHeight().width(1.dp).background(color))
}

/** A row in a tree/list, with IDE selection and hover behaviour. */
@Composable
fun ListRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = Dimens.rowHeight,
    padding: PaddingValues = PaddingValues(horizontal = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalWorktreeColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            // The New UI paints list selection as a rounded rectangle held away from the pane
            // edges, rather than as an edge-to-edge band.
            .padding(horizontal = Dimens.selectionInset, vertical = 1.dp)
            .clip(RoundedCornerShape(Dimens.selectionArc))
            .background(
                when {
                    selected -> colors.selection
                    hovered -> colors.hover
                    else -> Color.Transparent
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Small rounded count/state chip. */
@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.22f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Single-line input styled like an IntelliJ text field. */
@Composable
fun IdeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val colors = LocalWorktreeColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(Dimens.arc))
            .background(if (enabled) colors.panel else colors.panelAlt)
            .border(1.dp, colors.controlBorder, RoundedCornerShape(Dimens.arc))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = colors.textDisabled, style = textStyle)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = textStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Centred hint shown where a pane has nothing to display. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val colors = LocalWorktreeColors.current
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = colors.textDisabled, style = MaterialTheme.typography.bodySmall)
        action?.invoke()
    }
}

/**
 * Draggable divider that resizes the pane on one side of it.
 *
 * The pane is measured in absolute [size], not as a fraction of the window. That is what makes a
 * wider window widen only the flexible pane between the dividers: fractions would grow every pane
 * at once, and the side panels would drift wider every time the window did.
 *
 * The running size lives in a local inside the gesture. A `pointerInput` block captures its lambda
 * once and keeps running it for the whole drag, and several drag events can arrive between two
 * frames, so anything read from composition here would be stale — the divider would advance one
 * step and freeze.
 */
@Composable
fun VerticalSplitter(
    size: Dp,
    onSizeChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    min: Dp = 140.dp,
    max: Dp = 640.dp,
    sizesTrailingPane: Boolean = false,
    color: Color = LocalWorktreeColors.current.border,
) = Splitter(true, size, onSizeChange, modifier, min, max, sizesTrailingPane, color)

@Composable
fun HorizontalSplitter(
    size: Dp,
    onSizeChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    min: Dp = 80.dp,
    max: Dp = 720.dp,
    sizesTrailingPane: Boolean = false,
    color: Color = LocalWorktreeColors.current.border,
) = Splitter(false, size, onSizeChange, modifier, min, max, sizesTrailingPane, color)

/**
 * A divider that moves a *proportion* rather than an absolute size.
 *
 * The agent wall wants this and the window layout does not: inside a wall of terminals every pane
 * should keep its share as the window grows, whereas side panels should keep their width. Same
 * gesture handling as [Splitter], including the running value living inside the gesture.
 */
@Composable
fun ProportionalSplitter(
    vertical: Boolean,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    totalPx: Float,
    modifier: Modifier = Modifier,
    min: Float = 0.08f,
) {
    val colors = LocalWorktreeColors.current
    val currentFraction by rememberUpdatedState(fraction)
    val currentTotal by rememberUpdatedState(totalPx)
    val onChange by rememberUpdatedState(onFractionChange)

    var dragging by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = dragging || hovered

    Box(
        modifier = modifier
            .then(
                if (vertical) {
                    Modifier.fillMaxHeight().width(Dimens.splitterThickness)
                } else {
                    Modifier.fillMaxWidth().height(Dimens.splitterThickness)
                }
            )
            .hoverable(interaction)
            .pointerHoverIcon(if (vertical) ResizeColumnCursor else ResizeRowCursor)
            .pointerInput(Unit) {
                var position = 0f
                detectDragGestures(
                    onDragStart = {
                        position = currentFraction
                        dragging = true
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, drag ->
                    change.consume()
                    val total = currentTotal
                    if (total > 0f) {
                        val delta = if (vertical) drag.x else drag.y
                        position = (position + delta / total).coerceIn(min, 1f - min)
                        onChange(position)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(
                    if (vertical) {
                        Modifier.fillMaxHeight().width(1.dp)
                    } else {
                        Modifier.fillMaxWidth().height(1.dp)
                    }
                )
                .background(if (active) colors.accent else colors.border),
        )
    }
}

/** Proper resize cursors; a hand pointer reads as "click me", not "drag me". */
private val ResizeColumnCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR))
private val ResizeRowCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR))

@Composable
private fun Splitter(
    vertical: Boolean,
    size: Dp,
    onSizeChange: (Dp) -> Unit,
    modifier: Modifier,
    min: Dp,
    max: Dp,
    /** True when the sized pane sits *after* the divider, so dragging back towards it grows it. */
    sizesTrailingPane: Boolean,
    /**
     * The line's colour at rest.
     *
     * `border` is Gray1 — the *same* value as `editor` — so a divider drawn with it between two
     * editor-coloured regions is painted and invisible. Panes on that surface pass `separator`.
     */
    color: Color,
) {
    val colors = LocalWorktreeColors.current
    val currentSize by rememberUpdatedState(size)
    val onChange by rememberUpdatedState(onSizeChange)

    var dragging by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = dragging || hovered

    val gesture = Modifier.pointerInput(Unit) {
        var position = 0f
        detectDragGestures(
            onDragStart = {
                position = currentSize.toPx()
                dragging = true
            },
            onDragEnd = { dragging = false },
            onDragCancel = { dragging = false },
        ) { change, drag ->
            change.consume()
            val delta = if (vertical) drag.x else drag.y
            position = (position + if (sizesTrailingPane) -delta else delta)
                .coerceIn(min.toPx(), max.toPx())
            onChange(position.toDp())
        }
    }

    Box(
        modifier = modifier
            .then(
                if (vertical) {
                    Modifier.fillMaxHeight().width(Dimens.splitterThickness)
                } else {
                    Modifier.fillMaxWidth().height(Dimens.splitterThickness)
                }
            )
            .hoverable(interaction)
            .pointerHoverIcon(if (vertical) ResizeColumnCursor else ResizeRowCursor)
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(
                    if (vertical) {
                        Modifier.fillMaxHeight().width(1.dp)
                    } else {
                        Modifier.fillMaxWidth().height(1.dp)
                    }
                )
                .background(if (active) colors.accent else color),
        )
    }
}

/** Underlined tab strip, as used for the right-hand pane and the terminal. */
@Composable
fun IdeTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val colors = LocalWorktreeColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val underlineHeight = with(LocalDensity.current) { Dimens.tabUnderline.toPx() }
    val underlineRadius = with(LocalDensity.current) { (Dimens.tabUnderline / 2).toPx() }
    Box(modifier.height(Dimens.tabHeight)) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .background(if (hovered && !selected) colors.hover else Color.Transparent)
                // Drawn rather than laid out: a `fillMaxWidth` underline would stretch the tab
                // itself to the full strip and push every other tab out of view.
                .drawWithContent {
                    drawContent()
                    if (selected) {
                        drawRoundRect(
                            color = colors.accent,
                            topLeft = Offset(0f, size.height - underlineHeight),
                            size = Size(size.width, underlineHeight),
                            cornerRadius = CornerRadius(underlineRadius, underlineRadius),
                        )
                    }
                }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = text,
                color = if (selected) colors.text else colors.textDim,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            badge?.invoke()
            if (onClose != null) {
                ToolButton(
                    icon = IconKind.CLOSE,
                    tooltip = "Close this tab",
                    onClick = onClose,
                    modifier = Modifier.size(16.dp),
                    tint = colors.textDim,
                )
            }
        }
    }
}
