package io.mainactor.worktree.ui.dialogs

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mainactor.worktree.model.Task
import io.mainactor.worktree.model.Branch
import io.mainactor.worktree.ui.components.ToolButton
import io.mainactor.worktree.CommandFailure
import io.mainactor.worktree.ui.components.Tooltip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.mainactor.worktree.model.AgentSpec
import io.mainactor.worktree.model.BuiltInAgents
import io.mainactor.worktree.model.ProjectAgents
import io.mainactor.worktree.model.Project
import io.mainactor.worktree.model.Worktree
import io.mainactor.worktree.ui.components.Badge
import io.mainactor.worktree.ui.components.IconKind
import io.mainactor.worktree.ui.components.IdeButton
import io.mainactor.worktree.ui.components.IdeIcon
import io.mainactor.worktree.ui.components.IdeTextField
import io.mainactor.worktree.ui.components.ListRow
import io.mainactor.worktree.ui.theme.CodeTextStyle
import io.mainactor.worktree.ui.theme.Dimens
import io.mainactor.worktree.ui.theme.LocalWorktreeColors

/**
 * Modal shell drawn inside the main window.
 *
 * An in-window modal keeps every dialog in plain common code — no per-platform window handling —
 * and dims the app behind it the way the IDE's own modal dialogs do.
 */
@Composable
fun Modal(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 460.dp,
    footer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalWorktreeColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // Swallow clicks so they never reach the panes behind the modal.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = modifier
                .width(width)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.panel)
                .border(1.dp, colors.separator, RoundedCornerShape(12.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = colors.text, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                io.mainactor.worktree.ui.components.ToolButton(
                    icon = IconKind.CLOSE,
                    tooltip = "Close",
                    onClick = onDismiss,
                    modifier = Modifier.size(20.dp),
                )
            }
            content()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                footer()
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    val colors = LocalWorktreeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) colors.accent else Color.Transparent)
                .border(1.dp, if (selected) colors.accent else colors.controlBorder, RoundedCornerShape(6.dp)),
        )
        Text(label, color = colors.text, style = MaterialTheme.typography.bodySmall)
        if (detail != null) Text(detail, color = colors.textDisabled, fontSize = 11.sp)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier.clickable(onClick = onToggle).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(13.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) colors.accent else Color.Transparent)
                .border(1.dp, if (checked) colors.accent else colors.controlBorder, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) IdeIcon(IconKind.CHECK, Color.White, size = 9.dp)
        }
        Text(label, color = colors.text, style = MaterialTheme.typography.bodySmall)
    }
}

/** How the new worktree gets a branch. */
enum class BranchMode { NEW_BRANCH, EXISTING_BRANCH, DETACHED }

data class NewWorktreeRequest(
    val path: String,
    val newBranch: String?,
    val existingBranch: String?,
    val baseRef: String?,
    val force: Boolean,
)

