package io.mainactor.worktree.terminal

import com.jediterm.core.Color
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import io.mainactor.worktree.platform.Os
import java.awt.Dimension
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.GraphicsEnvironment
import java.nio.charset.StandardCharsets
import javax.swing.JScrollBar

/**
 * A real terminal, not a command-output console: a login shell on a pty (pty4j) rendered by
 * JediTerm, the same emulator the IntelliJ terminal uses.
 *
 * That matters for this app specifically — the point of the embedded terminal is to run the git
 * commands the UI does not cover, in the worktree you have selected, with your own prompt,
 * aliases and pagers working exactly as they do in your own terminal.
 *
 * JediTerm is LGPL 3.0 and is linked as an unmodified library.
 */
class TerminalSessionHandle internal constructor(
    val id: String,
    val widget: JediTermWidget,
    private val process: PtyProcess,
) {
    val isAlive: Boolean get() = process.isAlive

    val hasKeyboardFocus: Boolean get() = widget.terminalPanel.hasFocus()

    /** Puts the caret in this pane, so typing goes here. */
    fun requestFocus() {
        widget.requestFocusInWindow()
    }

    fun dispose() {
        runCatching { widget.close() }
        runCatching { process.destroyForcibly() }
    }
}

/**
 * Owns the live terminal processes.
 *
 * Sessions outlive composition on purpose: switching to another tab, or hiding the tool window,
 * must not kill a running build. The manager hands the same widget back when the tab returns.
 */
class TerminalSessionManager {
    private val sessions = mutableMapOf<String, TerminalSessionHandle>()

    /**
     * Called when a pane takes the keyboard focus.
     *
     * A pane is a heavyweight Swing component, so a click inside the terminal never reaches the
     * Compose click handler around it — AWT consumes it. Without this the app's idea of "the
     * focused pane" would only ever change by clicking a header, while typing went somewhere else.
     */
    var onFocusGained: (sessionId: String) -> Unit = {}

    fun getOrCreate(
        id: String,
        workDir: String,
        title: String,
        command: String? = null,
    ): TerminalSessionHandle {
        sessions[id]?.let { existing ->
            if (existing.isAlive) return existing
            existing.dispose()
            sessions.remove(id)
        }
        val handle = create(id, workDir, title, command)
        sessions[id] = handle
        return handle
    }

    fun close(id: String) {
        sessions.remove(id)?.dispose()
    }

    fun closeAll() {
        sessions.values.forEach { it.dispose() }
        sessions.clear()
    }

    private fun create(
        id: String,
        workDir: String,
        title: String,
        command: String?,
    ): TerminalSessionHandle {
        val env = HashMap(System.getenv())
        // Tell the shell it is talking to a capable terminal, and keep pagers from taking over.
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        if (Os.isMac) env["LANG"] = env["LANG"] ?: "en_US.UTF-8"

        val process = PtyProcessBuilder()
            .setCommand(Os.shellRunning(command).toTypedArray())
            .setEnvironment(env)
            .setDirectory(workDir)
            .setInitialColumns(120)
            .setInitialRows(24)
            .setConsole(false)
            .setUseWinConPty(Os.isWindows)
            .start()

        val widget = ChromelessTerminalWidget(IdeTerminalSettings())
        widget.ttyConnector = PtyTtyConnector(process, title)
        widget.start()
        widget.terminalPanel.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                onFocusGained(id)
            }
        })

        return TerminalSessionHandle(id, widget, process)
    }
}

/**
 * A terminal without JediTerm's own scrollbar.
 *
 * That scrollbar is a plain Swing component and looks nothing like the rest of the window. It
 * cannot simply be dropped: the widget wires the terminal panel to its model, which is how
 * scrolling and the search highlights work — so the object is kept and only hidden, which also
 * gives the text the full width of the pane.
 */
private class ChromelessTerminalWidget(settings: SettingsProvider) : JediTermWidget(settings) {

    override fun createScrollBar(): JScrollBar = super.createScrollBar().apply {
        isVisible = false
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
    }
}

/** Bridges JediTerm's TTY abstraction to a pty4j process, including window-size propagation. */
private class PtyTtyConnector(
    private val process: PtyProcess,
    private val title: String,
) : ProcessTtyConnector(process, StandardCharsets.UTF_8) {

    override fun getName(): String = title

    override fun resize(termSize: TermSize) {
        // Without this the shell keeps its initial 120x24 and every full-screen program (vim,
        // less, git's own pager) wraps at the wrong column.
        runCatching { process.winSize = WinSize(termSize.columns, termSize.rows) }
    }
}

/** Terminal colours matched to the app's New UI palette. */
internal class IdeTerminalSettings : DefaultSettingsProvider() {

