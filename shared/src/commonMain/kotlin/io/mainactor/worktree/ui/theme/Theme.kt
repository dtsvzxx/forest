package io.mainactor.worktree.ui.theme

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colour roles the UI needs beyond what Material3's scheme covers.
 *
 * Values come from JetBrains' own theme definitions rather than from eyeballing screenshots:
 * chrome from the New UI dark theme (`expUI_dark.theme.json`), and VCS/diff semantics from the
 * editor colour scheme that Android Studio inherits. The named greys and blues below are that
 * theme's `Gray1…Gray14` / `Blue1…Blue13` ramps.
 */
@Immutable
data class WorktreeColors(
    // Surfaces
    val panel: Color,
    val panelAlt: Color,
    val editor: Color,
    val toolbar: Color,
    val statusBar: Color,
    val border: Color,
    val separator: Color,
    val controlBorder: Color,

    // Text
    val text: Color,
    val textDim: Color,
    val textDisabled: Color,

    // Interaction
    val hover: Color,
    val hoverOverlay: Color,
    val selection: Color,
    val selectionInactive: Color,
    val focusRing: Color,
    val accent: Color,
    val link: Color,

    // Git / VCS semantics
    val added: Color,
    val modified: Color,
    val deleted: Color,
    val renamed: Color,
    val untracked: Color,
    val conflicted: Color,
    val ignored: Color,
    val branchLocal: Color,
    val branchRemote: Color,

    // Diff view
    val diffAddedBg: Color,
    val diffDeletedBg: Color,
    val diffHunkBg: Color,
    val diffGutterText: Color,

    // Conflict resolution
    val oursBg: Color,
    val oursAccent: Color,
    val theirsBg: Color,
    val theirsAccent: Color,
    val baseBg: Color,

    // Feedback
    val error: Color,
    val warning: Color,
    val success: Color,
)

/**
 * The IntelliJ / Android Studio **New UI** dark theme.
 *
 * Compared with classic Darcula this is a colder, lower-contrast shell: panels sit above a nearly
 * black editor, borders are *darker* than the surfaces they separate rather than lighter, and the
 * accent moved to a considerably brighter blue.
 */
val NewUiDarkColors = WorktreeColors(
    panel = Color(0xFF2B2D30),          // Gray2  — tool windows, toolbar, status bar
    panelAlt = Color(0xFF393B40),       // Gray3  — chips, popups, secondary surfaces
    editor = Color(0xFF1E1F22),         // Gray1  — editor, diff, terminal
    toolbar = Color(0xFF2B2D30),
    statusBar = Color(0xFF2B2D30),
    border = Color(0xFF1E1F22),         // Gray1  — *.borderColor: darker than the panel
    separator = Color(0xFF393B40),      // Gray3
    controlBorder = Color(0xFF4E5157),  // Gray5  — Component.borderColor / Button.startBorderColor

    text = Color(0xFFDFE1E5),           // Gray12
    textDim = Color(0xFF9DA0A8),        // Gray9
    textDisabled = Color(0xFF6F737A),   // Gray7

    hover = Color(0xFF393B40),          // Gray3  — *.hoverBackground
    hoverOverlay = Color(0x16FFFFFF),   // ActionButton.hoverBackground
    selection = Color(0xFF2E436E),      // Blue2  — *.selectionBackground
    selectionInactive = Color(0xFF43454A), // Gray4
    focusRing = Color(0xFF3574F0),      // Blue6
    accent = Color(0xFF3574F0),         // Blue6  — default button, tab underline
    link = Color(0xFF6B9BFA),           // Blue9

    added = Color(0xFF629755),          // FILESTATUS_ADDED
    modified = Color(0xFF6897BB),       // FILESTATUS_MODIFIED
    deleted = Color(0xFF6C6C6C),        // FILESTATUS_DELETED
    renamed = Color(0xFF3A8484),        // FILESTATUS_RENAMED
    untracked = Color(0xFFD1675A),      // FILESTATUS_UNKNOWN
    conflicted = Color(0xFFD5756C),     // FILESTATUS_MERGED_WITH_CONFLICTS
    ignored = Color(0xFF848504),
    branchLocal = Color(0xFFDFE1E5),
    branchRemote = Color(0xFF868A91),   // Gray8

    diffAddedBg = Color(0xFF294436),    // DIFF_INSERTED
    diffDeletedBg = Color(0xFF484A4A),  // DIFF_DELETED
    diffHunkBg = Color(0xFF2B2D30),
    diffGutterText = Color(0xFF606366), // LINE_NUMBERS_COLOR

    oursBg = Color(0xFF25324D),         // Blue1
    oursAccent = Color(0xFF548AF7),     // Blue8
    theirsBg = Color(0xFF253627),       // Green1
    theirsAccent = Color(0xFF5FAD65),   // Green7
    baseBg = Color(0xFF3D3223),         // Yellow1

    error = Color(0xFFDB5C5C),          // Red7
    warning = Color(0xFFF2C55C),        // Yellow7
    success = Color(0xFF5FAD65),        // Green7
)

val LocalWorktreeColors = staticCompositionLocalOf { NewUiDarkColors }

/**
 * New UI metrics. The corner radii are the ones the platform itself uses — `Component.arc` and
 * `Button.arc` are 8, and list selection is drawn as a rounded rectangle inset from the pane edge
 * rather than as a full-bleed band, which is the change that most identifies the new look.
 */
object Dimens {
    val rowHeight = 26.dp
    val toolbarHeight = 40.dp
    val statusBarHeight = 26.dp
    val tabHeight = 34.dp
    val iconButton = 26.dp
    val gutterWidth = 54.dp
    /** Wider than the 1dp rule it draws: the whole strip is the grab target. */
    val splitterThickness = 7.dp

    /** `Component.arc` / `Button.arc`. */
    val arc = 8.dp

    /** Rounded selection in lists and trees, and how far it is inset from the pane edges. */
    val selectionArc = 8.dp
    val selectionInset = 4.dp

    /** `EditorTabs.underlineHeight` / `underlineArc`. */
    val tabUnderline = 3.dp
}

val MonoFamily = FontFamily.Monospace

private val ideTypography = Typography().let { base ->
    base.copy(
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
        labelLarge = base.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp),
        titleSmall = base.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
}

val CodeTextStyle = TextStyle(
    fontFamily = MonoFamily,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
)

@Composable
fun WorktreeTheme(colors: WorktreeColors = NewUiDarkColors, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = Color.White,
        primaryContainer = colors.selection,
        onPrimaryContainer = colors.text,
        secondary = colors.link,
        background = colors.panel,
        onBackground = colors.text,
        surface = colors.panel,
        onSurface = colors.text,
        surfaceVariant = colors.panelAlt,
        onSurfaceVariant = colors.textDim,
        outline = colors.separator,
        outlineVariant = colors.border,
        error = colors.error,
        onError = Color.White,
    )
    CompositionLocalProvider(
        LocalWorktreeColors provides colors,
        // Right-click menus are drawn by the foundation, so they only follow the theme if told to.
        LocalContextMenuRepresentation provides DefaultContextMenuRepresentation(
            backgroundColor = colors.panelAlt,
            textColor = colors.text,
            itemHoverColor = colors.selection,
            disabledTextColor = colors.textDisabled,
        ),
        LocalScrollbarStyle provides defaultScrollbarStyle().copy(
            thickness = 9.dp,
            shape = RoundedCornerShape(5.dp),
            unhoverColor = Color(0x33FFFFFF),
            hoverColor = Color(0x59FFFFFF),
        ),
    ) {
        MaterialTheme(colorScheme = scheme, typography = ideTypography, content = content)
    }
}
