package io.mainactor.worktree.ui.dialogs

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
import io.mainactor.worktree.model.Branch
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

@Composable
fun CommitDialog(
    stagedCount: Int,
    unstagedCount: Int,
    onDismiss: () -> Unit,
    onCommit: (message: String, amend: Boolean, stageAll: Boolean) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var message by remember { mutableStateOf("") }
    var amend by remember { mutableStateOf(false) }
    var stageAll by remember { mutableStateOf(stagedCount == 0 && unstagedCount > 0) }
    val canCommit = message.isNotBlank() && (stagedCount > 0 || stageAll || amend)

    Modal(
        title = "Commit",
        onDismiss = onDismiss,
        width = 520.dp,
        footer = {
            IdeButton("Cancel", onDismiss)
            IdeButton(
                text = if (amend) "Amend" else "Commit",
                onClick = { onCommit(message, amend, stageAll) },
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
    loadWorktrees: (Project, onLoaded: (List<Worktree>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Project, Worktree) -> Unit,
) {
    val colors = LocalWorktreeColors.current
    var project by remember { mutableStateOf(initialProject ?: projects.firstOrNull()) }
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
                onClick = { project?.let { p -> chosen?.let { onConfirm(p, it) } } },
                enabled = chosen != null && project != null,
                primary = chosen != null && project != null,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
