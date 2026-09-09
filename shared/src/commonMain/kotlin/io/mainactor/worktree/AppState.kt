package io.mainactor.worktree

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.mainactor.worktree.git.Git
import io.mainactor.worktree.git.GitLogEntry
import io.mainactor.worktree.git.GitParsers
import io.mainactor.worktree.git.WorktreeActivity
import io.mainactor.worktree.model.Branch
import io.mainactor.worktree.model.Task
import io.mainactor.worktree.model.ChangedFile
import io.mainactor.worktree.model.CommitInfo
import io.mainactor.worktree.model.ConflictSegment
import io.mainactor.worktree.model.ConflictedFile
import io.mainactor.worktree.model.FileDiff
import io.mainactor.worktree.model.Project
import io.mainactor.worktree.model.RepoOperation
import io.mainactor.worktree.model.PaneNode
import io.mainactor.worktree.model.RepoStatus
import io.mainactor.worktree.model.AgentSpec
import io.mainactor.worktree.model.BuiltInAgents
import io.mainactor.worktree.model.ProjectAgents
import io.mainactor.worktree.model.SplitAxis
import io.mainactor.worktree.model.contains
import io.mainactor.worktree.model.removeLeaf
import io.mainactor.worktree.model.mapSessions
import io.mainactor.worktree.model.sessions
import io.mainactor.worktree.model.splitLeaf
import io.mainactor.worktree.model.withFraction
import io.mainactor.worktree.model.Resolution
import io.mainactor.worktree.model.Worktree
import io.mainactor.worktree.platform.CommandResult
import io.mainactor.worktree.platform.DirectoryChooser
import io.mainactor.worktree.platform.FileSystemAccess
import io.mainactor.worktree.platform.ShellRunner
import io.mainactor.worktree.platform.SyntaxHighlighter
import io.mainactor.worktree.platform.SystemIntegration
import io.mainactor.worktree.usage.UsageReader
import io.mainactor.worktree.usage.WorktreeUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What the right-hand pane is comparing. */
enum class DiffMode {
    /** Uncommitted changes in the selected worktree. */
    WORKING_TREE,

    /** Everything the worktree's branch carries on top of a chosen base ref. */
    AGAINST_BASE,
}

enum class RightTab { CHANGES, CONFLICTS, LOG, SEARCH, TASKS, CONSOLE }

/** The two things the window can be: a repository, or a wall of agents working in it. */
enum class AppMode { PROJECT, AGENTS }

/** A transient message shown in the status bar / notification strip. */
data class Notice(val text: String, val isError: Boolean)

/** A project command that exited non-zero, with everything it said on its way out. */
data class CommandFailure(
    val name: String,
    val worktree: String,
    val commandLine: String,
    val exitCode: Int,
    val output: String,
)

/**
 * One embedded terminal tab.
 *
 * The name is kept in pieces rather than as one string, because the wall draws them differently
 * and a header that is one label cannot say which of several repositories a pane is in — which was
 * exactly the complaint: "main · 2" names a branch that half the repositories on the wall also
 * have, and the ordinal says nothing at all.
 */
data class TerminalSession(
    val id: String,
    /** The worktree's own label — its branch, normally; see [Worktree.label]. */
    val label: String,
    val workDir: String,
    /**
     * The repository's name, when it is worth saying.
     *
     * Null for a tool-window tab: those all belong to the project the window has open, so naming
     * it on every tab is noise. The agent wall sets it, because there it is the missing half.
     */
    val projectName: String? = null,
    /** Which agent, and which of several panes on the same worktree — "Claude 2", or just "2". */
    val agentLabel: String? = null,
    /** The repository this pane's worktree belongs to; agents may span several. */
    val projectPath: String? = null,
    /**
     * The command line the pane starts with, already resolved to resume or begin.
     *
     * Null is a plain login shell, which is what every pane was before agents could be chosen.
     */
    val command: String? = null,
    /** Which agent this pane was started as, for the header and for the picker's default. */
    val agentId: String? = null,
) {
    /** One line, for anything with room for only one — a tab, a widget's name, a test. */
    val title: String get() = listOfNotNull(projectName, label, agentLabel).joinToString(" · ")
}

/** A pending "start an agent" request, waiting for the user to say where. */
data class AgentRequest(
    /** Which way to split the focused pane, or null to let the pane's shape decide. */
    val axis: SplitAxis?,
)

/**
 * Everything the UI reads and every action it can trigger.
 *
 * Git commands are serialised through [gitLock]: several of them write the index or `.git/`
 * state, and running e.g. a refresh concurrently with a stage would race on `index.lock`.
 * Read-only refreshes of *different* worktrees still fan out in parallel inside that lock.
 */
