package io.mainactor.worktree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mainactor.worktree.git.Git
import io.mainactor.worktree.model.Branch
import io.mainactor.worktree.model.Project
import io.mainactor.worktree.model.Worktree
import io.mainactor.worktree.platform.CommandResult
import io.mainactor.worktree.platform.CommandRunner
import io.mainactor.worktree.platform.DirectoryChooser
import io.mainactor.worktree.platform.FileSystemAccess
import io.mainactor.worktree.model.SplitAxis
import io.mainactor.worktree.platform.SystemIntegration
import io.mainactor.worktree.ui.components.drawForestIcon
import io.mainactor.worktree.ui.dialogs.NewAgentDialog
import io.mainactor.worktree.ui.dialogs.NewWorktreeDialog
import io.mainactor.worktree.ui.dialogs.SwitchBranchDialog
import io.mainactor.worktree.ui.WindowChrome
import io.mainactor.worktree.ui.theme.Dimens
import io.mainactor.worktree.ui.theme.LocalWorktreeColors
import io.mainactor.worktree.ui.theme.WorktreeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the whole window off-screen and checks that it actually painted something.
 *
 * This is the cheapest guard against the failures that unit tests cannot see — a layout that
 * throws at measure time, a nested-scroll crash, an infinite constraint — and it runs without a
 * display, so it works in CI. The PNG it leaves behind is also the fastest way to eyeball a
 * layout change.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AppRenderTest {

    @Test
    fun `renders the main window against a fake repository`() {
        render(worktrees = 2, into = File("build/reports/app-render.png"))
    }

    @Test
    fun `renders the conflict resolution pane`() {
        // A conflicted status routes the right-hand pane to Conflicts on its own, so this walks
        // the same path the app takes when a merge stops.
        render(
            worktrees = 2,
            conflicted = true,
            into = File("build/reports/app-render-conflicts.png"),
        )
    }

    @Test
    fun `the log shows a commit's files and its patch`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        val image = try {
            scene.render()
            scene.render()
            state.rightTab = RightTab.LOG
            scene.render()
            state.selectCommit(state.commits.first())
            scene.render()
        } finally {
            scene.close()
        }

        File("build/reports/app-render-log.png").apply { parentFile?.mkdirs() }
            .writeBytes(image.encodeToData(EncodedImageFormat.PNG)?.bytes!!)

        assertEquals(2, state.commitFiles.size, "the commit's files should have been parsed")
        assertEquals("src/Orders.kt", state.commitFile?.path, "the first file should be selected")
    }

    @Test
    fun `searching for a file shows the commits that touched it`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        val image = try {
            scene.render()
            scene.render()
            state.rightTab = RightTab.SEARCH
            scene.render()
            state.search("Order")
            scene.render()
            state.selectSearchFile(state.searchResults.first())
            scene.render()
        } finally {
            scene.close()
        }

        File("build/reports/app-render-search.png").apply { parentFile?.mkdirs() }
            .writeBytes(image.encodeToData(EncodedImageFormat.PNG)?.bytes!!)

        // Every divider in this pane sits between two `editor`-coloured regions, and `border` is
        // that same Gray1 — drawn with it, a separator is painted and invisible. Scanning a column
        // clear of the vertical splitter for lines that are actually a different colour is what
        // tells "there is a divider here" from "there is nothing here".
        assertTrue(
            rowsWithColour(image, x = WIDTH - 80, from = 70, to = HEIGHT - 40, rgb = SEPARATOR) >= 2,
            "the search field and the pane splitter should both be separated by a visible line",
        )

        // Name matches rank above path-only ones: Orders.kt before src/orders/....
        assertEquals("src/Orders.kt", state.searchResults.first())
        assertEquals(3, state.searchResults.size, "only the paths containing the query")
        assertEquals(4, state.fileCommits.size, "the file's history should have loaded")
        assertEquals("src/Orders.kt", state.fileDiff?.path)
    }

    @Test
    fun `notes are written in one pane and picked from another`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        val image = try {
            scene.render()
            scene.render()
            state.rightTab = RightTab.NOTES
            state.addNote()
            state.updateNote(state.selectedNote!!, "Ship the release notes\n\nRead the log since the last tag.")
            state.addNote()
            state.updateNote(
                state.selectedNote!!,
                "Rewrite the pty layer on FFM\n\nNo native library should ship in a jar.",
            )
            scene.render()
        } finally {
            scene.close()
        }

        File("build/reports/app-render-notes.png").apply { parentFile?.mkdirs() }
            .writeBytes(image.encodeToData(EncodedImageFormat.PNG)?.bytes!!)

        // The list is newest first, and a note is named by its own first line.
        assertEquals(
            listOf("Rewrite the pty layer on FFM", "Ship the release notes"),
            state.notes.map { it.title },
        )
    }

    @Test
    fun `renders the picker that sends a note to an agent`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        val image = try {
            scene.render()
            scene.render()
            state.addNote()
            state.updateNote(state.selectedNote!!, "Rewrite the pty layer on FFM\n\nNo native library in a jar.")
            state.addNote()
            state.updateNote(state.selectedNote!!, "Ship the release notes")
            state.switchTo(AppMode.AGENTS)
            scene.render()
            state.requestNote("pane-1")
            // One pass runs the effect that turns the request into a dialog, the next paints it.
            scene.render()
            scene.render()
        } finally {
            scene.close()
        }

        File("build/reports/app-render-send-note.png").apply { parentFile?.mkdirs() }
            .writeBytes(image.encodeToData(EncodedImageFormat.PNG)?.bytes!!)

        assertEquals("pane-1", state.noteRequest, "the picker should still be open")
    }

    @Test
    fun `renders a failed command's output`() {
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            WorktreeTheme {
                Box(Modifier.fillMaxSize().background(LocalWorktreeColors.current.editor)) {
                    io.mainactor.worktree.ui.dialogs.CommandFailureDialog(
                        failure = io.mainactor.worktree.CommandFailure(
                            name = "Build",
                            worktree = "FM-3713-release-5.7",
                            commandLine = "./gradlew assembleDebug",
                            exitCode = 1,
                            output = buildString {
                                appendLine("> Task :app:compileDebugKotlin FAILED")
                                appendLine("e: Orders.kt:14:9 Unresolved reference 'audit'")
                                appendLine("e: Orders.kt:15:5 Expecting '}'")
                                appendLine()
                                appendLine("FAILURE: Build failed with an exception.")
                                append("BUILD FAILED in 12s")
                            },
                        ),
                        onCopy = {},
                        onDismiss = {},
                    )
                }
            }
        }
        val image = try { scene.render(); scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty())
        File("build/reports/app-render-command-failed.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }

    @Test
    fun `renders the rename dialog`() {
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            WorktreeTheme {
                Box(Modifier.fillMaxSize().background(LocalWorktreeColors.current.editor)) {
                    io.mainactor.worktree.ui.dialogs.RenameWorktreeDialog(
                        worktree = Worktree(
                            path = "/repo-NOTASK-partial-payments-rollout",
                            branch = "NOTASK-partial-payments-rollout",
                        ),
                        onDismiss = {},
                        onConfirm = { _, _ -> },
                    )
                }
            }
        }
        val image = try { scene.render(); scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty())
        File("build/reports/app-render-rename.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }

    @Test
    fun `renders the commit dialog`() {
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            WorktreeTheme {
                Box(Modifier.fillMaxSize().background(LocalWorktreeColors.current.editor)) {
                    io.mainactor.worktree.ui.dialogs.CommitDialog(
                        stagedCount = 3,
                        unstagedCount = 2,
                        // The longer of the two: a branch with no upstream is about to get one.
                        pushCommand = "git push -u origin NOTASK-partial-payments-rollout",
                        onDismiss = {},
                        onCommit = { _, _, _, _ -> },
                    )
                }
            }
        }
        val image = try { scene.render(); scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty())
        File("build/reports/app-render-commit.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }

    @Test
    fun `renders the project's agent settings`() {
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            WorktreeTheme {
                Box(Modifier.fillMaxSize().background(LocalWorktreeColors.current.editor)) {
                    io.mainactor.worktree.ui.dialogs.AgentSettingsDialog(
                        projectName = "repo",
                        agents = io.mainactor.worktree.model.ProjectAgents(
                            enabled = setOf("claude", "shell", "custom-aider"),
                            custom = listOf(
                                io.mainactor.worktree.model.AgentSpec(
                                    id = "custom-aider",
                                    name = "Aider",
                                    command = "aider --model sonnet",
                                    builtIn = false,
                                ),
                            ),
                        ),
                        // Codex reads as missing here, which is the case worth looking at: it is
                        // still listed and still tickable.
                        isInstalled = { it.id != "codex" },
                        onDismiss = {},
                        onConfirm = {},
                    )
                }
            }
        }
        val image = try { scene.render(); scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty())
        File("build/reports/app-render-agent-settings.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }

    @Test
    fun `hovering a toolbar button shows its tooltip`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        val image = try {
            scene.render()
            scene.render()
            // "Add a worktree" in the Worktrees pane header.
            scene.sendPointerEvent(PointerEventType.Enter, Offset(496f, 58f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(496f, 58f))
            scene.render()
            Thread.sleep(TOOLTIP_WAIT_MS)
            scene.render()
        } finally {
            scene.close()
        }

        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes!!
        File("build/reports/app-render-tooltip.png").apply { parentFile?.mkdirs() }.writeBytes(png)

        // The tooltip card is the only thing that can paint over the pane below the button.
        assertTrue(
            hasNonPanelPixels(image, x = 460, y = 90, width = 320, height = 60),
            "no tooltip appeared under the hovered button",
        )
    }

    /** How many pixel rows in the column at [x] are exactly [rgb] — one row per drawn divider. */
    private fun rowsWithColour(
        image: org.jetbrains.skia.Image,
        x: Int,
        from: Int,
        to: Int,
        rgb: Int,
    ): Int {
        val pixels = image.peekPixels() ?: return 0
        val bytes = pixels.buffer.bytes
        val rowBytes = pixels.rowBytes
        var found = 0
        for (row in from until to) {
            val i = row * rowBytes + x * 4
            if (i + 2 >= bytes.size) continue
            val colour = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            if (colour == rgb) found++
        }
        return found
    }

    /** True when the region contains something other than the flat panel colour. */
    private fun hasNonPanelPixels(
        image: org.jetbrains.skia.Image,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val pixels = image.peekPixels() ?: return false
        val bytes = pixels.buffer.bytes
        val rowBytes = pixels.rowBytes
        var different = 0
        for (row in y until y + height) {
            for (col in x until x + width step 2) {
                val i = row * rowBytes + col * 4
                if (i + 2 >= bytes.size) continue
                val r = bytes[i].toInt() and 0xFF
                val g = bytes[i + 1].toInt() and 0xFF
                val b = bytes[i + 2].toInt() and 0xFF
                // Panel is #2B2D30; the tooltip card is #393B40 with lighter text on it.
                if (r > 0x33 && g > 0x33 && b > 0x36) different++
            }
        }
        return different > 200
    }

    @Test
    fun `a bigger window grows only the changes pane and nothing else`() {
        // Two windows, same starting state. The side panes and the terminal are sized absolutely,
        // so their dividers must land in the same place regardless of how big the window is; only
        // the pane between them takes the extra room. Fractional sizing would move all of them.
        val small = measurePanes(WIDTH, HEIGHT)
        val large = measurePanes(WIDTH + 500, HEIGHT + 400)

        assertEquals(small.firstDividerX, large.firstDividerX, "the projects pane grew with the window")
        assertEquals(small.bottomBandHeight, large.bottomBandHeight, "the terminal grew with the window")
        assertTrue(
            large.changesPaneWidth > small.changesPaneWidth + 400,
            "the changes pane did not absorb the extra width: " +
                "${small.changesPaneWidth} -> ${large.changesPaneWidth}",
        )
    }

    private class PaneMetrics(
        val firstDividerX: Int,
        val bottomBandHeight: Int,
        val changesPaneWidth: Int,
    )

    private fun measurePanes(width: Int, height: Int): PaneMetrics {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(width, height, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }
        return try {
            scene.render()
            scene.render()
            state.toggleTerminal()
            scene.render()
            val image = scene.render()
            val first = splitterX(image)
            val second = splitterX(image, from = first + 4)
            PaneMetrics(
                firstDividerX = first,
                bottomBandHeight = height - panesBottomY(image, height),
                changesPaneWidth = width - second,
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `dragging the first splitter resizes the projects pane`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        try {
            scene.render()
            scene.render()
            val before = splitterX(scene.render())

            // Grab the divider and pull it right, in several steps as a real drag would.
            val y = 300f
            scene.sendPointerEvent(PointerEventType.Press, Offset(before + 0.5f, y))
            var x = before + 0.5f
            repeat(10) {
                x += 12f
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))

            val after = splitterX(scene.render())
            File("build/reports/app-render-splitter.png").apply { parentFile?.mkdirs() }
                .writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)

            assertTrue(
                after > before + 60,
                "the splitter did not follow the drag: $before -> $after",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `dragging the terminal splitter resizes the tool window`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        try {
            scene.render()
            scene.render()
            state.toggleTerminal()
            scene.render()
            val before = panesBottomY(scene.render())

            // Drag the divider upwards: the terminal should grow. This splitter is driven by the
            // inverse of the terminal's own fraction, which is easy to get backwards.
            val x = 400f
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, before + 2f))
            var y = before + 2f
            repeat(10) {
                y -= 12f
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))

            val after = panesBottomY(scene.render())
            assertTrue(after < before - 60, "the terminal did not grow: divider $before -> $after")
        } finally {
            scene.close()
        }
    }

    /**
     * Y of the horizontal divider under the panes, i.e. the top of the terminal tool window.
     *
     * Scans below the project rows, and requires the darkness to persist for a few pixels — the
     * tool-window header is separated by a one-pixel rule of the very same colour.
     */
    private fun panesBottomY(image: org.jetbrains.skia.Image): Int = panesBottomY(image, image.height)

    private fun panesBottomY(image: org.jetbrains.skia.Image, height: Int): Int {
        val pixels = image.peekPixels()!!
        val bytes = pixels.buffer.bytes
        val rowBytes = pixels.rowBytes
        val col = 100
        fun isDark(row: Int): Boolean {
            val i = row * rowBytes + col * 4
            return (bytes[i].toInt() and 0xFF) <= 0x24 && (bytes[i + 1].toInt() and 0xFF) <= 0x25
        }
        for (row in 250 until height - 60) {
            if (isDark(row) && isDark(row + 2) && isDark(row + 4)) return row
        }
        error("no terminal boundary found")
    }

    /**
     * X of the first pane divider: a one-pixel column of the border colour between two panels.
     */
    private fun splitterX(image: org.jetbrains.skia.Image, from: Int = 60): Int {
        val pixels = image.peekPixels()!!
        val bytes = pixels.buffer.bytes
        val rowBytes = pixels.rowBytes
        val row = 300
        for (col in from until image.width) {
            val i = row * rowBytes + col * 4
            val r = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val b = bytes[i + 2].toInt() and 0xFF
            // Border is #1E1F22; the panels either side are #2B2D30.
            if (r <= 0x22 && g <= 0x23 && b <= 0x26) return col
        }
        error("no divider found in the rendered window")
    }

    @Test
    fun `right-clicking a project opens its context menu`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        val image = try {
            scene.render()
            scene.render()
            // The project row, whose path is no longer on screen and lives in this menu instead.
            val spot = Offset(120f, 96f)
            scene.sendPointerEvent(PointerEventType.Move, spot)
            scene.sendPointerEvent(PointerEventType.Press, spot, button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, spot, button = PointerButton.Secondary)
            scene.render()
            scene.render()
        } finally {
            scene.close()
        }

        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes!!
        File("build/reports/app-render-context-menu.png").apply { parentFile?.mkdirs() }.writeBytes(png)

        assertTrue(
            hasNonPanelPixels(image, x = 120, y = 110, width = 200, height = 90),
            "no context menu appeared under the pointer",
        )
    }

    /**
     * The lists that had no menu now have one, and this is the guard that they still do.
     *
     * A right-click acts on the row under the pointer, which is the whole point of having one: the
     * toolbar acts on the *selected* file, so reaching for it means selecting first. The changed
     * files list is the one that costs the most to be without.
     */
    @Test
    fun `right-clicking a changed file opens its context menu`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        val image = try {
            scene.render()
            scene.render()
            // The first file row. Not the section header above it, which is what a guess at the
            // coordinates lands on and which has no menu of its own.
            val spot = Offset(700f, 147f)
            scene.sendPointerEvent(PointerEventType.Move, spot)
            scene.sendPointerEvent(PointerEventType.Press, spot, button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, spot, button = PointerButton.Secondary)
            scene.render()
            scene.render()
        } finally {
            scene.close()
        }

        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes!!
        File("build/reports/app-render-file-menu.png").apply { parentFile?.mkdirs() }.writeBytes(png)

        assertTrue(
            hasNonPanelPixels(image, x = 700, y = 160, width = 200, height = 80),
            "no context menu appeared under the pointer",
        )
    }

    @Test
    fun `renders the new-worktree dialog`() {
        // Dialogs are modal state inside App and cannot be opened from outside it, so the
        // restyled controls are rendered directly instead.
        val scene = ImageComposeScene(720, 620, Density(1f), Dispatchers.Unconfined) {
            WorktreeTheme {
                Box(Modifier.fillMaxSize().background(LocalWorktreeColors.current.panel)) {
                    NewWorktreeDialog(
                        branches = listOf(
                            Branch(name = "main", isRemote = false, isCurrent = true, checkedOutIn = null),
                            Branch(name = "feature/login", isRemote = false),
                            Branch(name = "origin/main", isRemote = true),
                        ),
                        defaultParent = "/Users/dev/projects",
                        suggestedBase = "main",
                        onBrowse = {},
                        onDismiss = {},
                        onConfirm = {},
                    )
                }
            }
        }
        val image = try { scene.render(); scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty())
        File("build/reports/app-render-dialog.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }

    @Test
    fun `renders a repository with many worktrees`() {
        // The pane switches to a filtered list past a handful of worktrees; this is the case that
        // is awkward to check by hand and easy to break.
        render(worktrees = 40, into = File("build/reports/app-render-many.png"))
    }

    @Test
    fun `renders the agent wall`() {
        val state = fakeState(worktrees = 3)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { session, _, m ->
                Box(m.fillMaxSize().background(Color(0xFF1E1F22))) {
                    BasicText(
                        text = "$ ${session.title}",
                        style = TextStyle(color = Color(0xFF5FAD65), fontSize = 12.sp),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            })
        }

        val image = try {
            scene.render()
            scene.render()
            state.switchTo(AppMode.AGENTS)
            // An explicit arrangement, the way a multiplexer builds one: one pane, split right,
            // then split each half downwards.
            val worktree = state.worktrees.first()
            state.openAgent(worktree)
            state.openAgent(worktree, SplitAxis.ROW)
            state.openAgent(worktree, SplitAxis.COLUMN)
            state.openAgent(worktree, SplitAxis.ROW)
            // The wall polls usage on a timer of its own; drive one pass so the badge is painted
            // rather than waiting on wall-clock time inside a render test.
            kotlinx.coroutines.runBlocking { state.refreshAgentUsage().join() }
            scene.render()
            scene.render()
        } finally {
            scene.close()
        }

        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes!!
        File("build/reports/app-render-agents.png").apply { parentFile?.mkdirs() }.writeBytes(png)
        assertEquals(4, state.agents.size)

        // Four panes on one worktree share one transcript, and one response is one response no
        // matter how many panes are looking at it.
        val usage = state.agentUsage.getValue("/repo")
        assertEquals(1, usage.requests)
        assertEquals(2_480_200, usage.tokens.total)
        // 1.2K in at $5/M, 48K out at $25/M, 2.4M cache reads at $0.50/M, 31K one-hour cache
        // writes at $10/M — the one-hour rate being double the input rate is the part worth pinning.
        assertEquals("2.5M · ${'$'}2.72", io.mainactor.worktree.usage.UsageFormat.badge(usage))
    }

    @Test
    fun `a dialog opened on the agent wall is drawn once`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }

        val (plain, dimmed) = try {
            scene.render()
            scene.render()
            state.switchTo(AppMode.AGENTS)
            scene.render()
            val before = backgroundLuma(scene.render())

            state.requestNewAgent()
            scene.render()
            before to backgroundLuma(scene.render())
        } finally {
            scene.close()
        }

        // The modal dims what is behind it. Rendering it twice stacks two scrims and darkens the
        // background about twice as much, which is what "the dialog appears twice" looks like.
        val singleScrim = plain * (1f - MODAL_SCRIM_ALPHA)
        val doubleScrim = plain * (1f - MODAL_SCRIM_ALPHA) * (1f - MODAL_SCRIM_ALPHA)
        assertTrue(
            dimmed > (singleScrim + doubleScrim) / 2f,
            "the dialog looks stacked: background went $plain -> $dimmed, " +
                "one scrim would give ${singleScrim.toInt()}, two ${doubleScrim.toInt()}",
        )
    }

    /** Pixels that are recognisably the mark's green rather than the plate or the edge. */
    private fun countGreenPixels(bytes: ByteArray): Int {
        var count = 0
        var i = 0
        while (i + 3 < bytes.size) {
            val r = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val b = bytes[i + 2].toInt() and 0xFF
            if (g > r + 24 && g > b + 24) count++
            i += 4
        }
        return count
    }

    /** Brightness of a spot well clear of any centred dialog card. */
    private fun backgroundLuma(image: org.jetbrains.skia.Image): Float {
        val pixels = image.peekPixels()!!
        val bytes = pixels.buffer.bytes
        val i = 400 * pixels.rowBytes + 100 * 4
        return ((bytes[i].toInt() and 0xFF) + (bytes[i + 1].toInt() and 0xFF) +
            (bytes[i + 2].toInt() and 0xFF)) / 3f
    }

    @Test
    fun `the product name reads Forest, and the domain term is left alone`() {
        val state = fakeState(worktrees = 2)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, m -> Box(m.fillMaxSize().background(Color(0xFF1E1F22))) })
        }
        try {
            scene.render()
            scene.render()
        } finally {
            scene.close()
        }

        // The toolbar falls back to the product name when nothing is open; the pane header keeps
        // the git term. A careless rename would take both or neither.
        assertEquals("Forest", productFallbackName())
        assertEquals("Worktrees", worktreesPaneTitle())
    }

    private fun productFallbackName(): String {
        val chrome = File("../shared/src/commonMain/kotlin/io/mainactor/worktree/ui/Chrome.kt").readText()
        return Regex("""state\.project\?\.name \?: "([^"]+)"""").find(chrome)!!.groupValues[1]
    }

    private fun worktreesPaneTitle(): String {
        val pane = File("../shared/src/commonMain/kotlin/io/mainactor/worktree/ui/panes/WorktreesPane.kt").readText()
        return Regex("""ToolWindowHeader\("([^"]+)"\)""").find(pane)!!.groupValues[1]
    }

    @Test
    fun `the app icon draws a legible mark`() {
        // Rendered at the sizes that matter most: the installer artwork and the title-bar glyph.
        listOf(512, 128, 32, 16).forEach { side ->
            val scene = ImageComposeScene(side, side, Density(1f), Dispatchers.Unconfined) {
                Canvas(Modifier.fillMaxSize()) { drawForestIcon(size.minDimension) }
            }
            val image = try { scene.render(); scene.render() } finally { scene.close() }
            val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes!!
            File("build/reports/forest-icon-$side.png").apply { parentFile?.mkdirs() }.writeBytes(png)

            // Every pixel, not a sample: a 16px icon is 256 pixels, and the sampling stride
            // used for full windows would look at seven of them.
            val pixels = image.peekPixels()!!
            val green = countGreenPixels(pixels.buffer.bytes)
            assertTrue(green > side / 2, "the mark vanished at ${side}px — only $green green pixels")
        }
    }

    @Test
    fun `renders the new-agent picker`() {
        val scene = ImageComposeScene(720, 560, Density(1f), Dispatchers.Unconfined) {
            WorktreeTheme {
                Box(Modifier.fillMaxSize().background(LocalWorktreeColors.current.panel)) {
                    NewAgentDialog(
                        title = "Split right",
                        projects = listOf(Project("/repo", "repo"), Project("/other", "other-app")),
                        initialProject = Project("/repo", "repo"),
                        initialWorktreePath = "/repo",
                        agents = io.mainactor.worktree.model.BuiltInAgents.all,
                        initialAgentId = null,
                        loadWorktrees = { _, onLoaded ->
                            // Ordered and dated the way AppState hands them over.
                            onLoaded(
                                listOf(
                                    Worktree(
                                        path = "/repo",
                                        branch = "main",
                                        isMain = true,
                                        lastActivityLabel = "2 days ago",
                                    ),
                                    Worktree(
                                        path = "/repo-login",
                                        branch = "feature/login",
                                        lastActivityLabel = "6 minutes ago",
                                    ),
                                    Worktree(
                                        path = "/repo-api",
                                        branch = "FM-3713-release-5.7",
                                        lastActivityLabel = "3 hours ago",
                                    ),
                                )
                            )
                        },
                        onDismiss = {},
                        onConfirm = { _, _, _ -> },
                    )
                }
            }
        }
        val image = try { scene.render(); scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty())
        File("build/reports/app-render-new-agent.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }

    @Test
    fun `the picker preselects the worktree it was opened from`() {
        // Render the same picker twice, seeded with different worktrees, and see where the
        // highlight lands: it must follow the seed rather than always sitting on the first row.
        val first = selectedRowY(initialWorktreePath = "/repo")
        val third = selectedRowY(initialWorktreePath = "/repo-api")

        assertTrue(first > 0 && third > 0, "no row was highlighted at all")
        val rowsApart = ((third - first) / PICKER_ROW_HEIGHT).toInt()
        assertEquals(2, rowsApart, "expected the highlight two rows down, moved ${third - first}px")
    }

    /** Y of the highlighted row in the worktree picker, or 0 when nothing is highlighted. */
    private fun selectedRowY(initialWorktreePath: String): Int {
        val scene = ImageComposeScene(720, 560, Density(1f), Dispatchers.Unconfined) {
            WorktreeTheme {
                Box(Modifier.fillMaxSize().background(LocalWorktreeColors.current.panel)) {
                    NewAgentDialog(
                        title = "Split right",
                        projects = listOf(Project("/repo", "repo")),
                        initialProject = Project("/repo", "repo"),
                        initialWorktreePath = initialWorktreePath,
                        agents = io.mainactor.worktree.model.BuiltInAgents.all,
                        initialAgentId = null,
                        loadWorktrees = { _, onLoaded ->
                            onLoaded(
                                listOf(
                                    Worktree(path = "/repo", branch = "main", isMain = true),
                                    Worktree(path = "/repo-login", branch = "feature/login"),
                                    Worktree(path = "/repo-api", branch = "release-5.7"),
                                )
                            )
                        },
                        onDismiss = {},
                        onConfirm = { _, _, _ -> },
                    )
                }
            }
        }
        val image = try { scene.render(); scene.render() } finally { scene.close() }
        val pixels = image.peekPixels()!!
        val bytes = pixels.buffer.bytes
        // Selection is #2E436E; the list sits on the editor colour.
        for (row in 0 until image.height) {
            val i = row * pixels.rowBytes + 360 * 4
            val r = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val b = bytes[i + 2].toInt() and 0xFF
            if (r in 0x28..0x34 && g in 0x3D..0x49 && b in 0x66..0x76) return row
        }
        return 0
    }

    @Test
    fun `renders the switch-branch dialog`() {
        val scene = ImageComposeScene(720, 640, Density(1f), Dispatchers.Unconfined) {
            WorktreeTheme {
                Box(Modifier.fillMaxSize().background(LocalWorktreeColors.current.panel)) {
                    SwitchBranchDialog(
                        worktreeFolder = "repo-login",
                        worktreePath = "/repo-login",
                        branches = listOf(
                            Branch("main", isRemote = false, checkedOutIn = "/repo"),
                            Branch("feature/login", isRemote = false, checkedOutIn = "/repo-login"),
                            Branch("spare", isRemote = false),
                            Branch("bugfix/ANDROID-2291", isRemote = false),
                            Branch("origin/shipped", isRemote = true),
                        ),
                        currentBranch = "feature/login",
                        dirtyFiles = 2,
                        onDismiss = {},
                        onSwitch = {},
                        onCreate = { _, _ -> },
                    )
                }
            }
        }
        val image = try { scene.render(); scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty())
        File("build/reports/app-render-switch-branch.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }

    /**
     * The macOS title bar is hidden and its buttons are not: they stay in the top-left corner, on
     * top of whatever the toolbar draws there. This is the guard that the toolbar starts to the
     * right of them — a mode switch under the close button is unreachable, and looks like a bug in
     * the window rather than in the layout.
     */
    @Test
    fun `the toolbar keeps clear of the window's own buttons`() {
        val strip = 78
        assertTrue(
            toolbarPaintsInside(strip, controlsWidth = 0.dp),
            "nothing was drawn in the corner to begin with, so the test proves nothing",
        )
        assertTrue(
            !toolbarPaintsInside(strip, controlsWidth = strip.dp),
            "the toolbar still draws under the window's buttons",
        )
    }

    /** True when the toolbar paints anything of its own in the first [width] points. */
    private fun toolbarPaintsInside(width: Int, controlsWidth: Dp): Boolean {
        val state = fakeState(worktrees = 2)
        WindowChrome.controlsWidth = controlsWidth
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), Dispatchers.Unconfined) {
            App(state = state, terminal = { _, _, modifier -> Box(modifier.fillMaxSize()) })
        }
        val image = try {
            scene.render()
            scene.render()
        } finally {
            scene.close()
            // A global the platform layer sets: leaving it set would move every later render.
            WindowChrome.controlsWidth = 0.dp
        }

        val pixels = image.peekPixels()!!
        val bytes = pixels.buffer.bytes
        var painted = 0
        for (row in 2 until Dimens.toolbarHeight.value.toInt() - 2) {
            for (col in 0 until width) {
                val i = row * pixels.rowBytes + col * 4
                val colour = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    (bytes[i + 2].toInt() and 0xFF)
                if (colour != TOOLBAR_RGB) painted++
            }
        }
        return painted > 20
    }

    private fun render(worktrees: Int, into: File, conflicted: Boolean = false) {
        val state = fakeState(worktrees, conflicted)

        val scene = ImageComposeScene(
            width = WIDTH,
            height = HEIGHT,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined,
        ) {
            App(
                state = state,
                // The real terminal is a heavyweight Swing component and cannot render off-screen;
                // a stand-in keeps the rest of the layout under test.
                terminal = { _, _, modifier ->
                    Box(modifier.fillMaxSize().background(Color(0xFF1E1E1E)))
                },
            )
        }

        val image = try {
            // Two passes: the first lets start()'s effects land, the second paints the result.
            scene.render()
            scene.render()
        } finally {
            scene.close()
        }

        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes
        assertTrue(png != null && png.isNotEmpty(), "the scene produced no image")

        into.parentFile?.mkdirs()
        into.writeBytes(png)

        val pixels = image.peekPixels()!!
        assertTrue(distinctColours(pixels.buffer.bytes) > 8, "the window rendered as a flat colour")
    }

    private fun distinctColours(bytes: ByteArray): Int {
        val seen = HashSet<Int>()
        var i = 0
        while (i + 3 < bytes.size) {
            seen += (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            if (seen.size > 64) return seen.size
            i += 4 * SAMPLE_STRIDE
        }
        return seen.size
    }

    /** An [AppState] wired to canned git output, so the render never touches a real repository. */
    private fun fakeState(worktrees: Int, conflicted: Boolean = false): AppState {
        val fs = FakeFileSystem(conflicted)
        return AppState(
            git = Git(FakeRunner(worktrees, conflicted), fs, gitPath = "git"),
            fs = fs,
            store = ProjectStore(fs),
            // The render tests never run one; a stub keeps them from reaching a real shell.
            shell = object : io.mainactor.worktree.platform.ShellRunner {
                override suspend fun run(workDir: String, commandLine: String) =
                    CommandResult(0, "", "")
            },
            chooser = object : DirectoryChooser {
                override suspend fun chooseDirectory(title: String, startIn: String?): String? = null
            },
            system = object : SystemIntegration {
                override val revealLabel = "Show in Finder"
                override fun reveal(path: String) = Unit
                override fun copyToClipboard(text: String) = Unit
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private companion object {
        const val WIDTH = 1440
        const val HEIGHT = 900

        /** Sampling every Nth pixel is plenty to tell "painted" from "flat fill". */
        const val SAMPLE_STRIDE = 37

        /** `WorktreeColors.toolbar` — Gray2, the flat background the toolbar draws on. */
        const val TOOLBAR_RGB = 0x2B2D30

        /** Comfortably past the tooltip's own hover delay, which runs on real time. */
        const val TOOLTIP_WAIT_MS = 900L

        /** Matches the scrim in `Modal`. */
        const val MODAL_SCRIM_ALPHA = 0.45f

        /** Height of a row in the worktree picker. */
        const val PICKER_ROW_HEIGHT = 26f

        /** `WorktreeColors.separator` — Gray3, the one divider colour visible on the editor. */
        const val SEPARATOR = 0x393B40
    }
}

private const val FS = '\u001F'
private const val NUL = '\u0000'

/** Answers the handful of git invocations the first render makes. */
private class FakeRunner(private val worktrees: Int, private val conflicted: Boolean) : CommandRunner {

    /** Distinct HEADs, so the app can date and order them the way it does for a real repository. */
    private fun head(index: Int) = index.toString().padStart(40, 'a')

    private val worktreeList = buildString {
        append("worktree /repo\nHEAD ${head(0)}\nbranch refs/heads/main\n\n")
        repeat(worktrees - 1) { i ->
            append("worktree /repo-${LONG_NAMES[i % LONG_NAMES.size]}-$i\nHEAD ${head(i + 1)}")
            append("\nbranch refs/heads/${LONG_NAMES[i % LONG_NAMES.size]}\n\n")
        }
    }

    /** `git log --no-walk`: one line per HEAD, times spread so the ordering is visible. */
    private val commitTimes = buildString {
        repeat(worktrees) { i ->
            append(head(i)).append(FS).append(1_700_000_000L - i * 7_200L).append('\n')
        }
    }


    override suspend fun exec(
        workDir: String?,
        command: List<String>,
        stdin: String?,
        env: Map<String, String>,
    ): CommandResult {
        val args = command.dropWhile { it != "worktree" && it !in VERBS }
        return when {
            args.startsWith("worktree", "list") -> ok(worktreeList)
            args.startsWith("rev-parse", "--show-toplevel") -> ok("/repo\n")
            args.startsWith("rev-parse") -> CommandResult(1, "", "")
            args.startsWith("status") -> ok(if (conflicted) CONFLICTED_STATUS else STATUS)
            args.startsWith("for-each-ref") -> ok(if ("refs/heads" in command) BRANCHES else "")
            args.startsWith("log", "--no-walk") -> ok(commitTimes)
            args.startsWith("log") -> ok(LOG)
            args.startsWith("ls-files") -> ok(TRACKED_FILES)
            args.startsWith("show") -> ok(COMMIT_PATCH)
            args.startsWith("diff") -> ok(DIFF)
            else -> ok("")
        }
    }

    private fun ok(stdout: String) = CommandResult(0, stdout, "")

    private fun List<String>.startsWith(vararg prefix: String) =
        size >= prefix.size && prefix.withIndex().all { (i, v) -> this[i] == v }

    private companion object {
        val LONG_NAMES = listOf(
            "NOTASK-partial-payments-rollout",
            "FM-3713-release-5.7",
            "feature/login",
            "bugfix/ANDROID-2291-crash-on-startup",
        )

        val VERBS = setOf("rev-parse", "status", "for-each-ref", "log", "ls-files", "show", "diff", "remote")

        val STATUS = listOf(
            "# branch.oid aaaaaaaaaaaaaaaaaaaa",
            "# branch.head main",
            "# branch.upstream origin/main",
            "# branch.ab +2 -1",
            "1 M. N... 100644 100644 100644 aaa bbb src/Main.kt",
            "1 .M N... 100644 100644 100644 ccc ddd README.md",
            "? notes.txt",
        ).joinToString(NUL.toString()) + NUL

        val BRANCHES = buildString {
            appendLine("main${FS}origin/main$FS*$FS/repo${FS}local")
            appendLine("feature/login$FS$FS$FS/repo-login${FS}local")
        }

        val CONFLICTED_STATUS = listOf(
            "# branch.oid aaaaaaaaaaaaaaaaaaaa",
            "# branch.head main",
            "u UU N... 100644 100644 100644 100644 aaa bbb ccc src/Greeting.kt",
            "1 M. N... 100644 100644 100644 aaa bbb src/Main.kt",
        ).joinToString(NUL.toString()) + NUL

        /**
         * Deliberately shaped like a real shared repository: eight-character hashes, refs long
         * enough to crowd the row, and merge subjects. The hash column used to wrap at this size.
         */
        val LOG = listOf(
            "c2c22b8e11${FS}c2c22b8e${FS}Merge branch 'dtsv/NOTASK-partial-orders-fix' into 'main'" +
                "${FS}Dmitry Tsvetkov${FS}17 hours ago${FS}HEAD -> main, origin/main, origin/HEAD",
            "14142ab022${FS}14142ab0${FS}Partial close fix${FS}dmitry.tsvetkov${FS}17 hours ago" +
                "${FS}dtsv/NOTASK-partial-orders-fix",
            "04dd069b33${FS}04dd069b${FS}Merge branch 'kmp-stage-adaptation' into 'FM-3713-release-5.7'" +
                "${FS}artem.bambalov${FS}3 days ago$FS",
            "1917f2a544${FS}1917f2a5${FS}Set marketing version to 5.8${FS}app_mtaciuser${FS}3 days ago$FS",
        ).joinToString("\n", postfix = "\n")

        /** `git ls-files -z`: the index the Search tab filters in memory. */
        val TRACKED_FILES = listOf(
            "src/Orders.kt",
            "src/orders/OrderRepository.kt",
            "src/orders/PartialOrderPolicy.kt",
            "src/payments/Refund.kt",
            "README.md",
        ).joinToString(NUL.toString()) + NUL

        /** `git show` for whichever commit the Log tab asks about. */
        val COMMIT_PATCH = """
            diff --git a/src/Orders.kt b/src/Orders.kt
            --- a/src/Orders.kt
            +++ b/src/Orders.kt
            @@ -12,6 +12,7 @@
             fun close(order: Order) {
            -    order.state = CLOSED
            +    order.state = if (order.partial) PARTIAL else CLOSED
            +    audit(order)
             }
            diff --git a/README.md b/README.md
            new file mode 100644
            --- /dev/null
            +++ b/README.md
            @@ -0,0 +1,2 @@
            +# Orders
            +Partial close is supported.
        """.trimIndent()

        val DIFF = """
            diff --git a/src/Main.kt b/src/Main.kt
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -1,3 +1,4 @@
             fun main() {
            -    println("old")
            +    println("new")
            +    println("extra")
             }
        """.trimIndent()
    }
}

/** Pretends the recent-projects file already lists one repository. */
private class FakeFileSystem(private val conflicted: Boolean = false) : FileSystemAccess {
    override fun exists(path: String) = true
    override fun isDirectory(path: String) = true

    override fun readText(path: String) =
        if (conflicted && path.endsWith(".kt")) CONFLICTED_FILE else "/repo"
    override fun writeText(path: String, text: String) = Unit
    override fun createDirectories(path: String) = Unit
    override fun homeDir() = "/home/dev"
    override fun nameOf(path: String) = path.substringAfterLast('/')
    override fun parentOf(path: String) = path.substringBeforeLast('/').ifEmpty { null }
    override fun resolve(base: String, child: String) = "$base/$child"
    override fun canonicalPath(path: String) = path

    // Nothing is installed as far as the render tests are concerned; the dialogs list the
    // built-ins anyway and say so.
    override fun findOnPath(name: String): String? = null

    // Enough of a ~/.claude/projects tree for the agent wall to show a usage badge: one project
    // directory for /repo, holding one session with one response in it.
    override fun listDirectory(path: String): List<String> = when {
        path.endsWith(".claude/projects") -> listOf("-repo")
        path.endsWith("/-repo") -> listOf("s.jsonl")
        else -> emptyList()
    }

    override fun fileSize(path: String) = if (path.endsWith("s.jsonl")) TRANSCRIPT.size.toLong() else 0L

    override fun readFrom(path: String, offset: Long, maxBytes: Int): ByteArray {
        if (!path.endsWith("s.jsonl") || offset >= TRANSCRIPT.size) return ByteArray(0)
        return TRANSCRIPT.copyOfRange(offset.toInt(), minOf(TRANSCRIPT.size.toLong(), offset + maxBytes).toInt())
    }

    // Fixed clock and file times, so the rendered ages never drift between runs.
    override fun now() = NOW

    override fun lastModifiedAt(path: String) = if (path.endsWith("notes.txt")) NOW - 240 else 0L

    private companion object {
        const val NOW = 1_700_000_000L

        val TRANSCRIPT = ("{\"type\":\"assistant\",\"cwd\":\"/repo\",\"requestId\":\"req_1\"," +
            "\"message\":{\"id\":\"m\",\"model\":\"claude-opus-5\",\"usage\":{" +
            "\"input_tokens\":1200,\"output_tokens\":48000,\"cache_read_input_tokens\":2400000," +
            "\"cache_creation\":{\"ephemeral_1h_input_tokens\":31000,\"ephemeral_5m_input_tokens\":0}" +
            "}}}\n").encodeToByteArray()

        val CONFLICTED_FILE = """
            package app

            fun greet(name: String): String {
            <<<<<<< HEAD
                return "Hello, ${'$'}name!"
            =======
                return "Hi there, ${'$'}name."
            >>>>>>> feature/greeting
            }
        """.trimIndent()
    }
}