    override fun getTerminalColorPalette(): ColorPalette = IdeConsolePalette

    /**
     * New UI: Gray12 on Gray1, matching the app's editor surface.
     *
     * All three are overridden on purpose. `getDefaultStyle` is deprecated but is still what the
     * other two derive from, and it is the *style* that cells carry and that the block cursor is
     * painted from — leaving JediTerm's black-on-white default in place there put a black cursor
     * on a black background. The derived getters are overridden as well so the colours do not
     * depend on which of the two paths a given call takes.
     */
    @Suppress("DEPRECATION")
    @Deprecated("Overrides a deprecated member; still the source the others derive from.")
    override fun getDefaultStyle(): TextStyle = TextStyle(DEFAULT_FOREGROUND, DEFAULT_BACKGROUND)

    override fun getDefaultForeground(): TerminalColor = DEFAULT_FOREGROUND

    override fun getDefaultBackground(): TerminalColor = DEFAULT_BACKGROUND

    override fun getSelectionColor(): TextStyle =
        TextStyle(TerminalColor.rgb(0xDF, 0xE1, 0xE5), TerminalColor.rgb(0x2E, 0x43, 0x6E))

    // Must not be called `terminalFont`: DefaultSettingsProvider is Java, so Kotlin exposes its
    // getTerminalFont() as a synthetic `terminalFont` property, and the name would resolve to this
    // very override instead of the companion's value.
    override fun getTerminalFont(): Font = preferredMonospacedFont

    override fun getTerminalFontSize(): Float = 13f

    override fun getLineSpacing(): Float = 1.05f

    override fun audibleBell(): Boolean = false

    override fun copyOnSelect(): Boolean = false

    override fun caretBlinkingMs(): Int = 505

    override fun getBufferMaxLinesCount(): Int = 10_000

    companion object {
        val DEFAULT_FOREGROUND: TerminalColor = TerminalColor.rgb(0xDF, 0xE1, 0xE5)
        val DEFAULT_BACKGROUND: TerminalColor = TerminalColor.rgb(0x1E, 0x1F, 0x22)

        /** First monospaced family that is actually installed, preferring the IDE's own. */
        val preferredMonospacedFont: Font by lazy {
            val installed = runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
            }.getOrDefault(emptySet())
            val preferred = listOf(
                "JetBrains Mono", "SF Mono", "Menlo", "DejaVu Sans Mono",
                "Cascadia Mono", "Consolas", "Liberation Mono", "Monospaced",
            )
            val family = preferred.firstOrNull { it in installed } ?: Font.MONOSPACED
            Font(family, Font.PLAIN, 13)
        }
    }
}

/**
 * The 16 ANSI colours exactly as the IDE console defines them (`CONSOLE_*_OUTPUT` in the dark
 * scheme), plus the xterm-256 cube — so coloured `git` and build output looks the same here as
 * it does in the IDE's own terminal.
 */
private object IdeConsolePalette : ColorPalette() {

    private val ansi = arrayOf(
        Color(0x00, 0x00, 0x00), // black
        Color(0xF0, 0x52, 0x4F), // red
        Color(0x5C, 0x96, 0x2C), // green
        Color(0xA6, 0x8A, 0x0D), // yellow
        Color(0x39, 0x93, 0xD4), // blue
        Color(0xA7, 0x71, 0xBF), // magenta
        Color(0x00, 0xA3, 0xA3), // cyan
        Color(0x80, 0x80, 0x80), // white
        Color(0x59, 0x59, 0x59), // bright black
        Color(0xFF, 0x40, 0x50), // bright red
        Color(0x4F, 0xC4, 0x14), // bright green
        Color(0xE5, 0xBF, 0x00), // bright yellow
        Color(0x1F, 0xB0, 0xFF), // bright blue
        Color(0xED, 0x7E, 0xED), // bright magenta
        Color(0x00, 0xE5, 0xE5), // bright cyan
        Color(0xFF, 0xFF, 0xFF), // bright white
    )

    override fun getForegroundByColorIndex(colorIndex: Int): Color = resolve(colorIndex)

    override fun getBackgroundByColorIndex(colorIndex: Int): Color = resolve(colorIndex)

    private fun resolve(index: Int): Color = when {
        index < ansi.size -> ansi[index]
        index < 232 -> cubeColor(index - 16)
        index < 256 -> greyscale(index - 232)
        else -> ansi[7]
    }

    /** xterm's 6×6×6 colour cube. */
    private fun cubeColor(offset: Int): Color {
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        return Color(steps[offset / 36 % 6], steps[offset / 6 % 6], steps[offset % 6])
    }

    private fun greyscale(step: Int): Color {
        val v = 8 + step * 10
        return Color(v, v, v)
    }
}