@Composable
fun NewWorktreeDialog(
    branches: List<Branch>,
    defaultParent: String,
    suggestedBase: String?,
    onBrowse: (onPicked: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (NewWorktreeRequest) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var mode by remember { mutableStateOf(BranchMode.NEW_BRANCH) }
    var branchName by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf(defaultParent) }
    var folder by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(suggestedBase.orEmpty()) }
    var existing by remember { mutableStateOf("") }
    var force by remember { mutableStateOf(false) }

    // A branch already checked out elsewhere cannot be checked out again — git refuses, so the
    // picker refuses first and says where it lives.
    val available = remember(branches) { branches.filter { it.checkedOutIn == null } }

    // Folder name defaults to the branch name, with slashes flattened so nesting stays shallow.
    val effectiveFolder = folder.ifBlank {
        when (mode) {
            BranchMode.NEW_BRANCH -> branchName.replace('/', '-')
            BranchMode.EXISTING_BRANCH -> existing.substringAfterLast('/')
            BranchMode.DETACHED -> base.take(12)
        }
    }
    val path = if (parent.isBlank() || effectiveFolder.isBlank()) "" else "$parent/$effectiveFolder"
    val valid = path.isNotBlank() && when (mode) {
        BranchMode.NEW_BRANCH -> branchName.isNotBlank()
        BranchMode.EXISTING_BRANCH -> existing.isNotBlank()
        BranchMode.DETACHED -> base.isNotBlank()
    }

    Modal(
        title = "New worktree",
        onDismiss = onDismiss,
        width = 520.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = "Create",
                onClick = {
                    onConfirm(
                        NewWorktreeRequest(
                            path = path,
                            newBranch = branchName.takeIf { mode == BranchMode.NEW_BRANCH },
                            existingBranch = existing.takeIf { mode == BranchMode.EXISTING_BRANCH },
                            baseRef = base.takeIf { it.isNotBlank() && mode != BranchMode.EXISTING_BRANCH },
                            force = force,
                        )
                    )
                },
                enabled = valid,
                primary = valid,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Branch") {
                Column {
                    RadioRow("Create new branch", mode == BranchMode.NEW_BRANCH) { mode = BranchMode.NEW_BRANCH }
                    if (mode == BranchMode.NEW_BRANCH) {
                        IdeTextField(
                            value = branchName,
                            onValueChange = { branchName = it },
                            placeholder = "feature/my-change",
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 4.dp),
                        )
                    }
                    RadioRow("Check out existing branch", mode == BranchMode.EXISTING_BRANCH) {
                        mode = BranchMode.EXISTING_BRANCH
                    }
                    if (mode == BranchMode.EXISTING_BRANCH) {
                        BranchList(
                            branches = available,
                            selected = existing,
                            onSelect = { existing = it },
                            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                        )
                    }
                    RadioRow("Detached HEAD", mode == BranchMode.DETACHED) { mode = BranchMode.DETACHED }
                }
            }

            if (mode != BranchMode.EXISTING_BRANCH) {
                Field(if (mode == BranchMode.DETACHED) "Checkout ref" else "Start point") {
                    IdeTextField(
                        value = base,
                        onValueChange = { base = it },
                        placeholder = "HEAD, main, a commit hash…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Field("Location") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IdeTextField(
                        value = parent,
                        onValueChange = { parent = it },
                        modifier = Modifier.weight(1f),
                    )
                    IdeButton("Browse…", { onBrowse { parent = it } })
                }
                IdeTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    placeholder = effectiveFolder.ifBlank { "folder name" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (path.isNotBlank()) {
                Text(
                    text = path,
                    color = colors.textDisabled,
                    style = CodeTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }

            CheckRow("Force (reuse a non-empty folder / already-checked-out branch)", force) { force = !force }
        }
    }
}

@Composable
private fun BranchList(
    branches: List<Branch>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorktreeColors.current
    var filter by remember { mutableStateOf("") }
    val shown = remember(branches, filter) {
        if (filter.isBlank()) branches else branches.filter { it.name.contains(filter, ignoreCase = true) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IdeTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = "Filter branches…",
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp)
                .clip(RoundedCornerShape(Dimens.arc))
                .background(colors.editor)
                .border(1.dp, colors.controlBorder, RoundedCornerShape(Dimens.arc)),
        ) {
            LazyColumn {
                items(shown, key = { "${it.isRemote}:${it.name}" }) { branch ->
                    ListRow(
                        selected = branch.name == selected,
                        onClick = { onSelect(branch.name) },
                        height = 22.dp,
                    ) {
                        IdeIcon(
                            IconKind.BRANCH,
                            if (branch.isRemote) colors.branchRemote else colors.branchLocal,
                            size = 11.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            branch.name,
                            color = colors.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (branch.isCurrent) Badge("current", colors.added)
                    }
                }
            }
        }
    }
}

/**
 * @param pushCommand what pushing would run, shown beside the checkbox the way a `ToolButton`'s
 *   `detail` does — a branch with no upstream is about to get one, and that is worth seeing before
 *   the commit rather than after it.
 */
