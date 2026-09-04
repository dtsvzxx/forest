package io.mainactor.worktree.model

import io.mainactor.worktree.usage.AgentTool

/**
 * Something a pane can be started with: an agent CLI, or any command the user names.
 *
 * The command is a *shell command line* rather than an argv list because a pane runs it in a login
 * shell anyway — that is what gives an agent the same `PATH`, aliases and credentials the user gets
 * in their own terminal — and because a custom entry is typed by the user, who thinks in command
 * lines.
 */
data class AgentSpec(
    val id: String,
    val name: String,
    /** Blank means no command at all: a plain login shell, which is what a pane used to be. */
    val command: String = "",
    /**
     * The command that continues this worktree's most recent session, or null when the tool has no
     * such thing. Both supported CLIs scope "most recent" to the working directory, which is
     * exactly the worktree the pane runs in.
     */
    val resumeCommand: String? = null,
    /** The tool whose usage Forest can read for this agent, when it can read any. */
    val tool: AgentTool? = null,
    /** Built-ins cannot be renamed or removed, only switched off for a project. */
    val builtIn: Boolean = true,
) {
    val isShell: Boolean get() = command.isBlank()
}

/** The agents Forest knows how to drive itself. */
object BuiltInAgents {

    val CLAUDE = AgentSpec(
        id = "claude",
        name = "Claude Code",
        command = "claude",
        resumeCommand = "claude --continue",
        tool = AgentTool.CLAUDE,
    )

    val CODEX = AgentSpec(
        id = "codex",
        name = "Codex",
        command = "codex",
        // `--last` skips the picker; without `--all` it is already scoped to this directory.
        resumeCommand = "codex resume --last",
        tool = AgentTool.CODEX,
    )

    /** A pane with no agent in it — what every pane was before this existed. */
    val SHELL = AgentSpec(id = "shell", name = "Shell")

    val all = listOf(CLAUDE, CODEX, SHELL)

    fun byId(id: String): AgentSpec? = all.firstOrNull { it.id == id }

    /** The executable to look for on `PATH`, for deciding what to enable by default. */
    fun executableOf(spec: AgentSpec): String? = spec.command.substringBefore(' ').takeIf { it.isNotBlank() }
}

/** Which agents a project offers, and any commands the user added to it. */
data class ProjectAgents(
    val enabled: Set<String> = emptySet(),
    val custom: List<AgentSpec> = emptyList(),
) {
    /** Everything on offer, built-ins first, in the order they are declared. */
    fun available(): List<AgentSpec> =
        BuiltInAgents.all.filter { it.id in enabled } + custom.filter { it.id in enabled }

    fun all(): List<AgentSpec> = BuiltInAgents.all + custom
}
