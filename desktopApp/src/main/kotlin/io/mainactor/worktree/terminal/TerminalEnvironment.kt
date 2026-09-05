package io.mainactor.worktree.terminal

import io.mainactor.worktree.platform.Os

/**
 * The environment a pane's shell is started with.
 *
 * In one place because both engines have to hand a shell exactly the same one. A difference here
 * reproduces the class of bug that only shows up in a packaged build launched from Finder — the
 * pane that answers `command not found` for something that plainly exists — and it would show up as
 * a difference between engines, which is the hardest possible place to look for it.
 */
internal fun terminalEnvironment(): Map<String, String> {
    val env = HashMap(System.getenv())
    // Tell the shell it is talking to a capable terminal, and keep pagers from taking over.
    env["TERM"] = "xterm-256color"
    env["COLORTERM"] = "truecolor"
    if (Os.isMac) env["LANG"] = env["LANG"] ?: "en_US.UTF-8"
    return env
}