@Composable
fun CommitDialog(
    stagedCount: Int,
    unstagedCount: Int,
    pushCommand: String,
    onDismiss: () -> Unit,
    onCommit: (message: String, amend: Boolean, stageAll: Boolean, push: Boolean) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var message by remember { mutableStateOf("") }
    var amend by remember { mutableStateOf(false) }
    var stageAll by remember { mutableStateOf(stagedCount == 0 && unstagedCount > 0) }
    // Off every time the dialog opens: pushing leaves the machine, and a box that remembers itself
    // would publish a commit at some point because it was ticked for a different one.
    var push by remember { mutableStateOf(false) }
    val canCommit = message.isNotBlank() && (stagedCount > 0 || stageAll || amend)

    Modal(
        title = "Commit",
        onDismiss = onDismiss,
        width = 520.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = when {
                    amend && push -> "Amend and push"
                    amend -> "Amend"
                    push -> "Commit and push"
                    else -> "Commit"
                },
                onClick = { onCommit(message, amend, stageAll, push) },
                enabled = canCommit,
                primary = canCommit,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge("$stagedCount staged", if (stagedCount > 0) colors.added else colors.textDim)
                Badge("$unstagedCount unstaged", if (unstagedCount > 0) colors.modified else colors.textDim)
            }
            Field("Message") {
                IdeTextField(
                    value = message,
                    onValueChange = { message = it },
                    placeholder = "Describe the change",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                )
            }
            CheckRow("Stage all changes first (git commit --all)", stageAll) { stageAll = !stageAll }
            CheckRow("Amend the previous commit", amend) { amend = !amend }
            CheckRow("Push afterwards ($pushCommand)", push) { push = !push }
            if (amend && push) {
                Text(
                    // Amending rewrites the commit; the remote refuses that unless it never had it.
                    text = "A commit the remote already has will reject an amended one — push that " +
                        "by hand, with a force.",
                    color = colors.textDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Points an existing worktree at a different branch.
 *
 * Branches that another worktree already has checked out are left out of the list: git refuses
 * them outright, so offering them would only produce an error.
 */
@Composable
fun SwitchBranchDialog(
    worktreeFolder: String,
    worktreePath: String,
    branches: List<Branch>,
    currentBranch: String?,
    dirtyFiles: Int,
    onDismiss: () -> Unit,
    onSwitch: (Branch) -> Unit,
    onCreate: (name: String, startPoint: String?) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var createNew by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var startPoint by remember { mutableStateOf(currentBranch.orEmpty()) }

    val takenElsewhere = remember(branches, worktreePath) {
        branches.count { it.checkedOutIn != null && it.checkedOutIn != worktreePath }
    }
    val available = remember(branches, worktreePath, currentBranch) {
        branches.filter { branch ->
            branch.name != currentBranch &&
                (branch.checkedOutIn == null || branch.checkedOutIn == worktreePath)
        }
    }
    val valid = if (createNew) newName.isNotBlank() else selected.isNotBlank()

    Modal(
        title = "Switch branch",
        onDismiss = onDismiss,
        width = 480.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = if (createNew) "Create and switch" else "Switch",
                onClick = {
                    if (createNew) {
                        onCreate(newName, startPoint.takeIf { it.isNotBlank() })
                    } else {
                        available.firstOrNull { it.name == selected }?.let(onSwitch)
                    }
                },
                enabled = valid,
                primary = valid,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Worktree $worktreeFolder is on ${currentBranch ?: "a detached HEAD"}.",
                color = colors.textDim,
                style = MaterialTheme.typography.bodySmall,
            )

            RadioRow("Switch to an existing branch", !createNew) { createNew = false }
            if (!createNew) {
                Column(Modifier.padding(start = 20.dp)) {
                    BranchList(branches = available, selected = selected, onSelect = { selected = it })
                    if (takenElsewhere > 0) {
                        Text(
                            text = "$takenElsewhere branch(es) hidden — checked out in another worktree.",
                            color = colors.textDisabled,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            RadioRow("Create a new branch here", createNew) { createNew = true }
            if (createNew) {
                Column(
                    modifier = Modifier.padding(start = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IdeTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "feature/my-change",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    IdeTextField(
                        value = startPoint,
                        onValueChange = { startPoint = it },
                        placeholder = "start point (HEAD, main, a commit…)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (dirtyFiles > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IdeIcon(IconKind.WARNING, colors.warning, size = 12.dp)
                    Text(
                        text = "$dirtyFiles uncommitted change(s) come along, or git refuses the switch.",
                        color = colors.warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * Chooses where a new agent runs: which repository, then which of its worktrees.
 *
 * The wall is not tied to the open project — an agent in another repository is a perfectly normal
 * thing to want — so the worktrees of whatever is picked here are read on demand rather than taken
 * from the loaded project.
 */
@Composable
fun NewAgentDialog(
    title: String,
    projects: List<Project>,
    initialProject: Project?,
    initialWorktreePath: String?,
    agents: List<AgentSpec>,
    initialAgentId: String?,
    loadWorktrees: (Project, onLoaded: (List<Worktree>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Project, Worktree, AgentSpec?) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var project by remember { mutableStateOf(initialProject ?: projects.firstOrNull()) }
    var agent by remember {
        mutableStateOf(agents.firstOrNull { it.id == initialAgentId } ?: agents.firstOrNull())
    }
    var worktrees by remember { mutableStateOf<List<Worktree>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var selectedPath by remember { mutableStateOf(initialWorktreePath.orEmpty()) }

    LaunchedEffect(project) {
        val current = project ?: return@LaunchedEffect
        loading = true
        loadWorktrees(current) { loaded ->
            worktrees = loaded.filterNot { it.isBare }
            loading = false
            if (worktrees.none { it.path == selectedPath }) {
                selectedPath = worktrees.firstOrNull()?.path.orEmpty()
            }
        }
    }

    val chosen = worktrees.firstOrNull { it.path == selectedPath }

    Modal(
        title = title,
        onDismiss = onDismiss,
        width = 480.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = "Start",
                onClick = { project?.let { p -> chosen?.let { onConfirm(p, it, agent) } } },
                enabled = chosen != null && project != null,
                primary = chosen != null && project != null,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (agents.size > 1) {
                Field("Agent") {
                    AgentChips(agents = agents, selected = agent, onSelect = { agent = it })
                }
            }

            Field("Project") {
                ProjectPicker(projects = projects, selected = project, onSelect = { project = it })
            }

            Field("Worktree") {
                when {
                    loading -> Text(
                        "Reading worktrees…",
                        color = colors.textDim,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    worktrees.isEmpty() -> Text(
                        "No worktrees found in this repository.",
                        color = colors.textDisabled,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    else -> Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(Dimens.arc))
                            .background(colors.editor)
                            .border(1.dp, colors.controlBorder, RoundedCornerShape(Dimens.arc)),
                    ) {
                        LazyColumn {
                            items(worktrees, key = { it.path }) { worktree ->
                                ListRow(
                                    selected = worktree.path == selectedPath,
                                    onClick = { selectedPath = worktree.path },
                                    height = 26.dp,
                                ) {
                                    IdeIcon(
                                        icon = if (worktree.isMain) IconKind.HOME else IconKind.BRANCH,
                                        tint = colors.textDim,
                                        size = 11.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = worktree.label,
                                        color = colors.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // The list is ordered by activity, same as the worktrees pane,
                                    // so the age has to be visible here too.
                                    worktree.lastActivityLabel?.let { age ->
                                        Text(
                                            text = age,
                                            color = colors.textDisabled,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            chosen?.let {
                Text(
                    text = it.path,
                    color = colors.textDisabled,
                    style = CodeTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

@Composable
private fun ProjectPicker(
    projects: List<Project>,
    selected: Project?,
    onSelect: (Project) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(Dimens.arc))
                .background(colors.panel)
                .border(1.dp, colors.controlBorder, RoundedCornerShape(Dimens.arc))
                .clickable { open = true }
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IdeIcon(IconKind.FOLDER, colors.textDim, size = 12.dp)
            Text(
                text = selected?.name ?: "choose a repository",
                color = if (selected == null) colors.textDisabled else colors.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IdeIcon(IconKind.CHEVRON_DOWN, colors.textDim, size = 10.dp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.panelAlt),
        ) {
            projects.forEach { candidate ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = candidate.name,
                            color = colors.text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(candidate)
                    },
                )
            }
        }
    }
}

/** Pick a branch to merge into the current one. */
@Composable
fun MergeDialog(
    branches: List<Branch>,
    currentBranch: String?,
    onDismiss: () -> Unit,
    onConfirm: (ref: String, noFastForward: Boolean) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var selected by remember { mutableStateOf("") }
    var noFastForward by remember { mutableStateOf(false) }
    val candidates = remember(branches, currentBranch) { branches.filterNot { it.name == currentBranch } }

    Modal(
        title = "Merge into ${currentBranch ?: "HEAD"}",
        onDismiss = onDismiss,
        width = 460.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = "Merge",
                onClick = { onConfirm(selected, noFastForward) },
                enabled = selected.isNotBlank(),
                primary = selected.isNotBlank(),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Merge the selected branch into this worktree's branch.",
                color = colors.textDim,
                style = MaterialTheme.typography.bodySmall,
            )
            BranchList(branches = candidates, selected = selected, onSelect = { selected = it })
            CheckRow("Always create a merge commit (--no-ff)", noFastForward) { noFastForward = !noFastForward }
        }
    }
}

/** Pick the ref to replay the current branch onto. */
@Composable
fun RebaseDialog(
    branches: List<Branch>,
    currentBranch: String?,
    onDismiss: () -> Unit,
    onConfirm: (onto: String) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var selected by remember { mutableStateOf("") }
    val candidates = remember(branches, currentBranch) { branches.filterNot { it.name == currentBranch } }

    Modal(
        title = "Rebase ${currentBranch ?: "HEAD"}",
        onDismiss = onDismiss,
        width = 460.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = "Rebase",
                onClick = { onConfirm(selected) },
                enabled = selected.isNotBlank(),
                primary = selected.isNotBlank(),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Replay this branch's commits on top of the selected ref. Conflicts stop the rebase " +
                    "and open in the Conflicts tab.",
                color = colors.textDim,
                style = MaterialTheme.typography.bodySmall,
            )
            BranchList(branches = candidates, selected = selected, onSelect = { selected = it })
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalWorktreeColors.current
    Modal(
        title = title,
        onDismiss = onDismiss,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(confirmLabel, onConfirm, primary = !destructive)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = colors.text, style = MaterialTheme.typography.bodySmall)
            extra?.invoke()
        }
    }
}

@Composable
fun RemoveWorktreeDialog(
    name: String,
    path: String,
    dirtyFiles: Int,
    onDismiss: () -> Unit,
    onConfirm: (force: Boolean) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var force by remember { mutableStateOf(false) }
    Modal(
        title = "Remove worktree",
        onDismiss = onDismiss,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton("Remove", { onConfirm(force) })
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Remove the worktree \"$name\"? The directory and its contents are deleted; the branch itself is kept.",
                color = colors.text,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(path, color = colors.textDisabled, style = CodeTextStyle, maxLines = 2)
            if (dirtyFiles > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IdeIcon(IconKind.WARNING, colors.warning, size = 12.dp)
                    Text(
                        "$dirtyFiles uncommitted change(s) will be lost — git needs Force to proceed.",
                        color = colors.warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            CheckRow("Force removal", force) { force = !force }
        }
    }
}

@Composable
fun CloneDialog(
    defaultParent: String,
    onBrowse: (onPicked: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (url: String, parent: String, folder: String) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var url by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf(defaultParent) }
    var folder by remember { mutableStateOf("") }

    val suggested = remember(url) {
        url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
    }
    val effectiveFolder = folder.ifBlank { suggested }
    val valid = url.isNotBlank() && parent.isNotBlank() && effectiveFolder.isNotBlank()

    Modal(
        title = "Clone repository",
        onDismiss = onDismiss,
        width = 520.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = "Clone",
                onClick = { onConfirm(url, parent, effectiveFolder) },
                enabled = valid,
                primary = valid,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Repository URL") {
                IdeTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "git@github.com:owner/repo.git",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Field("Parent directory") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IdeTextField(value = parent, onValueChange = { parent = it }, modifier = Modifier.weight(1f))
                    IdeButton("Browse…", { onBrowse { parent = it } })
                }
            }
            Field("Folder") {
                IdeTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    placeholder = suggested.ifBlank { "folder name" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "Credentials come from your git credential helper or SSH agent; this app never prompts for them.",
                color = colors.textDisabled,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * The agents on offer, as chips rather than a dropdown.
 *
 * There are only ever a handful, and which ones a project offers is itself a setting — showing them
 * all is the difference between "pick from what this project has" and "go and find out what it has".
 */
@Composable
private fun AgentChips(agents: List<AgentSpec>, selected: AgentSpec?, onSelect: (AgentSpec) -> Unit) {
    val colors = LocalWorktreeColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        agents.forEach { spec ->
            val active = spec.id == selected?.id
            Box(
                Modifier
                    .clip(RoundedCornerShape(Dimens.arc))
                    .background(if (active) colors.selection else colors.panelAlt)
                    .clickable { onSelect(spec) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = spec.name,
                    color = if (active) colors.text else colors.textDim,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Which agents a project offers, and any commands the user has added to it.
 *
 * The built-ins are always listed, whether or not their executable could be found: detection reads
 * a `PATH` that a windowed app does not fully inherit, so hiding a tool on that basis would hide
 * one the user can run perfectly well. It is said out loud instead, and the tick is theirs.
 */
@Composable
fun AgentSettingsDialog(
    projectName: String,
    agents: ProjectAgents,
    isInstalled: (AgentSpec) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ProjectAgents) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var enabled by remember { mutableStateOf(agents.enabled) }
    var custom by remember { mutableStateOf(agents.custom) }
    var newName by remember { mutableStateOf("") }
    var newCommand by remember { mutableStateOf("") }
    var newBackground by remember { mutableStateOf(false) }

    fun toggle(id: String) {
        enabled = if (id in enabled) enabled - id else enabled + id
    }

    Modal(
        title = "Agents in $projectName",
        onDismiss = onDismiss,
        width = 520.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = "Save",
                onClick = { onConfirm(ProjectAgents(enabled = enabled, custom = custom)) },
                primary = true,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Built in") {
                Column {
                    BuiltInAgents.all.forEach { spec ->
                        AgentToggleRow(
                            name = spec.name,
                            detail = when {
                                spec.isShell -> "A plain login shell, no agent"
                                !isInstalled(spec) -> "${spec.command} — not found on PATH"
                                spec.resumeCommand != null -> "${spec.resumeCommand} when the worktree has a session"
                                else -> spec.command
                            },
                            checked = spec.id in enabled,
                            onToggle = { toggle(spec.id) },
                        )
                    }
                }
            }

            if (custom.isNotEmpty()) {
                Field("Your commands") {
                    Column {
                        custom.forEach { spec ->
                            AgentToggleRow(
                                name = spec.name,
                                detail = spec.command,
                                checked = spec.id in enabled,
                                onToggle = { toggle(spec.id) },
                                mode = if (spec.background) "background" else "pane",
                                onToggleMode = {
                                    custom = custom.map {
                                        if (it.id == spec.id) it.copy(background = !it.background) else it
                                    }
                                },
                                onRemove = {
                                    custom = custom - spec
                                    enabled = enabled - spec.id
                                },
                            )
                        }
                    }
                }
            }

            Field("Add a command") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IdeTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "Name",
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(140.dp),
                    )
                    IdeTextField(
                        value = newCommand,
                        onValueChange = { newCommand = it },
                        placeholder = "Command to run in the worktree",
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    ModeChip(
                        background = newBackground,
                        onToggle = { newBackground = !newBackground },
                    )
                    IdeButton(
                        text = "Add",
                        enabled = newName.isNotBlank() && newCommand.isNotBlank(),
                        onClick = {
                            val id = "custom-${newName.trim().lowercase().replace(' ', '-')}"
                            custom = custom + AgentSpec(
                                id = id,
                                name = newName.trim(),
                                command = newCommand.trim(),
                                builtIn = false,
                                background = newBackground,
                            )
                            enabled = enabled + id
                            newName = ""
                            newCommand = ""
                        },
                    )
                }
            }

            Text(
                text = "A pane runs its command in a login shell and keeps the shell when it exits. " +
                    "A background command opens nothing: it runs quietly and only says anything if " +
                    "it fails, and then it shows you the output.",
                color = colors.textDisabled,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AgentToggleRow(
    name: String,
    detail: String,
    checked: Boolean,
    onToggle: () -> Unit,
    mode: String? = null,
    onToggleMode: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = LocalWorktreeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(Dimens.selectionArc))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) colors.accent else colors.panelAlt)
                .border(1.dp, colors.controlBorder, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) IdeIcon(IconKind.CHECK, Color.White, size = 10.dp)
        }
        Text(name, color = colors.text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Text(
            text = detail,
            color = colors.textDisabled,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (mode != null && onToggleMode != null) {
            ModeChip(background = mode == "background", onToggle = onToggleMode)
        }
        if (onRemove != null) {
            ToolButton(
                icon = IconKind.CLOSE,
                tooltip = "Remove this command",
                onClick = onRemove,
                modifier = Modifier.size(18.dp),
                tint = colors.textDim,
            )
        }
    }
}

/**
 * Renames a worktree: the folder it lives in, the branch it has checked out, or both.
 *
 * Both, because they are usually named after each other — a folder called `repo-FM-3713` holding a
 * branch called `FM-3713` — and renaming one alone is the reliable way to make them disagree.
 * Either field can be left as it is.
 */
@Composable
fun RenameWorktreeDialog(
    worktree: Worktree,
    onDismiss: () -> Unit,
    onConfirm: (folderName: String?, branchName: String?) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    val currentBranch = worktree.branch.orEmpty()
    var folder by remember { mutableStateOf(worktree.name) }
    var branch by remember { mutableStateOf(currentBranch) }

    // Git refuses to move the main working tree: it is the repository, not one of the checkouts
    // around it.
    val canMove = !worktree.isMain
    val canRename = currentBranch.isNotEmpty()

    val folderChanged = canMove && folder.trim() != worktree.name
    val branchChanged = canRename && branch.trim() != currentBranch
    val badFolder = folder.trim().let { it.isEmpty() || '/' in it || '\\' in it }
    val badBranch = branch.trim().isEmpty()
    val valid = (folderChanged || branchChanged) &&
        (!folderChanged || !badFolder) &&
        (!branchChanged || !badBranch)

    Modal(
        title = "Rename worktree",
        onDismiss = onDismiss,
        width = 460.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = "Rename",
                onClick = {
                    onConfirm(
                        folder.trim().takeIf { folderChanged },
                        branch.trim().takeIf { branchChanged },
                    )
                },
                enabled = valid,
                primary = valid,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Folder") {
                IdeTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    enabled = canMove,
                    placeholder = worktree.name,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Field("Branch") {
                IdeTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    enabled = canRename,
                    placeholder = if (canRename) currentBranch else "detached",
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val hint = when {
                !canMove -> "This is the main working tree — git cannot move it, so only its " +
                    "branch can be renamed here."
                !canRename -> "This worktree is detached, so there is no branch to rename."
                worktree.isLocked -> "This worktree is locked; unlock it first or the move will be " +
                    "refused."
                badFolder && folder.trim().isNotEmpty() -> "A folder name, not a path."
                else -> "Uncommitted work moves with the folder, and any agent running in it keeps " +
                    "running."
            }
            Text(
                text = hint,
                color = if (badFolder && folder.trim().isNotEmpty()) colors.error else colors.textDisabled,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Switches one command between opening a pane and running with nothing on screen. */
@Composable
private fun ModeChip(background: Boolean, onToggle: () -> Unit) {
    val colors = LocalWorktreeColors.current
    Tooltip(
        text = if (background) "Runs with no terminal" else "Opens a pane",
        detail = if (background) {
            "Nothing appears unless it fails, and then you get its output."
        } else {
            "A pane opens on the agent wall and stays after the command exits."
        },
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(colors.panelAlt)
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = if (background) "background" else "pane",
                color = colors.textDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * What a background command said when it failed.
 *
 * The output is the whole point: a command that runs with nothing on screen has no other way to
 * explain itself, and "it failed" without the reason is worse than not running it.
 */
@Composable
fun CommandFailureDialog(
    failure: CommandFailure,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalWorktreeColors.current
    val scroll = rememberScrollState()

    Modal(
        title = "${failure.name} failed in ${failure.worktree}",
        onDismiss = onDismiss,
        width = 620.dp,
        footer = {
            IdeButton("Copy output", { onCopy(failure.output) })
            IdeButton("Close", onDismiss, primary = true)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = failure.commandLine,
                    color = colors.textDim,
                    style = CodeTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "exit ${failure.exitCode}",
                    color = colors.conflicted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(Dimens.arc))
                    .background(colors.editor)
                    .padding(8.dp)
                    .verticalScroll(scroll),
            ) {
                Text(failure.output, color = colors.text, style = CodeTextStyle)
            }
        }
    }
}

/**
 * Picks the idea to hand to a running agent.
 *
 * A list and nothing else. The moment this is open you already know which task you want — you
 * wrote it — so the dialog's whole job is to be out of the way in one click, and the body is shown
 * beneath each title only far enough to tell two similar ideas apart.
 */
@Composable
fun SendTaskDialog(
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onSend: (Task) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var selected by remember { mutableStateOf(tasks.firstOrNull()?.id) }
    val chosen = tasks.firstOrNull { it.id == selected }

    Modal(
        title = "Send a task to this agent",
        onDismiss = onDismiss,
        width = 560.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = "Send",
                onClick = { chosen?.let(onSend) },
                enabled = chosen != null && !chosen.isEmpty,
                primary = chosen != null && !chosen.isEmpty,
            )
        },
    ) {
        if (tasks.isEmpty()) {
            Text(
                "Nothing left on the list. The Tasks tab is where work goes.",
                color = colors.textDim,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Modal
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                // Said before it happens, because it happens the moment the button is pressed.
                "The task is pasted into the pane and submitted.",
                color = colors.textDim,
                style = MaterialTheme.typography.bodySmall,
            )
            // Sized to the tasks, not to the cap: a `LazyColumn` fills the height it is offered,
            // so `heightIn(max = …)` alone leaves a dialog of empty space under two tasks.
            Box(Modifier.fillMaxWidth().height((TASK_ROW_HEIGHT * tasks.size).coerceAtMost(TASK_LIST_MAX))) {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    items(tasks, key = { it.id }) { task ->
                        ListRow(
                            selected = task.id == selected,
                            onClick = { selected = task.id },
                            height = TASK_ROW_HEIGHT,
                            padding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    task.title,
                                    color = if (task.isEmpty) colors.textDisabled else colors.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (task.preview.isNotEmpty()) {
                                    Text(
                                        task.preview,
                                        color = colors.textDisabled,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** Two lines of text: a task's first line and its second. */
private val TASK_ROW_HEIGHT = 44.dp

/** Past this the list scrolls rather than growing the dialog past the window. */
private val TASK_LIST_MAX = 320.dp