class AppState(
    val git: Git,
    private val fs: FileSystemAccess,
    private val store: ProjectStore,
    private val chooser: DirectoryChooser,
    /** Runs a project's own commands, with no terminal attached. */
    private val shell: ShellRunner,
    /** Colours the diff. Injected like every other platform thing; absent in a render test. */
    val highlighter: SyntaxHighlighter = SyntaxHighlighter.None,
    private val taskStore: TaskStore = TaskStore(fs),
    private val statusStore: StatusStore = StatusStore(fs),
    /**
     * Hands a prompt to a running pane.
     *
     * A function rather than a dependency on the terminal: `:shared` does not know what a terminal
     * is, and this is the one thing a task needs from one.
     */
    private val onSendPrompt: (sessionId: String, text: String) -> Unit = { _, _ -> },
    val system: SystemIntegration,
    private val scope: CoroutineScope,
    /** Lets the platform layer tear down the shell process behind a terminal tab we drop. */
    private val onTerminalDisposed: (sessionId: String) -> Unit = {},
) {
    private val gitLock = Mutex()
    private val activity = WorktreeActivity(fs)

    // ---------------------------------------------------------------- projects

    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var project by mutableStateOf<Project?>(null)
        private set

    // ---------------------------------------------------------------- worktrees

    var worktrees by mutableStateOf<List<Worktree>>(emptyList())
        private set
    var selectedWorktree by mutableStateOf<Worktree?>(null)
        private set

    /** Per-worktree summary, used for the dirty/ahead-behind badges in the worktree list. */
    var worktreeStatuses by mutableStateOf<Map<String, RepoStatus>>(emptyMap())
        private set

    // ------------------------------------------------------- selected worktree

    var status by mutableStateOf(RepoStatus.EMPTY)
        private set
    var branches by mutableStateOf<List<Branch>>(emptyList())
        private set
    var commits by mutableStateOf<List<CommitInfo>>(emptyList())
        private set

    var selectedFile by mutableStateOf<ChangedFile?>(null)
        private set
    var diff by mutableStateOf<FileDiff?>(null)
        private set
    var diffLoading by mutableStateOf(false)
        private set

    var diffMode by mutableStateOf(DiffMode.WORKING_TREE)
        private set

    /**
     * Whether a diff shows the whole file rather than the changed lines and a little around them.
     *
     * Three lines of context answers "what changed"; it does not answer "what does this function
     * look like now", which is the question you have as soon as the change is more than a typo.
     * git can answer both — the context is a number on the command line — so this is a request for
     * a wider one rather than a different view, and everything downstream is unchanged.
     *
     * Kept on the state rather than in a pane so the choice follows you: turned on to read one
     * file, it is still on for the next one and for a commit in the Log tab.
     */
    var wholeFileDiff by mutableStateOf(false)
        private set

    var baseRef by mutableStateOf<String?>(null)
        private set

    /** In [DiffMode.AGAINST_BASE] the file list comes from a range diff rather than from status. */
    var rangeDiffs by mutableStateOf<List<FileDiff>>(emptyList())
        private set

    // ---------------------------------------------------------------------- log

    /** The commit the Log tab is showing the contents of. */
    var selectedCommit by mutableStateOf<CommitInfo?>(null)
        private set

    /** Every file [selectedCommit] touched, each already carrying its patch. */
    var commitFiles by mutableStateOf<List<FileDiff>>(emptyList())
        private set

    /** The file selected within [selectedCommit]. */
    var commitFile by mutableStateOf<FileDiff?>(null)
        private set

    var commitDiffLoading by mutableStateOf(false)
        private set

    // -------------------------------------------------------------- file search

    var searchQuery by mutableStateOf("")
        private set

    /** Files matching [searchQuery], capped at [MAX_SEARCH_RESULTS]. */
    var searchResults by mutableStateOf<List<String>>(emptyList())
        private set

    /** True while the tracked-file index is being read for the first time. */
    var searchIndexing by mutableStateOf(false)
        private set

    /** How many files the worktree tracks, for the field's placeholder. */
    var searchIndexSize by mutableStateOf(0)
        private set

    /** The file whose history is on screen. */
    var searchFile by mutableStateOf<String?>(null)
        private set

    /** Commits that touched [searchFile], newest first. */
    var fileCommits by mutableStateOf<List<CommitInfo>>(emptyList())
        private set
    var fileCommit by mutableStateOf<CommitInfo?>(null)
        private set
    var fileDiff by mutableStateOf<FileDiff?>(null)
        private set
    var fileHistoryLoading by mutableStateOf(false)
        private set
    var fileDiffLoading by mutableStateOf(false)
        private set

    /**
     * Every file git tracks, read once per worktree and filtered in memory.
     *
     * Not snapshot state: it is large, it is never drawn, and only [searchResults] — what the pane
     * does draw — needs to make the UI recompose.
     */
    private var searchIndex: List<String> = emptyList()

    /** The worktree [searchIndex] was read for, so a stale index is never searched. */
    private var searchIndexFor: String? = null

    /** The in-flight index read, exposed the same way [badgeRefresh] is so tests can join it. */
    var searchIndexJob: Job? = null
        private set

    // ---------------------------------------------------------------- conflicts

    var conflictFile by mutableStateOf<ConflictedFile?>(null)
        private set
    var conflictLoading by mutableStateOf(false)
        private set

    // ---------------------------------------------------------------- chrome

    var rightTab by mutableStateOf(RightTab.CHANGES)
    var busy by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<Notice?>(null)
    val gitLog = mutableStateListOf<GitLogEntry>()

    var terminalVisible by mutableStateOf(false)
        private set
    var terminals by mutableStateOf<List<TerminalSession>>(emptyList())
        private set
    var activeTerminal by mutableStateOf<String?>(null)
        private set

    private var terminalSeq = 0
    private var logSeq = 0L

    /** Measured pane sizes, kept out of snapshot state: read when acting, never during layout. */
    private val paneSizes = mutableMapOf<String, Pair<Int, Int>>()

    // ---------------------------------------------------------------- agents

    var mode by mutableStateOf(AppMode.PROJECT)
        private set

    /** The wall's split tree; null when nothing is running. */
    var agentLayout by mutableStateOf<PaneNode?>(null)
        private set

    /** Every agent terminal on the wall, left to right and top to bottom. */
    val agents: List<TerminalSession> get() = agentLayout?.sessions().orEmpty()

    var focusedAgent by mutableStateOf<String?>(null)
        private set

    /**
     * What Claude Code has spent in each worktree holding an agent pane, keyed by worktree path.
     *
     * Read from the transcripts Claude Code writes for itself, so it covers every session in the
     * worktree — including ones started from an ordinary terminal rather than from a pane here.
     */
    var agentUsage by mutableStateOf<Map<String, WorktreeUsage>>(emptyMap())
        private set

    /** The in-flight read, exposed the same way [badgeRefresh] is so tests can join it. */
    var usageRefresh: Job? = null
        private set

    private val usageReader = UsageReader(fs)
    private val agentStore = AgentSettingsStore(fs)

    /**
     * Which agents the open project offers.
     *
     * A project nobody has configured offers every built-in whose executable could be found, so the
     * setting exists to take something away or to add a command of your own — not to switch the
     * feature on.
     */
    var projectAgents by mutableStateOf(ProjectAgents())
        private set

    /** The agents to offer when starting a pane, built-ins first. */
    val availableAgents: List<AgentSpec> get() = projectAgents.available()

    /** When set, that one pane fills the wall — the multiplexer's zoom. */
    var zoomedAgent by mutableStateOf<String?>(null)
        private set

    /** The worktree the last agent was started in, used to seed the picker. */
    var agentWorktree by mutableStateOf<Worktree?>(null)
        private set

    /** The in-flight background sweep of worktree badges; joinable, and cancelled when superseded. */
    var badgeRefresh: Job? = null
        private set

    // ---------------------------------------------------------------- lifecycle

    /** Returns the job that opens the restored project, so callers can await the first load. */
    fun start(): Job? {
        projects = store.load()
        // The list order is the user's; which project to reopen is remembered separately.
        val restore = store.lastOpened()?.takeIf { path -> projects.any { it.path == path } }
        return (restore ?: projects.firstOrNull()?.path)?.let { openProject(it) }
    }

    fun recordGitLog(entry: GitLogEntry) {
        gitLog += entry.copy(seq = ++logSeq)
        if (gitLog.size > MAX_LOG_ENTRIES) gitLog.removeRange(0, gitLog.size - MAX_LOG_ENTRIES)
    }

    // ---------------------------------------------------------------- projects

    fun chooseProject() = run("Opening repository") {
        val picked = chooser.chooseDirectory("Open Git repository", fs.homeDir()) ?: return@run
        openProjectAt(picked)
    }

    fun openProject(path: String) = run("Opening repository") { openProjectAt(path) }

    private suspend fun openProjectAt(path: String, select: String? = null) {
        val root = git.mainWorktree(path)
        if (root == null) {
            notice = Notice("${fs.nameOf(path)} is not a git repository", isError = true)
            return
        }
        // A sweep still running for the previous repository has nothing useful left to say.
        badgeRefresh?.cancel()
        // What the last sweep of *this* repository found, so the badges and the order are on
        // screen before the new sweep has run a single command. It is replaced wholesale when
        // that sweep lands; until then it is the truth as of the last time the project was open.
        worktreeStatuses = statusStore.load(root)
        projects = store.add(projects, root)
        store.setLastOpened(root)
        projectAgents = agentStore.forProjectOrDefault(root)
        project = projects.first { it.path == root }
        loadTasks(root)
        selectedWorktree = null
        baseRef = null
        diffMode = DiffMode.WORKING_TREE
        reloadProject(selectPath = select ?: root)
    }

    /** Opens the OS folder picker and hands the result back, for the "Browse…" buttons in dialogs. */
    fun browseForDirectory(title: String, startIn: String?, onPicked: (String) -> Unit) {
        scope.launch {
            chooser.chooseDirectory(title, startIn ?: fs.homeDir())?.let(onPicked)
        }
    }

    /** Sensible default parent directory for a new worktree: next to the repository itself. */
    fun defaultWorktreeParent(): String {
        val root = project?.path ?: return fs.homeDir()
        return fs.parentOf(root) ?: fs.homeDir()
    }

    fun forgetProject(path: String) {
        projects = store.remove(projects, path)
        statusStore.forget(path)
        if (project?.path == path) closeProject()
    }

    fun closeProject() {
        badgeRefresh?.cancel()
        project = null
        worktrees = emptyList()
        worktreeStatuses = emptyMap()
        selectedWorktree = null
        status = RepoStatus.EMPTY
        diff = null
        selectedFile = null
        terminals.forEach { onTerminalDisposed(it.id) }
        terminals = emptyList()
        activeTerminal = null
        agents.forEach { onTerminalDisposed(it.id) }
        agentLayout = null
        paneSizes.clear()
        focusedAgent = null
        zoomedAgent = null
        agentWorktree = null
    }

    /** Creates a brand-new repository in a directory the user picks. */
    fun initNewProject() = run("Creating repository") {
        val parent = chooser.chooseDirectory("Choose a folder for the new repository", fs.homeDir()) ?: return@run
        val result = git.init(parent)
        if (!result.ok) {
            fail(result)
            return@run
        }
        openProjectAt(parent)
        notice = Notice("Initialised empty repository in ${fs.nameOf(parent)}", isError = false)
    }

    fun cloneProject(url: String, parentDir: String, folderName: String) = run("Cloning $url") {
        val target = fs.resolve(parentDir, folderName)
        val result = git.clone(url, target)
        if (!result.ok) {
            fail(result)
            return@run
        }
        openProjectAt(target)
        notice = Notice("Cloned into $folderName", isError = false)
    }

    // ---------------------------------------------------------------- refresh

    fun refresh() = run("Refreshing") { reloadProject(selectPath = selectedWorktree?.path) }

    private suspend fun reloadProject(selectPath: String?) {
        val root = project?.path ?: return
        val raw = git.worktrees(root)
        val commitTimes = git.commitTimes(root, raw.mapNotNull { it.head }.filter(::isRealSha))
        // Ordered on what we already know. The badge sweep refines it a moment later, once it has
        // told us which files are dirty and therefore worth statting.
        val list = withActivity(raw, commitTimes, worktreeStatuses)
        worktrees = list

        val target = selectPath?.let { wanted -> list.firstOrNull { samePath(it.path, wanted) } }
            ?: list.firstOrNull()
        selectedWorktree = target

        // The worktree the user is looking at is loaded now; the badges for all the others catch
        // up in the background. With dozens of worktrees, waiting for the whole sweep would put a
        // third of a second between every click and the screen updating.
        target?.let { loadWorktree(it) }
        refreshBadges(root, list, commitTimes)
    }

    /**
     * Refreshes the dirty/ahead-behind badges of every worktree.
     *
     * Runs outside [gitLock] and outside the caller's job: `git status` is read-only and
     * `GIT_OPTIONAL_LOCKS=0` keeps it from touching `index.lock`, so it cannot collide with a
     * command that writes the index. Only the newest sweep is kept — during a burst of actions the
     * earlier ones have nothing left to say.
     *
     * **Each badge is published the moment it arrives**, rather than the whole map when the last
     * one lands. The sweep is bounded by the disk rather than by the processor — measured on a
     * repository of 62 worktrees, one `git status` is ~110 ms and the whole sweep is 5.3 s however
     * many run at once — so waiting for it means a list that shows nothing for five seconds after
     * a project is opened, which is what it did. Publishing as they come fills the list from the
     * top in the order it is drawn, and a sweep cancelled half way through keeps what it learnt.
     */
    private fun refreshBadges(root: String, list: List<Worktree>, commitTimes: Map<String, Long>) {
        badgeRefresh?.cancel()
        badgeRefresh = scope.launch {
            val statuses = sweepStatuses(list) { path, status ->
                if (project?.path == root) worktreeStatuses = worktreeStatuses + (path to status)
            }
            if (project?.path != root) return@launch
            // The whole map at the end, so a worktree that has since gone loses its badge with it.
            worktreeStatuses = statuses
            // Only a *finished* sweep is written: a cancelled one is a partial picture of the
            // repository, and remembering it would put half of the last state beside half of the
            // one before it the next time the project opens.
            statusStore.save(root, statuses)

            // Uncommitted edits can only be dated once we know which files they are in.
            val refined = withActivity(list, commitTimes, statuses)
            if (refined != worktrees) {
                worktrees = refined
                selectedWorktree = selectedWorktree?.let { current ->
                    refined.firstOrNull { it.path == current.path } ?: current
                }
            }
        }
    }

    private suspend fun loadWorktree(worktree: Worktree) {
        if (worktree.isBare) {
            status = RepoStatus.EMPTY
            branches = git.branches(worktree.path)
            commits = emptyList()
            return
        }
        // Independent read-only queries: issued together, because a serial chain of seven child
        // processes is most of what the user feels after every click.
        coroutineScope {
            val statusAsync = async { git.status(worktree.path, detailed = true) }
            val operationAsync = async { git.currentOperation(worktree.path) }
            val branchesAsync = async { git.branches(worktree.path) }
            val commitsAsync = async { git.log(worktree.path, limit = 150) }

            status = statusAsync.await().copy(operation = operationAsync.await())
            branches = branchesAsync.await()
            commits = commitsAsync.await()
        }

        // A refresh must not drop what the Log tab is reading, but a commit that was amended or
        // rebased away is no longer there to show.
        if (commits.none { it.hash == selectedCommit?.hash }) clearCommitSelection()

        // A commit, a checkout or a merge changes which files exist. Dropping the marker rather
        // than the index keeps the results on screen and costs one `ls-files` on the next search.
        searchIndexFor = null

        if (baseRef == null) baseRef = defaultBaseRef()

        if (status.operation != RepoOperation.NONE && status.conflicts.isNotEmpty()) {
            rightTab = RightTab.CONFLICTS
        } else if (rightTab == RightTab.CONFLICTS && status.conflicts.isEmpty()) {
            rightTab = RightTab.CHANGES
        }

        if (diffMode == DiffMode.AGAINST_BASE) loadRangeDiff()

        // Keep the previously selected file selected across refreshes when it still has changes.
        val keep = selectedFile?.path?.let { path -> status.files.firstOrNull { it.path == path } }
        selectFileInternal(keep ?: status.files.firstOrNull())
    }

    /**
     * Dates every worktree and orders the list by that, most recent first.
     *
     * The main working tree always comes first, whatever its activity. Everything after it is
     * ordered by the newer of the HEAD commit and any file git currently reports as changed — so a
     * worktree you edited five minutes ago outranks one you committed to yesterday. See
     * [WorktreeActivity] for why the filesystem is consulted this narrowly.
     *
     * Worktrees with nothing to date — bare ones, or a branch with no commits yet — sort last.
     */
    private fun withActivity(
        list: List<Worktree>,
        commitTimes: Map<String, Long>,
        statuses: Map<String, RepoStatus>,
    ): List<Worktree> {
        val now = fs.now()
        return list
            .map { worktree ->
                if (worktree.isBare) return@map worktree
                val commitAt = worktree.head?.let { commitTimes[it] } ?: 0L
                val editedAt = activity.newestChangedFileAt(
                    worktreePath = worktree.path,
                    files = statuses[worktree.path]?.files.orEmpty(),
                )
                val newest = maxOf(commitAt, editedAt)
                worktree.copy(
                    lastCommitAt = commitAt.takeIf { it > 0L },
                    lastActivityAt = newest.takeIf { it > 0L },
                    lastActivityLabel = relativeTime(newest, now).takeIf { it.isNotEmpty() },
                    activityReason = activityBreakdown(commitAt, editedAt, now),
                )
            }
            .sortedWith(
                // The main working tree is the repository itself, not one of the disposable
                // checkouts around it, so it is pinned rather than shuffled by activity.
                compareByDescending<Worktree> { it.isMain }
                    .thenByDescending { it.lastActivityAt ?: Long.MIN_VALUE }
                    .thenBy { it.label.lowercase() },
            )
    }

    private fun activityBreakdown(commitAt: Long, editedAt: Long, now: Long): String =
        buildList {
            if (editedAt > 0L) add("uncommitted edit  ${relativeTime(editedAt, now)}")
            if (commitAt > 0L) add("last commit       ${relativeTime(commitAt, now)}")
        }.joinToString("\n")

    /** git writes an all-zero HEAD for a branch that has no commits yet. */
    private fun isRealSha(sha: String) = sha.isNotBlank() && sha.any { it != '0' }

    /**
     * Summary status of every non-bare worktree, with a bounded number of gits in flight.
     *
     * [onStatus] is called with each one as it lands, on the caller's dispatcher, so the list can
     * fill in while the rest of the sweep is still running. The permits are handed out roughly in
     * the order asked, and [list] is in the order the pane draws, so the badges the user is
     * looking at are the ones that arrive first.
     */
    private suspend fun sweepStatuses(
        list: List<Worktree>,
        /** Nothing by default: a caller that hands the whole list over at once has no use for it. */
        onStatus: (String, RepoStatus) -> Unit = { _, _ -> },
    ): Map<String, RepoStatus> {
        val gate = Semaphore(MAX_PARALLEL_STATUS)
        return coroutineScope {
            list.filterNot { it.isBare }
                .map { wt ->
                    async {
                        val status = gate.withPermit { git.status(wt.path, detailed = false) }
                        onStatus(wt.path, status)
                        wt.path to status
                    }
                }
                .awaitAll()
                .toMap()
        }
    }

    /** git reports canonical paths; what the UI hands back may be the pre-symlink spelling. */
    private fun samePath(a: String, b: String): Boolean =
        a == b || fs.canonicalPath(a) == fs.canonicalPath(b)

    private fun defaultBaseRef(): String? {
        val mainBranch = worktrees.firstOrNull { it.isMain }?.branch
        val candidates = listOfNotNull(mainBranch, "main", "master", "develop")
        val names = branches.filterNot { it.isRemote }.map { it.name }.toSet()
        return candidates.firstOrNull { it in names } ?: mainBranch
    }

    // ---------------------------------------------------------------- selection

    fun selectWorktree(worktree: Worktree) = run(null) {
        selectedWorktree = worktree
        selectedFile = null
        diff = null
        conflictFile = null
        clearCommitSelection()
        clearSearch()
        loadWorktree(worktree)
    }

    fun selectFile(file: ChangedFile?) = run(null) { selectFileInternal(file) }

    private suspend fun selectFileInternal(file: ChangedFile?) {
        selectedFile = file
        conflictFile = null
        if (file == null) {
            diff = null
            return
        }
        val dir = selectedWorktree?.path ?: return
        if (file.conflicted) {
            rightTab = RightTab.CONFLICTS
            loadConflict(file.path)
            return
        }
        loadFileDiff(dir, file)
    }

    private suspend fun loadFileDiff(dir: String, file: ChangedFile) {
        diffLoading = true
        diff = try {
            when {
                file.untracked -> git.diffUntracked(dir, file.path).firstOrNull()
                file.staged && !file.unstaged ->
                    git.diff(dir, staged = true, path = file.path, contextLines = diffContext).firstOrNull()
                else ->
                    git.diff(dir, staged = false, path = file.path, contextLines = diffContext).firstOrNull()
                        ?: git.diff(dir, staged = true, path = file.path, contextLines = diffContext).firstOrNull()
            }
        } finally {
            diffLoading = false
        }
    }

    /**
     * Switches between the changed lines and the whole file, and re-reads what is on screen.
     *
     * Every diff has to be asked for again, because the context is decided by git rather than by
     * us — there is no wider version of a patch already in hand. Only what is actually loaded is
     * re-read, so turning this on while looking at a working-tree file does not go and fetch a
     * commit nobody is looking at.
     */
    fun showWholeFile(on: Boolean) = run(null) {
        if (on == wholeFileDiff) return@run
        wholeFileDiff = on
        val dir = selectedWorktree?.path ?: return@run

        selectedFile?.let { loadFileDiff(dir, it) }
        if (diffMode == DiffMode.AGAINST_BASE) loadRangeDiff()
        selectedCommit?.let { commit ->
            val showing = commitFile?.path
            commitFiles = git.commitDiff(dir, commit.hash, contextLines = diffContext)
            commitFile = commitFiles.firstOrNull { it.path == showing } ?: commitFiles.firstOrNull()
        }
        val commit = fileCommit
        val path = searchFile
        if (commit != null && path != null) loadFileDiff(dir, commit, path)
    }

    /**
     * How much of the file to ask git for.
     *
     * A number rather than a flag because that is what git takes, and one large enough that no file
     * anybody reads in a pane has more lines than it — `git diff -U` has no "everything" and this
     * is what every tool that offers the option passes.
     */
    private val diffContext: Int get() = if (wholeFileDiff) WHOLE_FILE_CONTEXT else 3

    /**
     * A path from a diff, made absolute.
     *
     * git reports paths relative to the worktree, and everything that leaves the window — revealing
     * a file, putting one on the clipboard — wants the whole thing.
     */
    fun pathOf(relative: String): String =
        selectedWorktree?.path?.let { fs.resolve(it, relative) } ?: relative

    fun selectRangeFile(fileDiff: FileDiff) {
        selectedFile = null
        conflictFile = null
        diff = fileDiff
    }

    /**
     * Loads what a commit changed.
     *
     * Nothing does this on its own. It is a `git show` per commit, and the Log tab is opened to
     * scan subjects far more often than to read a patch — the same reason no shell starts by
     * itself. The first file is selected once the patch is in hand, because by then the cost is
     * already paid and an empty diff pane beside a full file list is just another click.
     */
    fun selectCommit(commit: CommitInfo) = run(null) {
        val dir = selectedWorktree?.path ?: return@run
        selectedCommit = commit
        commitFile = null
        commitDiffLoading = true
        commitFiles = try {
            git.commitDiff(dir, commit.hash, contextLines = diffContext)
        } finally {
            commitDiffLoading = false
        }
        commitFile = commitFiles.firstOrNull()
    }

    fun selectCommitFile(file: FileDiff) {
        commitFile = file
    }

    /**
     * Re-reads the usage transcripts for every worktree on the agent wall.
     *
     * Outside [gitLock] on purpose — no git is involved, and a click must never wait behind a file
     * read — and off the main thread, because the first read of a session is the whole file and
     * the largest on this machine is 59 MB. Overlapping calls collapse into the one already
     * running: the reader holds a position per file and is not re-entrant.
     */
    fun refreshAgentUsage(): Job {
        usageRefresh?.takeIf { it.isActive }?.let { return it }
        val dirs = agents.map { it.workDir }.distinct()
        val job = scope.launch {
            agentUsage = if (dirs.isEmpty()) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) { dirs.associateWith { usageReader.read(it) } }
            }
        }
        usageRefresh = job
        return job
    }

    private fun clearCommitSelection() {
        selectedCommit = null
        commitFiles = emptyList()
        commitFile = null
    }

    // -------------------------------------------------------------- file search

    /**
     * Filters the worktree's tracked files by [query].
     *
     * The filtering is synchronous against an in-memory index, so typing never waits on a child
     * process; only the first search in a worktree launches one, to read that index.
     */
    fun search(query: String) {
        searchQuery = query
        val dir = selectedWorktree?.path
        if (dir != null && searchIndexFor != dir) {
            // The results already on screen stay until the index lands, rather than blinking empty.
            loadSearchIndex(dir)
            return
        }
        searchResults = matching(query)
    }

    private fun loadSearchIndex(dir: String) {
        if (searchIndexJob?.isActive == true) return
        searchIndexJob = run(null) {
            searchIndexing = true
            try {
                searchIndex = git.listFiles(dir)
                searchIndexFor = dir
            } finally {
                searchIndexing = false
            }
            searchIndexSize = searchIndex.size
            // The query may have grown while git was reading; match against the current one.
            searchResults = matching(searchQuery)
        }
    }

    /**
     * Ranks a file search.
     *
     * A query is nearly always aimed at the file's *name*, not the folders above it, so anything
     * matching the name sorts first; within each group the shortest path wins, which puts
     * `Main.kt` above `test/fixtures/deeply/nested/Main.kt`.
     */
    private fun matching(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return searchIndex
            .filter { it.contains(q, ignoreCase = true) }
            .sortedWith(
                compareBy(
                    { !it.substringAfterLast('/').contains(q, ignoreCase = true) },
                    { it.length },
                    { it },
                ),
            )
            .take(MAX_SEARCH_RESULTS)
    }

    /** Loads the commits that touched [path], and opens the newest of them. */
    fun selectSearchFile(path: String) = run(null) {
        val dir = selectedWorktree?.path ?: return@run
        searchFile = path
        fileCommit = null
        fileDiff = null
        fileHistoryLoading = true
        fileCommits = try {
            git.fileHistory(dir, path)
        } finally {
            fileHistoryLoading = false
        }
        fileCommits.firstOrNull()?.let { loadFileDiff(dir, it, path) }
    }

    fun selectFileCommit(commit: CommitInfo) = run(null) {
        val dir = selectedWorktree?.path ?: return@run
        val path = searchFile ?: return@run
        loadFileDiff(dir, commit, path)
    }

    private suspend fun loadFileDiff(dir: String, commit: CommitInfo, path: String) {
        fileCommit = commit
        fileDiffLoading = true
        fileDiff = try {
            git.commitFileDiff(dir, commit.hash, path, contextLines = diffContext)
        } finally {
            fileDiffLoading = false
        }
    }

    private fun clearSearch() {
        searchQuery = ""
        searchResults = emptyList()
        searchIndex = emptyList()
        searchIndexFor = null
        searchIndexSize = 0
        searchFile = null
        fileCommits = emptyList()
        fileCommit = null
        fileDiff = null
    }

    fun setDiffMode(mode: DiffMode) = run(null) {
        diffMode = mode
        diff = null
        if (mode == DiffMode.AGAINST_BASE) loadRangeDiff() else selectFileInternal(status.files.firstOrNull())
    }

    fun setBaseRef(ref: String) = run(null) {
        baseRef = ref
        if (diffMode == DiffMode.AGAINST_BASE) loadRangeDiff()
    }

    private suspend fun loadRangeDiff() {
        val dir = selectedWorktree?.path ?: return
        val base = baseRef ?: return
        diffLoading = true
        rangeDiffs = try {
            git.diffRange(dir, base, "HEAD", contextLines = diffContext)
        } finally {
            diffLoading = false
        }
        diff = rangeDiffs.firstOrNull()
    }

    // ---------------------------------------------------------------- worktree ops

    fun createWorktree(
        path: String,
        newBranch: String?,
        existingBranch: String?,
        baseRef: String?,
        force: Boolean,
        track: Boolean = false,
    ) = run("Creating worktree") {
        val root = project?.path ?: return@run
        val result = git.addWorktree(
            dir = root,
            path = path,
            newBranch = newBranch,
            existingBranch = existingBranch,
            baseRef = baseRef,
            force = force,
            track = track,
        )
        if (!result.ok) {
            fail(result)
            return@run
        }
        notice = Notice("Worktree created at ${fs.nameOf(path)}", isError = false)
        reloadProject(selectPath = path)
    }

    fun removeWorktree(worktree: Worktree, force: Boolean) = run("Removing worktree") {
        val root = project?.path ?: return@run
        val result = git.removeWorktree(root, worktree.path, force)
        if (!result.ok) {
            fail(result)
            return@run
        }
        terminals.filter { it.workDir == worktree.path }.forEach { onTerminalDisposed(it.id) }
        terminals = terminals.filterNot { it.workDir == worktree.path }
        if (activeTerminal !in terminals.map { it.id }) activeTerminal = terminals.lastOrNull()?.id
        agents.filter { it.workDir == worktree.path }.forEach { doomed ->
            onTerminalDisposed(doomed.id)
            paneSizes.remove(doomed.id)
            agentLayout = agentLayout?.removeLeaf(doomed.id)
        }
        if (focusedAgent !in agents.map { it.id }) focusedAgent = agents.lastOrNull()?.id
        if (zoomedAgent !in agents.map { it.id }) zoomedAgent = null
        if (agentWorktree?.path == worktree.path) agentWorktree = null
        notice = Notice("Removed ${worktree.name}", isError = false)
        reloadProject(selectPath = null)
    }

    /**
     * Renames a worktree: its folder, its branch, or both.
     *
     * The branch goes first. It is renamed from the repository rather than from the worktree, so it
     * does not care where the checkout currently sits — and if it fails, nothing has moved yet.
     *
     * Uncommitted work is safe: `git worktree move` relocates a dirty tree happily, and a moved
     * directory keeps its inode, so a shell already running inside it follows along. What does not
     * follow is the path each agent pane *recorded*, which is how its usage is found and how "show
     * in project" gets back — so those are rewritten here.
     */
    fun renameWorktree(worktree: Worktree, folderName: String?, branchName: String?) =
        run("Renaming worktree") {
            val root = project?.path ?: return@run
            val newBranch = branchName?.trim()?.takeIf { it.isNotEmpty() && it != worktree.branch }
            val newFolder = folderName?.trim()?.takeIf { it.isNotEmpty() && it != worktree.name }
            if (newBranch == null && newFolder == null) return@run

            val currentBranch = worktree.branch
            if (newBranch != null && currentBranch != null) {
                val renamed = git.renameBranch(root, currentBranch, newBranch)
                if (!renamed.ok) {
                    fail(renamed)
                    return@run
                }
            }

            var path = worktree.path
            if (newFolder != null) {
                val parent = fs.parentOf(worktree.path) ?: return@run
                val target = fs.resolve(parent, newFolder)
                // `git worktree move` onto a path that already exists moves the worktree *inside*
                // it, the way `mv` does, and reports success. Checking first is the difference
                // between a rename and a silently nested checkout.
                if (fs.exists(target)) {
                    notice = Notice("${'$'}newFolder already exists", isError = true)
                    return@run
                }
                val moved = git.moveWorktree(root, worktree.path, target)
                if (!moved.ok) {
                    fail(moved)
                    return@run
                }
                retarget(worktree.path, target)
                path = target
            }

            notice = Notice("Renamed ${'$'}{worktree.name}", isError = false)
            reloadProject(selectPath = path)
        }

    /** Points everything that remembered a worktree by path at where it now is. */
    private fun retarget(from: String, to: String) {
        fun moved(dir: String) = when {
            dir == from -> to
            dir.startsWith("${'$'}from/") -> to + dir.removePrefix(from)
            else -> dir
        }
        agentLayout = agentLayout?.mapSessions { session ->
            session.copy(workDir = moved(session.workDir))
        }
        terminals = terminals.map { it.copy(workDir = moved(it.workDir)) }
        if (agentWorktree?.path == from) agentWorktree = agentWorktree?.copy(path = to)
    }

    fun pruneWorktrees() = run("Pruning worktrees") {
        val root = project?.path ?: return@run
        val result = git.pruneWorktrees(root)
        if (!result.ok) fail(result) else notice = Notice("Pruned stale worktrees", isError = false)
        reloadProject(selectPath = selectedWorktree?.path)
    }

    fun toggleLock(worktree: Worktree) = run(if (worktree.isLocked) "Unlocking" else "Locking") {
        val root = project?.path ?: return@run
        val result = if (worktree.isLocked) {
            git.unlockWorktree(root, worktree.path)
        } else {
            git.lockWorktree(root, worktree.path, reason = null)
        }
        if (!result.ok) fail(result)
        reloadProject(selectPath = worktree.path)
    }

    /**
     * Points [worktree] at another branch.
     *
     * Picking a remote branch means "work on this branch here", so it creates the local tracking
     * branch rather than detaching HEAD at the remote ref — unless a local branch of that name
     * already exists, in which case that is what the user meant.
     */
    fun switchBranch(worktree: Worktree, branch: Branch) =
        mutateIn(worktree.path, "Switching to ${branch.shortName}") { dir ->
            when {
                !branch.isRemote -> git.checkout(dir, branch.name)
                branches.any { !it.isRemote && it.name == branch.shortName } ->
                    git.checkout(dir, branch.shortName)
                else -> git.checkoutTracking(dir, branch.shortName, branch.name)
            }
        }

    fun switchToNewBranch(worktree: Worktree, name: String, startPoint: String?) =
        mutateIn(worktree.path, "Creating $name") { dir ->
            git.checkoutNewBranch(dir, name, startPoint)
        }

    // ---------------------------------------------------------------- index ops

    fun stage(files: List<ChangedFile>) = mutate("Staging") { dir ->
        git.stage(dir, files.map { it.path })
    }

    fun unstage(files: List<ChangedFile>) = mutate("Unstaging") { dir ->
        git.unstage(dir, files.map { it.path })
    }

    fun stageAll() = mutate("Staging all") { dir -> git.stageAll(dir) }

    fun discard(files: List<ChangedFile>) = mutate("Discarding") { dir ->
        val tracked = files.filterNot { it.untracked }.map { it.path }
        val untracked = files.filter { it.untracked }.map { it.path }
        var result = CommandResult(0, "", "")
        if (tracked.isNotEmpty()) result = git.discard(dir, tracked)
        if (result.ok && untracked.isNotEmpty()) result = git.deleteUntracked(dir, untracked)
        result
    }

    /**
     * Commits, and — when [push] is set — sends it on in the same action.
     *
     * The push is skipped when the commit failed: there is nothing new to publish then, and
     * pushing regardless would send whatever the branch already held under the impression that it
     * is the commit just written. One action rather than two also means one lock and one refresh,
     * and a status bar that names the step it is on.
     */
    fun commit(message: String, amend: Boolean, stageAll: Boolean, push: Boolean = false) =
        mutate("Committing") { dir ->
            val committed = git.commit(dir, message, amend = amend, stageAll = stageAll)
            if (!push || !committed.ok) return@mutate committed
            busy = "Pushing"
            pushFrom(dir)
        }

    // ---------------------------------------------------------------- remote ops

    fun fetch() = mutate("Fetching") { dir -> git.fetch(dir) }

    fun pull(rebase: Boolean) = mutate("Pulling") { dir -> git.pull(dir, rebase) }

    fun push(force: Boolean = false) = mutate("Pushing") { dir -> pushFrom(dir, force) }

    private suspend fun pushFrom(dir: String, force: Boolean = false): CommandResult {
        val branch = status.branch
        val setUpstream = !status.hasUpstream && branch != null
        return git.push(dir, setUpstream = setUpstream, force = force, branch = branch)
    }

    /**
     * The exact command [push] will run.
     *
     * A branch with no upstream needs one, and the toolbar button and the commit dialog both
     * promise to show what they are about to run — from here, so the two cannot disagree with each
     * other or with [pushFrom].
     */
    val pushCommand: String
        get() = if (status.hasUpstream) "git push" else "git push -u origin ${status.branch.orEmpty()}"

    // ---------------------------------------------------------------- integrate

    fun merge(ref: String, noFastForward: Boolean) = mutate("Merging $ref") { dir ->
        git.merge(dir, ref, noFastForward = noFastForward)
    }

    fun rebase(onto: String) = mutate("Rebasing onto $onto") { dir -> git.rebase(dir, onto) }

    fun continueOperation() = mutate("Continuing ${status.operation.label}") { dir ->
        git.continueOperation(dir, status.operation)
    }

    fun abortOperation() = mutate("Aborting ${status.operation.label}") { dir ->
        git.abortOperation(dir, status.operation)
    }

    fun skipCommit() = mutate("Skipping commit") { dir -> git.skipOperation(dir, status.operation) }

    // ---------------------------------------------------------------- conflicts

    fun openConflict(path: String) = run(null) {
        rightTab = RightTab.CONFLICTS
        loadConflict(path)
    }

    private suspend fun loadConflict(path: String) {
        val dir = selectedWorktree?.path ?: return
        conflictLoading = true
        conflictFile = try {
            val full = fs.resolve(dir, path)
            if (fs.exists(full)) GitParsers.parseConflicts(path, fs.readText(full)) else null
        } catch (e: Exception) {
            notice = Notice("Cannot read $path: ${e.message}", isError = true)
            null
        } finally {
            conflictLoading = false
        }
    }

    fun resolveRegion(regionId: Int, resolution: Resolution) {
        val file = conflictFile ?: return
        conflictFile = file.copy(
            segments = file.segments.map { segment ->
                if (segment is ConflictSegment.Conflict && segment.id == regionId) {
                    segment.copy(resolution = resolution)
                } else {
                    segment
                }
            },
        )
    }

    fun resolveAll(resolution: Resolution) {
        val file = conflictFile ?: return
        conflictFile = file.copy(
            segments = file.segments.map { segment ->
                if (segment is ConflictSegment.Conflict) segment.copy(resolution = resolution) else segment
            },
        )
    }

    /** Writes the resolved text back to disk and stages it, which is what marks it resolved to git. */
    fun applyConflictResolution() = run("Saving resolution") {
        val dir = selectedWorktree?.path ?: return@run
        val file = conflictFile ?: return@run
        if (!file.isFullyResolved) {
            notice = Notice("${file.unresolvedCount} conflict(s) still unresolved", isError = true)
            return@run
        }
        try {
            fs.writeText(fs.resolve(dir, file.path), GitParsers.renderResolved(file))
        } catch (e: Exception) {
            notice = Notice("Cannot write ${file.path}: ${e.message}", isError = true)
            return@run
        }
        val result = git.markResolved(dir, listOf(file.path))
        if (!result.ok) {
            fail(result)
            return@run
        }
        notice = Notice("Resolved ${file.path}", isError = false)
        conflictFile = null
        reloadProject(selectPath = dir)
    }

    /** `git checkout --ours/--theirs` — takes one whole side of the file without editing regions. */
    fun takeWholeSide(path: String, ours: Boolean) = run("Resolving $path") {
        val dir = selectedWorktree?.path ?: return@run
        val result = if (ours) git.takeOurs(dir, path) else git.takeTheirs(dir, path)
        if (!result.ok) {
            fail(result)
            return@run
        }
        val staged = git.markResolved(dir, listOf(path))
        if (!staged.ok) fail(staged)
        conflictFile = null
        reloadProject(selectPath = dir)
    }

    // ---------------------------------------------------------------- terminal

    fun toggleTerminal() {
        terminalVisible = !terminalVisible
        if (terminalVisible && terminals.isEmpty()) openTerminal()
    }

    fun openTerminal(worktree: Worktree? = selectedWorktree) {
        val target = worktree ?: return
        terminalSeq++
        val session = TerminalSession(
            id = "term-$terminalSeq-${target.path.hashCode()}",
            label = target.label,
            workDir = target.path,
        )
        terminals = terminals + session
        activeTerminal = session.id
        terminalVisible = true
    }

    fun closeTerminal(id: String) {
        onTerminalDisposed(id)
        terminals = terminals.filterNot { it.id == id }
        if (activeTerminal == id) activeTerminal = terminals.lastOrNull()?.id
        if (terminals.isEmpty()) terminalVisible = false
    }

    fun selectTerminal(id: String) {
        activeTerminal = id
        terminalVisible = true
    }

    // ---------------------------------------------------------------- agents

    fun switchTo(next: AppMode) {
        mode = next
        // Nothing is started automatically. Opening a repository or the agent wall should not
        // spawn a shell the user did not ask for; both offer the action instead.
        if (next == AppMode.AGENTS && agentWorktree == null) agentWorktree = selectedWorktree
    }

    /**
     * Reads the worktrees of any remembered project for the new-agent picker, ordered exactly the
     * way the worktrees pane orders them: the main working tree pinned, then by last activity.
     *
     * The status sweep that dates uncommitted work runs outside [gitLock] — it is read-only, and
     * `GIT_OPTIONAL_LOCKS=0` keeps it off `index.lock` — so browsing another repository in the
     * picker never blocks what the window is doing.
     */
    fun worktreesOf(target: Project, onLoaded: (List<Worktree>) -> Unit): Job = scope.launch {
        val (raw, times) = gitLock.withLock {
            val list = git.worktrees(target.path)
            list to git.commitTimes(target.path, list.mapNotNull { it.head }.filter(::isRealSha))
        }
        val statuses = if (samePath(target.path, project?.path.orEmpty())) {
            worktreeStatuses
        } else {
            sweepStatuses(raw)
        }
        onLoaded(withActivity(raw, times, statuses))
    }

    fun chooseAgentWorktree(worktree: Worktree) {
        agentWorktree = worktree
    }

    /** Commands running right now with no terminal, as "<name> in <worktree>". */
    var backgroundRuns by mutableStateOf<List<String>>(emptyList())
        private set

    /** The last command that failed, until the user dismisses it. */
    var commandFailure by mutableStateOf<CommandFailure?>(null)

    /**
     * Runs one of the project's commands in a worktree with no terminal.
     *
     * Deliberately outside [gitLock]: it is not git, and a build that takes a minute must not hold
     * up a refresh. Several can be in flight at once, in different worktrees, which is the point.
     *
     * Silence is the success case — the user asked for the thing to be done, not watched. Every run
     * is recorded in the Console tab all the same, so a command that "worked" can still be read
     * afterwards, and a failure is put on screen with its output.
     */
    fun runCommand(worktree: Worktree, agent: AgentSpec): Job {
        val label = "${'$'}{agent.name} in ${'$'}{worktree.name}"
        return scope.launch {
            backgroundRuns = backgroundRuns + label
            val result = try {
                shell.run(worktree.path, agent.command)
            } finally {
                backgroundRuns = backgroundRuns - label
            }

            // recordGitLog renumbers, so every Console row has one counter behind it whether it
            // came from git or from here — the list is keyed by that number.
            recordGitLog(
                GitLogEntry(
                    seq = 0,
                    workDir = worktree.path,
                    command = agent.command,
                    exitCode = result.exitCode,
                    output = buildString {
                        append(result.stdout)
                        if (result.stderr.isNotBlank()) {
                            if (isNotEmpty() && !endsWith("\n")) append('\n')
                            append(result.stderr)
                        }
                    }.trimEnd(),
                ),
            )

            if (!result.ok) {
                commandFailure = CommandFailure(
                    name = agent.name,
                    worktree = worktree.name,
                    commandLine = agent.command,
                    exitCode = result.exitCode,
                    output = listOf(result.stdout, result.stderr)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                        .trimEnd()
                        .ifBlank { "The command produced no output." },
                )
            }
            // Something was probably changed on disk, so the badges should say so — but under the
            // git lock like every other refresh, not from here.
            refresh()
        }
    }

    /** Whether this agent's executable could be found; see [FileSystemAccess.findOnPath]. */
    fun isAgentInstalled(agent: AgentSpec): Boolean {
        val executable = BuiltInAgents.executableOf(agent) ?: return true
        return fs.findOnPath(executable) != null
    }

    /** Replaces the open project's agent settings and writes them out. */
    fun saveProjectAgents(agents: ProjectAgents) {
        projectAgents = agents
        val path = project?.path ?: return
        scope.launch { agentStore.setForProject(path, agents) }
    }

    /**
     * The command a pane should start with, resuming when there is something to resume.
     *
     * Both supported CLIs scope "most recent session" to the working directory, which is exactly
     * the worktree the pane runs in — so continuing is one flag and needs no session id. Whether
     * there *is* a session is asked first rather than discovered by running a command that fails:
     * Forest already reads both tools' session logs, so it can simply look.
     */
    private fun commandFor(agent: AgentSpec, worktreePath: String): String? {
        if (agent.isShell) return null
        val resume = agent.resumeCommand
        val tool = agent.tool
        if (resume == null || tool == null) return agent.command
        return if (usageReader.hasSessions(worktreePath, tool)) resume else agent.command
    }

    /**
     * Opens another terminal on the wall, splitting the focused pane.
     *
     * With no [axis] the split follows the focused pane's own shape — a wide pane divides into
     * columns, a tall one into rows — which is what keeps repeated "new agent" from degenerating
     * into a row of slivers.
     *
     * Several agents in one worktree is the normal case, not the exception: they share a checkout
     * on purpose, so panes are numbered per worktree rather than one-per-directory.
     */
    fun openAgent(
        worktree: Worktree? = agentWorktree ?: selectedWorktree,
        axis: SplitAxis? = null,
        projectPath: String? = project?.path,
        agent: AgentSpec? = null,
    ): Job? {
        val target = worktree ?: return null
        if (target.isBare) return null

        // Only a resumable agent needs anything read from disk. Everything else — a plain shell, a
        // command the user typed — opens straight away, which is also what keeps a pane appearing
        // the instant a hotkey is pressed.
        if (agent == null || agent.resumeCommand == null || agent.tool == null) {
            addPane(target, axis, projectPath, agent, agent?.command?.takeIf { it.isNotBlank() })
            return null
        }

        // Choosing between resume and a fresh start reads both tools' session logs, and on a
        // machine with a long history that is not work for the thread painting the wall.
        return scope.launch {
            val command = withContext(Dispatchers.IO) { commandFor(agent, target.path) }
            addPane(target, axis, projectPath, agent, command)
        }
    }

    private fun addPane(
        target: Worktree,
        axis: SplitAxis?,
        projectPath: String?,
        agent: AgentSpec?,
        command: String?,
    ) {
        terminalSeq++
        val ordinal = agents.count { it.workDir == target.path } + 1
        val session = TerminalSession(
            id = "agent-$terminalSeq-${target.path.hashCode()}",
            label = target.label,
            workDir = target.path,
            // Taken from the path rather than from `project`, because a pane may be running in a
            // repository this window does not have open — which is the whole reason the wall has
            // to say which one it is.
            projectName = projectPath?.let(fs::nameOf),
            agentLabel = if (agent == null || agent.isShell) "$ordinal" else "${agent.name} $ordinal",
            projectPath = projectPath,
            command = command,
            agentId = agent?.id,
        )

        val tree = agentLayout
        val focused = focusedAgent?.takeIf { tree?.contains(it) == true }
        agentLayout = when {
            tree == null -> PaneNode.Leaf(session)
            focused == null -> PaneNode.Split(
                id = "split-$terminalSeq",
                axis = axis ?: SplitAxis.ROW,
                first = tree,
                second = PaneNode.Leaf(session),
            )
            else -> tree.splitLeaf(
                targetId = focused,
                axis = axis ?: autoSplitAxis(focused),
                session = session,
                splitId = "split-$terminalSeq",
            )
        }
        focusedAgent = session.id
        zoomedAgent = null
        agentWorktree = target
    }

    private fun autoSplitAxis(paneId: String): SplitAxis {
        val size = paneSizes[paneId] ?: return SplitAxis.ROW
        return if (size.first >= size.second) SplitAxis.ROW else SplitAxis.COLUMN
    }

    /** Panes report their measured size so a new split can follow the shape of what it divides. */
    fun recordAgentPaneSize(id: String, width: Int, height: Int) {
        paneSizes[id] = width to height
    }

    fun resizeAgentSplit(splitId: String, fraction: Float) {
        agentLayout = agentLayout?.withFraction(splitId, fraction)
    }

    fun closeAgent(id: String) {
        onTerminalDisposed(id)
        paneSizes.remove(id)
        agentLayout = agentLayout?.removeLeaf(id)
        if (focusedAgent == id) focusedAgent = agents.lastOrNull()?.id
        if (zoomedAgent == id) zoomedAgent = null
    }

    /**
     * Asks the window to open the new-agent picker.
     *
     * Splitting always asks where the new pane should run. Agents are commonly spread across
     * several repositories, so "split right" is how a pane on a *different* worktree gets started
     * — inheriting the divided pane's worktree would make that impossible. The picker opens on the
     * focused pane's own worktree, so staying put is still just a confirmation.
     *
     * The dialog is App's own state, and the shortcut that triggers it arrives from a global key
     * hook outside the composition, so it travels as a request the window observes and clears.
     */
    // ---------------------------------------------------------------- tasks

    /**
     * The open project's list of work, unfinished first and newest within that.
     *
     * Newest first because a task is written when the thought arrives and reached for while it is
     * still warm; a list in the order they were created buries the one you just wrote. Finished
     * ones sink rather than disappear — a tracker that hides what was done cannot answer "did I do
     * that already", which is half of what one is for.
     */
    var tasks by mutableStateOf<List<Task>>(emptyList())
        private set

    /** What is left to do, which is what the agent wall offers and what the badge counts. */
    val openTasks: List<Task> get() = tasks.filter { it.isOpen }

    var selectedTask by mutableStateOf<String?>(null)
        private set

    /** A pane waiting to be given a prompt, which the window turns into a picker. */
    var taskRequest by mutableStateOf<String?>(null)
        private set

    fun selectTask(id: String?) {
        selectedTask = id
    }

    /**
     * Starts a new task and puts the caret in it.
     *
     * The id counts up rather than being derived from the list — `tasks.size` reuses the id of a
     * task deleted a moment ago, and two tasks with one id are edited as one.
     */
    private var taskSeq = 0

    fun addTask() {
        val task = Task(id = "task-${fs.now()}-${taskSeq++}", body = "", updatedAt = fs.now())
        tasks = order(listOf(task) + tasks)
        selectedTask = task.id
        persistTasks()
    }

    /**
     * Saves as you type.
     *
     * There is no save button and no dirty state, because a list with either is a list people stop
     * using. The file is a few kilobytes and rewriting it costs nothing worth counting.
     *
     * The list is deliberately *not* reordered here, even though the task just became the newest:
     * a row that climbs to the top between two keystrokes takes the caret with it. It sorts into
     * place the next time the project is opened.
     */
    fun updateTask(id: String, body: String) {
        tasks = tasks.map { if (it.id == id) it.copy(body = body, updatedAt = fs.now()) else it }
        persistTasks()
    }

    /**
     * Finishes a task, or takes it back.
     *
     * Nothing sets this on its own. Handing a task to an agent is not progress — the pane may
     * finish it, fail at it or be closed on it, and none of that is visible from here — so the one
     * thing the tracker records is the one thing only the person knows.
     */
    fun toggleTaskDone(id: String) {
        tasks = order(tasks.map { if (it.id == id) it.copy(done = !it.done, updatedAt = fs.now()) else it })
        persistTasks()
    }

    fun deleteTask(id: String) {
        tasks = tasks.filterNot { it.id == id }
        if (selectedTask == id) selectedTask = tasks.firstOrNull()?.id
        persistTasks()
    }

    /** Asks which task to send to [sessionId]; the window shows the picker. */
    fun requestTask(sessionId: String) {
        taskRequest = sessionId
    }

    fun clearTaskRequest() {
        taskRequest = null
    }

    /**
     * Hands a task to a running agent as if it had been pasted and submitted.
     *
     * Pasted rather than typed: a prompt is several lines, and a terminal that receives them as
     * ordinary input submits the first one and runs the rest as separate commands. Whether the
     * agent asked for bracketed paste is the backend's business, because only it knows.
     */
    fun sendTask(sessionId: String, task: Task) {
        if (task.isEmpty) return
        onSendPrompt(sessionId, task.body.trim())
        taskRequest = null
    }

    /** Unfinished first, newest within each half. A stable sort, so equal times keep their order. */
    private fun order(list: List<Task>): List<Task> =
        list.sortedWith(compareBy({ it.done }, { -it.updatedAt }))

    private fun persistTasks() {
        val path = project?.path ?: return
        taskStore.save(taskStore.load() + (path to tasks))
    }

    private fun loadTasks(path: String?) {
        tasks = order(path?.let { taskStore.load()[it] }.orEmpty())
        selectedTask = tasks.firstOrNull()?.id
    }

    var agentRequest by mutableStateOf<AgentRequest?>(null)
        private set

    fun requestNewAgent(axis: SplitAxis? = null) {
        agentRequest = AgentRequest(axis)
    }

    fun clearAgentRequest() {
        agentRequest = null
    }

    /** The pane a split would divide, which is what the picker starts from. */
    val focusedAgentSession: TerminalSession?
        get() = agents.firstOrNull { it.id == focusedAgent } ?: agents.lastOrNull()

    fun closeFocusedAgent() {
        focusedAgent?.let(::closeAgent)
    }

    fun toggleFocusedAgentZoom() {
        focusedAgent?.let(::toggleAgentZoom)
    }

    /**
     * Leaves the wall and shows this pane's worktree in the project view, opening its repository
     * first when the pane belongs to one that is not currently loaded.
     */
    fun showWorktreeInProject(session: TerminalSession) = run("Opening worktree") {
        val owner = session.projectPath
        if (owner != null && !samePath(owner, project?.path.orEmpty())) {
            openProjectAt(owner, select = session.workDir)
        } else {
            reloadProject(selectPath = session.workDir)
        }
        mode = AppMode.PROJECT
    }

    /** How many agents are running in [worktree], for the worktree menu. */
    fun agentCountFor(worktree: Worktree): Int = agents.count { it.workDir == worktree.path }

    /** Starts an agent in a worktree the user has already named, and shows the wall. */
    fun startAgentFor(worktree: Worktree, agent: AgentSpec? = availableAgents.firstOrNull()): Job? {
        switchTo(AppMode.AGENTS)
        return openAgent(worktree, agent = agent)
    }

    /** Takes the user to the agent already running in [worktree]. */
    fun focusAgentFor(worktree: Worktree) {
        val session = agents.lastOrNull { it.workDir == worktree.path } ?: return
        switchTo(AppMode.AGENTS)
        // A different pane may be zoomed; show the wall rather than swapping one zoom for another.
        zoomedAgent = null
        focusAgent(session.id)
    }

    fun focusAgent(id: String) {
        focusedAgent = id
    }

    fun toggleAgentZoom(id: String) {
        zoomedAgent = if (zoomedAgent == id) null else id
        focusedAgent = id
    }

    // ---------------------------------------------------------------- plumbing

    /** Runs [block] as the single in-flight git action, showing [label] in the status bar. */
    private fun run(label: String?, block: suspend () -> Unit): Job =
        scope.launch {
            gitLock.withLock {
                if (label != null) busy = label
                try {
                    block()
                } catch (e: Exception) {
                    notice = Notice(e.message ?: e::class.simpleName.orEmpty(), isError = true)
                } finally {
                    if (label != null) busy = null
                }
            }
        }

    /** A git action against the selected worktree that always refreshes afterwards. */
    private fun mutate(label: String, block: suspend (dir: String) -> CommandResult): Job = run(label) {
        val dir = selectedWorktree?.path ?: return@run
        runIn(dir, block)
    }

    /** The same, for an action aimed at a worktree that is not necessarily the selected one. */
    private fun mutateIn(dir: String, label: String, block: suspend (dir: String) -> CommandResult): Job =
        run(label) { runIn(dir, block) }

    private suspend fun runIn(dir: String, block: suspend (dir: String) -> CommandResult) {
        val result = block(dir)
        if (!result.ok) {
            fail(result)
        } else {
            result.message.takeIf { it.isNotBlank() }?.let { notice = Notice(it.lines().first(), isError = false) }
        }
        reloadProject(selectPath = dir)
    }

    private fun fail(result: CommandResult) {
        notice = Notice(result.message.ifBlank { "git exited with ${result.exitCode}" }, isError = true)
    }

    private companion object {
        const val MAX_LOG_ENTRIES = 500

        /** Enough matches to find what you meant; past this the list is scrolled, not read. */
        const val MAX_SEARCH_RESULTS = 300

        /**
         * How many `git status` runs the badge sweep keeps in flight.
         *
         * The sweep is bounded by the disk, not the processor, so this trades the *first* badge
         * against the last rather than buying throughput. Measured on a repository of 62 worktrees
         * of 11 500 files each, over six runs in varying order: the whole sweep takes 5.3 s at 8,
         * 5.5 s at 6 and 5.9 s at 4 — all within a few per cent — while the first badge lands at
         * 450 ms, 290 ms and 230 ms, and the eighth (the last row a list is showing) at 1030 ms,
         * 730 ms and 780 ms. Six is the corner: it fills the visible rows a third sooner and gives
         * up nothing measurable at the end. Above 8 both halves get worse.
         */
        const val MAX_PARALLEL_STATUS = 6

        /** Past any file anybody opens in a pane, which is how `-U` is asked for "all of it". */
        const val WHOLE_FILE_CONTEXT = 1_000_000


    }
}

val RepoOperation.label: String
    get() = when (this) {
        RepoOperation.NONE -> ""
        RepoOperation.MERGE -> "merge"
        RepoOperation.REBASE -> "rebase"
        RepoOperation.CHERRY_PICK -> "cherry-pick"
        RepoOperation.REVERT -> "revert"
        RepoOperation.BISECT -> "bisect"
    }
