package io.mainactor.worktree.ui

/**
 * What the agent-wall shortcuts are called on screen.
 *
 * The bindings themselves are registered in the platform layer, because a terminal pane holds the
 * keyboard focus and only a global key hook sees the chord at all. These labels are set from the
 * same place so the tooltips can never drift from what the keys actually do.
 */
object AgentShortcuts {
    var splitRight: String = ""
        private set
    var splitDown: String = ""
        private set
    var newAgent: String = ""
        private set
    var closePane: String = ""
        private set
    var zoomPane: String = ""
        private set

    fun describe(
        splitRight: String,
        splitDown: String,
        newAgent: String,
        closePane: String,
        zoomPane: String,
    ) {
        this.splitRight = splitRight
        this.splitDown = splitDown
        this.newAgent = newAgent
        this.closePane = closePane
        this.zoomPane = zoomPane
    }
}
