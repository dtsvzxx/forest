# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Forest** — a Compose Multiplatform desktop app for managing git worktrees, styled after the
Android Studio / IntelliJ **New UI** dark theme. Kotlin Multiplatform with a single `jvm()` target,
shipped to macOS, Linux and Windows. Package root `io.mainactor.worktree`. See `README.md` for the
feature list.

## Commands

```bash
./gradlew :desktopApp:hotRun --auto   # run with Compose hot reload (preferred while iterating)
./gradlew :desktopApp:run             # standard run
./gradlew build                       # compile + all tests
./gradlew :shared:jvmTest             # shared tests (commonTest + jvmTest)
./gradlew :terminal:jvmTest           # terminal: emulator and pseudo-terminal, seconds
./gradlew :desktopApp:test            # off-screen render test
./gradlew :shared:jvmTest --tests "io.mainactor.worktree.git.DiffParserTest"          # one class
./gradlew :shared:jvmTest --tests "*GitIntegrationTest.lists the main worktree*"      # one test
./gradlew :desktopApp:packageDistributionForCurrentOS                                 # installer
```

No lint/format task is configured; `kotlin.code.style=official`. Gradle 9.1 with configuration
cache **and** build cache on, so build-script changes must stay configuration-cache compatible. JVM
toolchain pinned to **Azul 25** — in `gradle/gradle-daemon-jvm.properties` (generated, regenerate it
with `./gradlew updateDaemonJvm --jvm-version=N --jvm-vendor=AZUL` rather than by hand) and again as
an explicit `jvmToolchain(25)` in both modules, because the version that compiles this is a
behavioural fact: the terminal's pseudo-terminal is moving onto `java.lang.foreign`, which is final
only from JDK 22. Every JVM the build launches carries `--enable-native-access=ALL-UNNAMED` — the
packaged `Forest.cfg`, `run`/`hotRun` and the test tasks — since JDK 24 warns on each restricted
call without it and JDK 26 will refuse.

## Architecture

Three layers, and the seam between them is what keeps the app testable:

```
desktopApp/          Window, JediTerm terminal, wiring (main.kt)
shared/jvmMain/      ProcessCommandRunner, JvmFileSystemAccess, SwingDirectoryChooser, GitLocator, Os
shared/commonMain/   models · GitParsers · Git · AppState · the entire Compose UI
terminal/            a terminal of our own: pseudo-terminal (FFM), emulator, view
```

**`:terminal` is a module rather than a package, and the boundary is the point.** Nothing in it can
reach `AppState` or `Git`, which is what keeps the emulator a pure function from bytes to a screen;
`./gradlew :terminal:jvmTest` is a seconds-long loop that never touches the UI. The dependency graph
runs one way and stops: `:terminal` knows nothing of `:shared` (its API takes an id, a directory and
a command line — not a `TerminalSession`), `:shared` knows nothing of `:terminal` (`App` takes the
pane as a composable slot), and only `:desktopApp` sees both. It is shaped like `:shared` — Kotlin
Multiplatform with a single `jvm()` target — so `commonMain` can depend on it without surprises in
the published metadata.

`commonMain` never touches the JVM directly. Everything platform-specific goes through three
interfaces in `platform/Platform.kt` — `CommandRunner`, `FileSystemAccess`, `DirectoryChooser` —
which are constructed in `main.kt` and injected. That is why `Git` can be tested with a fake runner
and `AppState` can be driven end-to-end without a window.

**Source-layout gotcha:** `:shared` is KMP (`src/commonMain/kotlin`, `src/jvmMain/kotlin`,
`src/commonTest`, `src/jvmTest`) while `:desktopApp` is a plain `kotlinJvm` module
(`src/main/kotlin`, `src/test/kotlin`).

### Git layer

`Git` (`git/Git.kt`) drives the **`git` binary**, never a JVM reimplementation. That is deliberate:
worktrees, rebase, hooks, credential helpers and conflict handling are exactly where a
reimplementation diverges from what the user sees in the terminal sitting next to the UI. Every
invocation is funnelled through `Git.run`, which logs a `GitLogEntry` to the Console tab, forces
`GIT_TERMINAL_PROMPT=0` (never block on a credential prompt) and disables colour/pager.

**Creating a worktree from a remote branch is not `git worktree add <path> origin/feature`.** That
resolves the ref, finds it is not a local branch, and checks the commit out **detached** — while
reporting success, so the worktree is made and simply has no branch, which is what the dialog's
branch picker used to do with every remote entry it listed. The command that means what was asked
is `--track -b feature <path> origin/feature`, so `NewWorktreeDialog` keeps the selected `Branch`
rather than its name (only `isRemote` tells `origin/feature` from a local branch called that) and
sends a *new* branch on the remote ref. `--track` is explicit and not redundant: `-b` off a
remote-tracking ref sets the upstream only because `branch.autoSetupMerge` defaults to true, and a
user who turned it off would get a branch with no upstream. `a worktree made from a remote branch
gets a local branch tracking it` pins all of it, including git's detaching — so the day git starts
doing the friendly thing, the test says the workaround can go.

**Worktree ordering** pins the main working tree to the top — it is the repository itself, not one
of the disposable checkouts around it — and orders everything below it by last activity, most recent
first (`AppState.withActivity`), with the age shown in the row and its source in the row's tooltip.
Activity is the newer of:

- the HEAD commit date — all of them from a single `git log --no-walk` (`Git.commitTimes`), one
  child process for the whole list rather than one per worktree;
- the newest mtime among the files git currently reports as changed (`WorktreeActivity`), so a
  worktree you edited minutes ago outranks one you committed to yesterday.

Three filesystem approaches were measured and rejected, and the reasoning is in `WorktreeActivity`'s
KDoc: the worktree directory's mtime does not move when a file inside it is edited (a directory's
mtime tracks only its own entries); walking the tree costs ~300 ms per 20 000 files, times every
worktree, on every refresh; and the git index looks tempting — `GIT_OPTIONAL_LOCKS=0` keeps our own
polling from touching it — but `git worktree add` and `git commit` stamp it with the current time,
which buries the commit dates it was meant to complement.

Because the changed-file list only exists after the badge sweep, ordering settles in two steps: a
provisional order on open, refined when the sweep lands. Tests must `badgeRefresh?.join()` before
asserting on order. Timestamps have one-second resolution, and ties break by branch name.

**Refresh cost is a hard constraint.** A refresh runs after every action, and repositories with
dozens of worktrees are the point of this app, so the work must not scale with the worktree count
on the interactive path:

- `status(detailed = false)` is the summary form used for list badges — no `--untracked-files=all`,
  no rename detection. Only the selected worktree gets the detailed status.
- `currentOperation` reads the marker files under the worktree's git dir (one `rev-parse`), rather
  than one `rev-parse --verify` per candidate ref. `status()` does **not** call it; `AppState` does,
  for the selected worktree only.
- `AppState.refreshBadges` sweeps all worktrees in the background (bounded by `MAX_PARALLEL_STATUS`,
  outside `gitLock` — safe because `GIT_OPTIONAL_LOCKS=0` keeps `status` off `index.lock`). It is
  exposed as `badgeRefresh` so tests can join it.
- `loadWorktree` issues its independent reads concurrently.
- Dating the worktree list is one command regardless of its length (see above).

**The sweep publishes each badge as it lands, and that is the whole of what a big repository
feels.** It used to assign the finished map in one go, so on a real repository of 62 worktrees the
list showed no state at all for six seconds after the project was opened and then filled in at
once. The sweep is bounded by the **disk**, not by the processor — one `git status` there is
~110 ms warm and the whole sweep takes 5.3 s at 8 in flight, 5.5 s at 6 and 5.9 s at 4, so
concurrency buys almost no throughput and only decides which badge is first. Both halves follow
from that: statuses go into `worktreeStatuses` one at a time (the full map still replaces it at the
end, so a worktree that has gone loses its badge with it), and `MAX_PARALLEL_STATUS` is 6 rather
than 8 because that lands the first badge in 290 ms instead of 450 ms and the eighth — the last row
a list is showing — in 730 ms instead of 1030 ms, for a few per cent on a total nobody is watching.
Measured end to end against that repository: the list and the selected worktree at 409 ms, the
first badge at 737 ms, all 62 by 5.8 s.

The dominant cost is not the untracked walk: `git diff-index --quiet HEAD` alone, over all 62, is
3.3 s of the 5.3 s. It is one `lstat` per tracked file per worktree — 700 000 of them — and no
choice of git flags avoids it.

**The last sweep is remembered, so the second opening of a project draws instantly**
(`StatusStore`, `~/.worktree/status.json`). Publishing each badge as it lands took the *first* one
down to 737 ms and could not do better, because the sweep is bounded by the disk; drawing what the
sweep found last time takes it to nothing. Measured on the same 62-worktree repository: a cold open
completes its badges at 6.9 s, a warm one has **all 62 on screen at 453 ms** — as soon as the window
has the list at all — and the sweep still runs behind it, finishing at 5.6 s and correcting whatever
moved. The file is 12 KB.

Three things about it are deliberate:

- **A cached badge is a claim about the past, and that is the trade.** It is right almost always,
  since a checkout nobody has touched has not changed, and wrong for as long as the sweep takes when
  somebody edited it from a terminal meanwhile. What is on offer is not a correct badge but five
  seconds of *no* badge, and nothing destructive reads these numbers.
- **The changed files are cached, not only the counts.** `withActivity` dates a worktree by the
  newest mtime among them, so without them the list opens in commit-date order and re-sorts when the
  sweep lands — the same jump this is meant to remove. The branch, head and in-progress operation
  are deliberately *not* stored: the row reads those off the `Worktree`, and a stale branch would be
  a different kind of wrong.
- **Only a finished sweep is written.** A cancelled one is a partial picture, and remembering it
  would put half of one state beside half of another on the next open.

`RefreshCostTest` builds a 40-worktree repository and fails if either budget regresses; `a badge
appears as it arrives, not when the last one lands` holds one worktree's status open and checks the
other 40 are already published, which the one-shot assignment could not do; and `a second opening
draws the badges before the sweep has run a command` holds the *whole* sweep open, which is the
stronger claim — not sooner, but without waiting for git at all.

**A commit's own diff** comes from `Git.commitDiff`, which is `git show --format= -m
--first-parent`. The `-m --first-parent` pair is the whole point: without it git prints an *empty*
diff for any commit with two parents, and the log of a shared repository is mostly merges. It is
one child process for the whole commit — the Log tab's file list is the `path` of each returned
`FileDiff`, not a second `--name-status` call — and nothing calls it until a commit is clicked,
because the Log tab is opened to scan subjects far more often than to read a patch.

**The Log tab's age column is the committer date** (`%cr`), because that is the clock git orders
the walk by. It was `%ar`, the author date, and the two disagree for exactly the commits a shared
repository is full of: a rebase or a cherry-pick keeps the author date and takes a new committer
date, so the top row of a real repository read "6 weeks ago" over rows saying "7 days ago" and the
list looked shuffled while being perfectly sorted. It is also the clock `commitTimes` (`%ct`) dates
the worktree list by. `the log's age is the one git sorts by` pins it, and checks the two clocks
really disagree in its fixture rather than passing on a repository where they cannot.

**File search** is `Git.listFiles` (`git ls-files -z`) once per worktree, filtered in memory by
`AppState.search` — a child process per keystroke on a tree of twenty thousand files is the
difference between instant and unusable. The index is not snapshot state (it is large and never
drawn; only `searchResults` is), `searchIndexFor` records which worktree it belongs to, and
`loadWorktree` clears that marker so a commit or checkout costs one `ls-files` on the next search
rather than on every refresh. `searchIndexJob` is exposed for the same reason `badgeRefresh` is.

`Git.fileHistory` uses **`--follow`**, and that is a deliberate trade: plain `git log -- <path>`
stops dead at the commit that renamed the file, hiding most of the history on a repository that
reorganises packages. The price is that git lists commits in which the file had another name, and
`commitFileDiff` has no patch for that path there — the pane says so rather than going blank. Both
halves are pinned by `a file's history reaches back past a rename`.

`GitParsers` holds the pure parsing of git's machine-readable formats and carries the bulk of the
unit tests. Two things there are easy to break:

- `parseStatus` reads `--porcelain=v2 -z`. Rename records (`2`) consume a **second** NUL-delimited
  token for the original path; missing that shifts every following record.
- `parseConflicts` handles both `merge` and `diff3` conflict styles. "No base section" and "empty
  base section" are different states — the `sawBase` flag, not `section >= 1`, decides.

Field separators: `GitParsers.FS` (`U+001F`, used in `--format` strings) and `GitParsers.NUL`.
Never put these characters in a source file literally.

**Persisted state** lives in `~/.worktree/`: `recent` is the project list in the order the user
added them, `last` is the project to reopen on startup, and `status.json` is the badge cache above —
the only one of them that is a cache rather than a preference, and so the only one that may be
deleted without losing anything. The list is deliberately never reordered
by use — entries that move under the pointer are harder to navigate — so "most recent" is tracked in
its own file rather than encoded in the ordering.

### State

`AppState` is a plain class of `mutableStateOf` properties plus the actions the UI triggers. All git
work is serialised through a `Mutex` — concurrent commands race on `index.lock`. Every public action
returns the `Job`, which is what makes `AppStateIntegrationTest` able to `.join()` on it.

**Paths must be compared with `samePath`/`fs.canonicalPath`.** git always reports canonical worktree
paths; a path from a dialog or from `File(...)` may not be one (on macOS `/var/…` vs `/private/var/…`),
and a naive `==` silently selects the wrong worktree.

**Commit carries an optional push**, and the two are one action rather than two calls from the
dialog: one lock, one refresh, and a status bar that names the step it is on. The push is skipped
when the commit failed — there is nothing new to send then, and pushing regardless would publish
whatever the branch already held as if it were the commit just written (`a commit that fails is not
pushed`). The checkbox starts clear every time the dialog opens: pushing leaves the machine, and a
box that remembers itself eventually publishes a commit because it was ticked for a different one.
What the push will run is `AppState.pushCommand` — the toolbar button's `detail` and the checkbox's
own label read it from there, so neither can drift from `pushFrom`, which is the only place that
decides a branch with no upstream needs `-u`.

### UI

`WorktreeTheme` provides `WorktreeColors` through `LocalWorktreeColors` — Material3's scheme covers
only a fraction of what an IDE-style UI needs, so most colours come from there. Icons are drawn on a
`Canvas` (`ui/components/Icons.kt`) rather than pulled from an icon library. Dialogs are in-window
modals (`ui/dialogs/Dialogs.kt`), not separate windows.

**The macOS title bar is hidden and its buttons are not.** `window/TitleBar.kt` sets three client
properties — `apple.awt.fullWindowContent` (the content pane reaches into the bar's strip),
`transparentTitleBar` (AppKit stops painting the bar and its hairline over it) and
`windowTitleVisible` (the centred title text goes). Not `undecorated = true`, which would take the
close/minimise/zoom buttons with the bar, along with the native resize edges, shadow and rounded
corners. `TitleBarTest` asserts the window's top inset drops from 28pt to 0 — what AppKit did,
rather than what it was asked to do — and it uses `pack()`, so no window ever appears on screen.

The toolbar then inherits everything that strip used to do, through `ui/WindowChrome.kt`, a global
the platform layer fills in the way it does `AgentShortcuts`; it is inert by default, so the render
tests and the other platforms are untouched.

- It starts at `controlsWidth` (78dp: the buttons are 12pt wide, 20pt apart, from 20pt) instead of
  the usual 8dp — the buttons are drawn by the window server on top of whatever is there, and a
  mode switch under the close button cannot be clicked. Full screen takes them away, and the inset
  goes with them. `the toolbar keeps clear of the window's own buttons` renders both and reads the
  corner.
- It drags the window, because the bar that did is gone. `WindowDrag` computes the position from
  the pointer's *screen* coordinates against where the press landed, never from per-event deltas: a
  delta is measured against a window that has already moved under the pointer, and should AppKit
  turn out to drag the window as well, an absolute target lands in the same place instead of twice
  as far. The AWT listener is global because Compose's canvas is what AWT delivers mouse events to,
  and half of a drag that moves a window happens outside it.
- A press consumed by a button inside the toolbar never reaches `Modifier.windowHandle`, which is
  what keeps the controls clickable and leaves only the bar's own background dragging the window.

**One control opens the terminal**, the toolbar's own button, and it opens a shell in the selected
worktree when there is none (`the terminal button opens the first shell and then only hides it`).
There were three, all drawn with the same glyph: this one, one on the right pane's tab strip calling
the identical action, and one in the Worktrees header. A row of near-identical icons is worse than a
single obvious one, and the two that went were the two that had no business being there — that tab
strip switches what the pane shows, and the terminal is not one of its tabs. Opening a shell in a
*particular* worktree stayed on that worktree's context menu, and the tool window keeps its own
tab `+` and hide `−`, which are window controls rather than another way in.

Every `ToolButton` carries a `tooltip`, and the git-driven ones a `detail` holding the exact command
they run — at 14dp an icon does not say what `git worktree prune` will do, and the Console tab's
"nothing is hidden" promise is worth applying to the buttons too. `Tooltip` wraps Compose Desktop's
`TooltipArea`; `AppRenderTest` drives a real hover to prove one actually appears.

**Panes are rounded cards on a lighter frame, and which way round the two greys go is the whole
look.** `frame` (Gray2, `MainToolbar.background`) is the window — the toolbar, the status bar and
every gap between panes — and a pane is Gray1 (`EditorTabs.background`), so the tool windows and the
editor share one surface and float on it. Forest had it the other way round, Gray2 panes over a
Gray1 window, which is the older flat look where a pane is a *lighter* region with a 1px rule beside
it. Both values were read twice: out of `expUI_dark.theme.json` (in
`Android Studio.app/Contents/lib/intellij.platform.ide.impl.jar`, under `themes/expUI/`) and back
off a screenshot of the IDE, where a row of pixels across the window reads Gray2 in the gaps and
Gray1 either side of them. The radius came the same way — `Component.arc` is 8, and the corner
measured off that screenshot is 8dp once its 1.25 scale is divided out.

Three things follow, and the second is the one that catches people:

- `Modifier.pane()` is a **clip and nothing else**. The pane already paints its own background, and
  clipping bends that paint round the corner; a border drawn on top could not let the frame show
  *through* the rounding.
- **A splitter between two panes draws nothing at rest.** The strip *is* the gap — `Dimens.paneGap`,
  the same 7dp as `splitterThickness`, because a 4dp gap (which is what the IDE has) is not a grab
  target. Only a splitter *inside* one pane passes `separator`, and the wall's `ProportionalSplitter`
  draws nothing either, its panes having borders of their own.
- `HorizontalDivider`/`VerticalDivider` default to **`separator`**, not `border`. On Gray1 panes
  `border` is the same value as the surface, so the old default was painted and invisible
  everywhere rather than only in the two panes that had noticed.

`a pane is a rounded card with the frame showing through its corner` reads the corner pixel
directly. Every other render test passes just as happily on square panes butted together, which is
what made it worth writing: it finds the pane's edges by scanning and then asserts the corner is
frame while both edges are not.

**The palette is sourced, not invented.** Chrome comes from JetBrains' New UI theme
(`expUI_dark.theme.json`: the `Gray1…Gray14` / `Blue*` ramps, `Component.arc` = 8,
`EditorTabs.underlineHeight` = 4, `List.rowHeight` = 24); VCS and diff colours come from the editor
scheme Android Studio inherits (`FILESTATUS_*`, `DIFF_INSERTED`/`DIFF_DELETED`), and the terminal
uses that scheme's `CONSOLE_*` ANSI colours. Every value in `Theme.kt` carries its source key in a
comment — keep that up when changing one. Three New UI traits the components implement by hand:
list selection is a rounded rectangle inset from the pane edge (`Dimens.selectionArc` /
`selectionInset`), tab underlines are thick and rounded in `accent`, and `*.borderColor` is
*darker* than the surfaces it separates.

Rows carry a right-click `ContextMenuArea` rather than showing paths inline — the project list is
scanned by name, and a path on every row is long, near-identical between entries and rarely what you
are looking for. The path stays reachable through the menu, the row tooltip, and the status bar.
Anything that leaves the window (revealing a path, the clipboard) goes through `SystemIntegration`;
`revealCommand` is a pure function precisely because the three desktops disagree and only one of
them can be exercised from any given machine.

**Pane sizing is absolute, not fractional.** `projectsWidth`, `worktreesWidth`, `terminalHeight` and
the changes pane's `fileListHeight` are `Dp`; every splitter takes a `size`/`onSizeChange` pair
rather than a fraction. That is what makes a bigger window grow only the flexible pane — with
fractions every pane grew at once and the side panels drifted wider with the window. Shrinking is
handled by squeezing the *displayed* size against a share of the available space (`SIDE_PANES_MAX_SHARE`
and friends) while leaving the stored value alone, so the panes come back when there is room again.

Layout traps that have already bitten this code:

- **`border` is Gray1 — the same value as `editor`, and now as `panel` too.** It is what the New
  UI calls a border, *darker* than what it edges, and it is why a divider drawn with it is painted
  and invisible. That trap is closed rather than documented: the dividers default to `separator`
  (Gray3). `AppRenderTest` scans a column of the rendered Search tab for rows of that exact colour,
  so the line has to be visible and not merely present.
- **A column of text sized by a guessed `dp` width will eventually wrap.** The Log tab's hash
  column was 62.dp against an eight-character monospace hash, which fitted in theory and wrapped in
  practice; the second line was then clipped by the row height. Where a column is monospace and
  uniform, pad the *string* to the widest value in the list and let it size itself — that is exact
  alignment with nothing to measure. Elsewhere, `maxLines = 1` at minimum.
- **Only ever put one weighted child in a row of flexible text.** Several `weight(1f)` children —
  including a `Spacer(Modifier.weight(1f))` used to push things apart — split the row evenly no
  matter what they contain, so a branch name truncates at half the width with a gap beside it. The
  worktree row and the status bar were both wrong this way. The fix is one weighted child that
  absorbs the slack; anything that must stay at its natural size goes unweighted (capped with
  `widthIn` if it could grow without bound).
- **A `pointerInput` block captures its lambda once.** Reading composed state inside a drag handler
  gives you the value from when it was composed, and several drag events can arrive between two
  frames — `rememberUpdatedState` is not enough. `Splitter` keeps the running position in a local
  variable inside the gesture; without that the dividers advanced one step and froze.
- A child with `fillMaxWidth()` makes its parent `Box`/`Row` take the full available width. The
  selected-tab underline is drawn with `drawWithContent`, not laid out, for exactly this reason.
- Do not nest a lazy list inside a lazy list item in the same direction. `ConflictsPane` renders
  conflict sides as plain `Column`s, capped at `MAX_SIDE_LINES`.
- `DiffView` pins the gutter by giving every row's scrollable half the *same* fixed content width
  (measured once from the longest line); rows of differing widths clamp their scroll offsets
  differently and drift apart.

### Syntax highlighting

`SyntaxHighlighter` is an interface in `commonMain` with `TreeSitterHighlighter` behind it, the same
shape as `CommandRunner` — the parser is native code, and it is why a render test can run with
`SyntaxHighlighter.None` and no colour at all. The mapping from a parse tree to a colour is
deliberately *structural* rather than a table per language: an anonymous node whose type is all
letters is a keyword, a named node whose type ends in `comment` is a comment. That is a guess about
grammars nobody here wrote, and it works for one nobody has looked at yet.

**tree-sitter counts bytes; a Kotlin `String` counts UTF-16 code units.** The two agree only while
the text is ASCII, so a node's `startByte` used straight as a string index paints everything after
the first accented letter, em dash, CJK character or emoji that many positions to the right, and
the drift accumulates down the file: a comment eating the first characters of the next line, a
keyword painted over the space beside it. This repository's own sources are the worst case — their
comments are made of em dashes, three bytes to one char. `CharOffsets` converts, and builds its
table only when the text is not all ASCII. It counts a surrogate as two bytes because a pair is
four between them, which was *checked* rather than assumed: the binding hands the parser real UTF-8
rather than the JVM's modified form, where an emoji would have cost six. `a colour lands where the
character is, not where its bytes are` pins it across all four widths.

**A patch is two files, not one** (`DiffHighlighting`). A deleted line comes from the old file and
an added one from the new, so the patch is reassembled into two texts — context plus deletions, and
context plus additions — each parsed alone. Parsed as they appear on screen instead, an added
comment opener closes over the deleted lines beneath it and a deleted triple quote swallows the
rest of the hunk. Both directions have a test. What no amount of care fixes is that three lines of
context can begin inside a block comment with nothing to say so; the whole-file toggle is the
answer, and with it the parse is exact rather than plausible.

### Modes

`AppState.mode` picks between the three-pane project view and `AgentsPane`, a wall of terminals for
running several agents in one worktree. Its layout is a binary split tree (`model/PaneLayout.kt`) —
a tree rather than an auto-arranged grid because the operation users reach for is "split *this*
pane", which only means anything relative to an existing one. The tree functions are pure and carry
their own tests; `AppState` holds the root and derives `agents` from it. Both use the same `terminal` slot and the same
`TerminalSessionManager`, so leaving the wall — or the mode — never stops a shell; only
`closeAgent`, removing the worktree, or closing the project do, all of them through
`onTerminalDisposed`.

**The terminal's chord is the exception to every rule the others follow.** ⌥F12 / Alt+F12 is the
same on both platforms, because the reason the rest diverge — a bare `Ctrl` combination belongs to
the shell on Linux and Windows — does not apply to a function key with `Alt`; and it is *taken*
rather than chosen, being what the IDE this app is styled after opens its terminal with. It is also
gated separately (`terminalEnabled`), since a terminal is offered on both screens while splitting
and closing panes only mean something on the wall. Its one cost is macOS-only: with the factory
setting where F11/F12 are volume keys it adjusts the volume instead, which is equally true of
Android Studio, and the toolbar button beside the tooltip still works. `AgentKeyBindings.handle` is
public for the test — AWT's focus manager will not dispatch to a component that is not showing,
which is every component in a test, so going through it would only ever prove nothing happened.

Shortcuts are registered as a global AWT `KeyEventDispatcher` (`terminal/AgentKeyBindings.kt`), not
through Compose. A pane is a heavyweight Swing terminal that owns the keyboard focus, so Compose
never sees the chord — only a hook that runs before AWT's own dispatch can claim it. The chords
differ per platform because a bare `Ctrl` combination belongs to the shell on Linux and Windows;
`AgentShortcuts` carries the labels back into the tooltips so the two cannot drift apart. Splitting
and adding both open a dialog that is App's own state, so they go through `AppState.agentRequest`,
a request carrying the split axis that the window observes and clears.

Adding a pane never inherits the divided pane's worktree: agents run across several repositories, so
a split is precisely how one starts somewhere else. The picker is seeded from
`focusedAgentSession`, which is why `TerminalSession` carries a `projectPath` — the wall can hold
panes from repositories the rest of the window does not have open.

**A pane's name is kept in pieces, not as one string.** `TerminalSession` carries `projectName`,
`label` (the worktree's, normally its branch) and `agentLabel` ("Claude Code 2"), and derives
`title` from them for the places with room for only one line — a tool-window tab, a widget's name.
The header used to read "main · 2", which names a branch that half the repositories on the wall
also have and an ordinal that says nothing, with the repository missing entirely — on the one
screen that holds panes from several at once. Only the worktree is weighted, and with
`fill = false`: branch names are the long ones, so it is the part that gives way, while a weight
that *filled* would fling the pane's number to the far right where it reads as part of the usage
badge. `an agent pane is named by its repository, its worktree and its agent` pins the parts; a
tool-window tab sets neither of the other two, because those all belong to the open project and
naming it on every tab is noise. The weight goes on a `Box` around the `Tooltip`, for the same
reason it does around a `ContextMenuArea` — `TooltipArea` wraps its content in a layout of its own.

Navigation runs both ways: a worktree's menu starts or focuses an agent
(`startAgentFor`/`focusAgentFor`), and a pane's `showWorktreeInProject` goes back, opening the
pane's own repository first when it is not the loaded one — which is why `openProjectAt` takes a
`select` argument rather than always landing on the main worktree.

`AppState.worktreesOf` orders the picker through the same `withActivity` the pane uses, including
the status sweep that dates uncommitted work, so the two lists cannot disagree. For the open project
it reuses `worktreeStatuses`; for any other it sweeps that repository, outside `gitLock` so browsing
never blocks the window.

**The wall's terminal is an overlay, and it opens where the focused *pane* is.** Pressing Terminal
on the wall brings up a shell over it, in the worktree of the pane you are looking at — not the one
the project view has selected, because the wall holds panes from repositories the window does not
even have open, and a shell in the wrong repository is the kind of wrong you notice after the
command has run. `AppState.toggleTerminal` is mode-aware for that reason alone; the sessions, the
tabs and the hiding are the tool window's, unchanged. Pressing it twice gives back the same shell
rather than stacking a second — the overlay's own `+` is what opens another.

**Hiding is not closing, and that is the whole of what the user asked for.** `terminalVisible` is a
flag; only `closeTerminal` calls `onTerminalDisposed`, so a build left running in there survives the
overlay being dismissed, the mode being switched and the wall being rearranged.

**The wall is detached while the overlay is up** (`AgentsPane.pausedBecause`). Not politeness: on
the JediTerm engine — still the default — a pane is a heavyweight Swing widget, and Compose drawn
over one of those is painted *underneath* it. Detaching is what `App` already does for a modal and
it is the only thing that works on both engines. The parameter carries the *reason* rather than a
flag because there are two of them now, and a pane reading "paused while a dialog is open" with no
dialog on screen is worse than one that says nothing. `the wall's terminal opens over it, in the
focused pane's worktree` pins both halves by counting which sessions the terminal slot is asked
for — which no pixel could say.

**Nothing spawns a shell implicitly.** Opening a project used to open a terminal and entering the
agent wall used to start an agent; both are gone. A shell is a real process the user did not ask
for, and the wall's own rule — every added pane asks where it runs — makes an automatic one
contradictory. `nothing starts a shell on its own` guards this.

**Pane focus lives in AWT, not in Compose.** A pane is a heavyweight Swing terminal that consumes
mouse clicks, so a `clickable` wrapped around it never fires — clicking inside a terminal cannot
move `focusedAgent` from the Compose side. `TerminalSessionManager.onFocusGained` reports the
widget's own focus events instead, and `EmbeddedTerminal` moves the caret the other way when the
selection changes elsewhere (a header click, a shortcut). Break either half and the highlighted pane
and the one receiving keystrokes drift apart — which also silently seeds the new-agent picker from
the wrong worktree.

Three traps in that pane:

- `ContextMenuArea` wraps its content in a box of its own and its `modifier` overload is
  **internal**, so a `weight` given to something inside it is invisible to the grid's `Row`. The
  layout modifier goes on a `Box` around the menu area; getting this wrong collapsed the whole wall
  into a single column.
- Splitters on the wall move a *fraction*, while the window's own panes move an absolute size:
  inside a wall of terminals every pane should keep its share as the window grows. That is why
  `ProportionalSplitter` exists next to `VerticalSplitter`/`HorizontalSplitter`.
- Actions cannot be named `setMode`/`setAgentWorktree`: a `var x by … private set`
  already emits those JVM setters. They are `switchTo`/`chooseAgentWorktree`.

`TerminalSessionManagerTest` starts six real PTYs at once — the wall's actual shape — and checks
they coexist, that re-entering a pane reattaches the running shell instead of starting another, and
that closing one leaves the rest alone. It needs no display.

### Terminal

`desktopApp/terminal/` runs a login shell on a pty4j PTY, rendered by JediTerm inside a `SwingPanel`.
`TerminalSessionManager` owns the processes so they outlive composition — switching tabs must not
kill a running build; `AppState.onTerminalDisposed` is what actually ends one.

#### The emulator of our own

`terminal/commonMain/.../term/` is a pure function from bytes to a screen: no JVM, no display, no
clock. That is what lets it be finished before anything draws it, and what makes every test below a
few lines of "feed this, read the screen back".

- **`Utf8Decoder` holds a partial character across chunks.** A pty hands over whatever was in its
  buffer, so a four-byte emoji is routinely split between two `read(2)`s; a decoder that restarted
  per chunk would turn the halves into mojibake that only appears under load. Invalid input follows
  the WHATWG rule — one U+FFFD per *maximal subpart*, and the byte that ended a bad sequence is
  reconsidered, so `E0 41` yields U+FFFD and then `A` rather than swallowing the `A`.
- **`EscapeParser` is Paul Williams' table, not an intuition.** The table's value is not that it
  parses `CSI 1 m` — anything does — but that it defines where malformed input comes to rest, which
  is what a terminal spends its undefined moments on: a `cat` of a binary, a program killed halfway
  through a sequence, a stream resuming mid-escape. `a flood of random bytes leaves the parser on
  the ground` is the test that says so. Three departures from the table, each deliberate:
  - a **colon** in CSI parameters separates sub-parameters instead of aborting — the table predates
    `CSI 38:2::255:0:0 m`, and that is the form Claude Code writes;
  - **`BEL` ends an OSC string** as well as `ST`, because nothing writes the compliant form alone;
  - **`ESC` delivers a collected string** rather than discarding it (`ESC \` *is* `ST`), while
    `CAN` and `SUB` discard — they mean cancel.
- **A separator closes the parameter before it.** `CSI ; 5 H` is "row default, column 5" and
  `38:2::255:0:0` has an empty third field, so an absent parameter has to survive as absent rather
  than collapsing into a zero. That was a real bug, caught by the test that names it.
- **The screen is parallel `IntArray`/`LongArray`, never objects.** A 200×50 screen over 10 000
  lines of scrollback is two million cells; as objects that is two million allocations for the
  collector to walk, and a `yes` flood is then spent in the collector rather than the parser.
  `CellStyle` packs a foreground, a background and the attributes into one `Long` (26 + 26 + 12
  bits) — the one thing that does not fit is SGR 58's underline colour, which belongs in a side
  table rather than in every cell.
- **The deferred wrap is where every implementation goes wrong once.** Writing into the last
  column does not move the cursor off the line; it leaves it there with a flag, and only the *next*
  character wraps. Without that, a program that fills a line exactly and then returns the carriage
  has already scrolled, and every full-width box a TUI draws sits one line low.
- **Answering `DECRQM` is a promise, not a formality.** 2 means "supported, currently off"; 0 means
  "never heard of it". Both agent CLIs ask about synchronized output (2026) that way, and answering
  0 makes them redraw in pieces you can watch tear. So `Modes.KNOWN` is a list of promises — adding
  a mode there without implementing it is worse than leaving it out.
- **Blank cells keep the current background**, which is what `BCE` means: a program that paints a
  coloured panel and clears part of it expects the hole to stay the panel's colour, not to become a
  white gash.
- **The terminal answers as itself.** `DA1` is `CSI ? 62;22c` (a VT220 with colour), `DA2` is
  deliberately modest and `XTVERSION` says `Forest(1.0)` rather than impersonating an xterm patch
  level — programs that recognise a specific xterm take paths written for xterm, and we are not it.
- **History is written only when the whole primary screen scrolls.** A program that set a scroll
  region is managing a pane of its own, and its discarded rows would fill your shell's scrollback
  with the middle of a progress bar. The alternate screen writes none at all, and leaving it finds
  the primary screen exactly as it was — that is why quitting `less` does not bury what you ran.

- **Character sets are implemented, and that is not history.** `tmux`, `dialog` and everything on
  `ncurses` still designate DEC special graphics, because a terminfo entry says the terminal has it;
  a terminal that ignores the designation prints `qqqq` where a rule was wanted and `lqqk` for a
  corner, which is the most recognisable symptom of an emulator that stopped short. The part that
  catches implementations out is the **single shift**: `SS2`/`SS3` last exactly one character, not
  until the next escape, and `a single shift lasts exactly one character` is the test that says so.
  `REP` repeats what reached the screen, so the translation happens before it is recorded.
- **The cursor's shape is not decoration.** A modal editor sets a bar in insert mode and a block in
  normal mode, and that is often the only thing on screen saying which mode you are in — so
  `DECSCUSR` is carried on the model and drawn, with the odd request numbers blinking and the even
  ones still. Three separate reasons stop the cursor being drawn at all, and each is a real case:
  the pane is not focused, it is scrolled back into history where the cursor is not, or the program
  hid it with `DECTCEM` while it repaints.

**Resizing rewraps** (`Reflow.kt`). Without it, narrowing a pane throws away everything past the
new edge and widening leaves the old breaks in place, so a paragraph of build output stays ragged
for the rest of the session. `TerminalLine.wrapped` is the whole mechanism — it is the only record
of the difference between "the program printed a newline here" and "the terminal ran out of room
here", and only the second may be undone. Four rules, each with a test that names it:

- the primary screen and its history rewrap **together**, because a wrapped line can straddle the
  boundary between them and rewrapping the two separately would leave a break in the middle of a
  line that has just become joinable;
- the alternate screen is **not** rewrapped: it belongs to a full-screen program that will redraw
  it for the new size, and a rewrap would fight that redraw with a stale copy of the old screen;
- the cursor keeps the *character* it was on rather than its row and column, or a prompt jumps out
  from under the person typing into it — which is why the cursor goes into the resize in document
  coordinates and comes back in them;
- a double-width character is never split across the new edge, and narrow → wide → narrow gives
  back what was there;
- **the blank rows below the cursor are padding, not content.** A screen is always as tall as the
  terminal, so one with a prompt on its second line carries the rest as blanks; counted as content,
  they leave the rewrap no room and it pushes real text off the top into history. What the user sees
  is the first half of every long line vanishing as they drag the splitter, with the remainder
  starting mid-word — this app's own agent panes, on this machine. `TerminalBuffer.resize` drops
  them before the rewrap (`TerminalLine.isPadding`, which insists on the erase style, since a row of
  coloured blanks is something a program painted) and the screen is padded out again afterwards. A
  screen that is genuinely full still gives ground, because there is nowhere else to put the rows.
  Both halves are named by tests, and the reason every earlier test missed this is worth keeping:
  they read the *document*, where nothing was ever lost, rather than the screen.

**Every line in the buffer is exactly `columns` wide, and a resize is what breaks that silently.**
The painter walks a row to `model.columns` and the emulator writes at `cursorColumn`, neither
asking a line how wide it is — so one line left at the old width keeps working until something
reaches past its end, and then throws on another thread, in a method with nothing to do with
resizing, minutes later. That happened: padding lines were created from the property rather than
from the new width, and growing a pane while a full-screen program was running produced `Index 348
out of bounds for length 80` on the reader thread. The width is now passed to each collection
explicitly, and `ResizeInvariantTest` checks the invariant across screen, alternate screen and
history rather than trusting it.

Shrinking keeps the **newest** rows and pushes the oldest into history, which is the opposite of
what the crude implementation did: the prompt is at the bottom of a shell's screen, and a pane that
kept the top rows would hide what the user is typing into behind whatever scrolled past an hour ago.

**Two side tables hang off a line, and both are usually null.** A cell holds one code point and one
style, which is all almost every cell needs, so anything rarer lives beside the arrays rather than
widening them:

- **combining marks**, a `HashMap` per line that is allocated only when something needs one. A
  zero-width character belongs to the cell before it — an accent written separately from its letter,
  or the joiners a family emoji is built from — and dropping them turns one thing on the screen into
  several. "The cell before the cursor" is not simply one to the left: after a double-width
  character the cursor is two columns along, and the mark belongs to the character rather than to
  its continuation cell. Marks move with their cells through `ICH`/`DCH` and through a rewrap, and
  they are copied with them, because an accent left behind on the clipboard makes a pasted word a
  different word.
- **hyperlinks** (`OSC 8`), an `IntArray` of ids allocated the first time a line carries one. Cells
  hold an id rather than an address, so a thousand cells of one link cost a thousand ints and two
  runs of the same address can still be told apart. Only the link **under the pointer** is
  underlined: underlining every one would put a rule under half a coloured build log, and a link
  matters at the moment you are about to click it. Opening it is the application's job, handed out
  through a callback — `:terminal` does not know what a browser is.

A cell that carries marks is drawn as a cluster rather than as a glyph, which breaks the run: the
accent has to be placed against its letter, and that is the shaper's work, not arithmetic's.

Known gaps, each deliberate and each with a name: left and right margins are not implemented and,
more to the point, **not advertised**, so nothing asks for them; `CharWidth` is a hand-written range
table rather than one generated from `EastAsianWidth.txt`; and there is no conformance suite —
`JediTermDifferentialTest` is what stands in for one.

`LiveTerminalTest` is the one place the halves are checked together — a real `/bin/sh` on a real
pty, chunked however the kernel chose, drawn by the emulator. `LiveResizeTest` joins the other two:
the view measures itself and the pty carries a size, each proved separately, and neither says the
two are wired to each other — so it lays a real pane out narrow, widens it, and asks the shell
itself what `stty size` says.

#### Recorded streams, and the reference beside them

`./gradlew :terminal:recordFixtures` records what `vim`, `less`, `tmux` and a shell actually print
into `terminal/src/jvmTest/resources/term/`, each with a committed screen dump reviewed once by eye.
A tool rather than a test, the same arrangement as `generateIcons`: the suite must not depend on
`vim` being installed, and the recordings are the *input* to the tests rather than a product of
them. Three things about the recorder were learnt the hard way and are worth keeping:

- it drives **our own pty**, not `script`, which fixes the terminal size so a program lays itself
  out identically every time, and makes a recording that cannot be made a pty bug found early;
- it **snapshots the bytes before closing the pty**, because closing hangs the terminal up and a
  full-screen program's last act is to leave the alternate screen and print why it died — a
  recording taken afterwards is of vim's obituary rather than of vim;
- tmux gets a config of its own, because the default status line carries the machine's hostname and
  a clock: one would commit somebody's machine name to the repository and the other would make the
  recording different every minute.

**The highest-yield test in the suite is `a recorded stream paints the same screen however it is
chopped up`.** Almost every real emulator bug is a parser state that did not survive a `read(2)`
boundary, and a pty hands over whatever happened to be in its buffer — so those splits happen
constantly under load and never while anyone is watching. Each recording is chopped at twenty sets
of random offsets with a fixed seed, and then fed one byte at a time.

`JediTermDifferentialTest` feeds the same bytes to both engines and compares the screens: a free
oracle for exactly as long as both exist, and scaffolding that goes when JediTerm does (its
dependency is `jvmTest` only, so it reaches no bundle and no notice). JediTerm is a **reference, not
an authority**. Two divergences are already recorded, and each is a fact worth knowing: it marks the
right half of a wide character with a private-use code point where we leave the cell out of the text
— a difference in how a screen is written down, not in what is on it — and it prints a stray `:`
for `CSI 4:3 m` because it follows the 1970s table into its ignore state. The second one is asserted
in a test of its own, so a change on either side has to come and look at it.

`TerminalThroughputTest` holds the budgets, set well under what this machine does (about 150 MB/s of
plain output) so a failure means a regression rather than a busy afternoon: 30 MB/s plain, 15 MB/s
coloured, 20 MB/s on a 400-column pane, and a history that stops at its ceiling however much is
printed. A terminal that cannot outrun a pty is not slow, it is broken.

#### The pseudo-terminal of our own

`terminal/jvmMain/.../pty/` starts a process on a pty through `java.lang.foreign` — no JNA, no
native library of ours, nothing in the bundle that has to be signed, hardened or notarized. The
trick that makes it possible without the fork helper every JVM pty library ships (and which is what
Apple rejected this app's first notarization over) is two-sided: `POSIX_SPAWN_SETSID` makes the
child a session leader with no controlling terminal, and a `posix_spawn` file action that **opens
the slave by path** onto fd 0/1/2 is what then gives it one — a session leader that opens a terminal
without `O_NOCTTY` acquires it. `posix_spawn_file_actions_addchdir_np` supplies the working
directory, so the command line a pane was asked to run is never rewritten into a `cd … && exec …`.

Four details there were established by probing, and each one silently breaks something:

- **`ioctl` is variadic**, so its downcall needs `Linker.Option.firstVariadicArg(2)`; without it the
  arguments go in registers on arm64 macOS where the callee reads the stack, and the call does the
  wrong thing rather than failing.
- **`posix_spawn` returns its errno** instead of setting it, unlike every other call there.
- **Darwin gives a pty no tty structure until a slave is open**, so `TIOCSWINSZ` on the master fails
  with `ENOTTY` unless the parent opens a slave first — and it must close that slave the moment the
  child is spawned, because while the parent holds one the master never sees end of file.
- **A pty discards buffered output when its last slave closes**, so a caller must read before
  reaping. `UnixPty` reaps lazily (`waitpid` with `WNOHANG` from whatever first asks `isAlive`)
  rather than from a thread per session, for that reason as much as for the thread.

The arena is `Arena.ofAuto`: a confined or shared one would have to be closed, and closing it under
a reader blocked in `read(2)` is a use-after-free. `PtyTest` runs a real `/bin/sh` for each claim —
`tty` answers only where there is a controlling terminal, `stty size` proves `TIOCSWINSZ` is the
right constant on this platform, and a `trap … WINCH` proves a resize reaches the child as a signal.

#### The view, and two engines side by side

`terminal/commonMain/.../term/ui/` draws a pane with Compose: one `Canvas`, `drawIntoCanvas` down
to Skia, and no Swing anywhere. Being an ordinary composable is most of the point — the pane it
replaces is heavyweight, and everything awkward about that follows from it.

- **The grid is never laid out.** One advance is measured from `M`, every glyph is placed at
  `column * cellWidth`, and the type system is only ever asked *which* glyph. Drawing is three
  passes — background runs, text runs as one `TextBlob` each, then the rules for underline and
  strikethrough — so the call count follows the runs on a row, not its cells.
- **Repainting is driven by frames, not by output.** The model's generation is compared once per
  frame, so a megabyte of output causes one repaint rather than a thousand: a flood is survived in
  the parser, which is fast, rather than in the renderer, which is not. While a program holds
  synchronized output open the comparison is skipped, so a half-drawn screen is never shown.
- **Skia takes pixels, a font size is quoted in points.** A retina screen is two pixels to the
  point, so handing Skia the point size directly draws a pane at half the size of the one beside
  it — small enough to notice and hard enough to explain, which is exactly what happened. AWT did
  that conversion invisibly for the Swing pane; here `rememberTerminalGlyphs` reads
  `LocalDensity` and multiplies, and `a cell is twice the size on a screen with twice the pixels`
  pins it.
- **Font fallback is ours now.** AWT did it invisibly for the Swing pane; here `TerminalGlyphs`
  asks Skia's font manager per code point and caches it. That is the hidden cost of "emoji and CJK
  work", and it is why `hasGlyph` is checked before every run.
- **Reverse video is resolved in the painter, not the cell**, because it swaps what the colours
  *became*: a reversed cell with a default foreground takes the background's actual colour, and
  only the palette knows what that is.
- `TerminalViewTest` renders the whole thing off screen into `terminal/build/reports/terminal-view.png`,
  and `TerminalPainterTest` paints straight onto a Skia surface for the scrollback and the selection
  (`terminal-scrollback.png`, `terminal-selection.png`) and then **reads the pixels back**. That this
  can exist at all is itself a result — `AppRenderTest` has always had to stand a coloured box in
  for the Swing pane. Sample the *corner* of a cell rather than its middle: the middle is where the
  glyph is, and a sample that lands on the ink reports the text colour whatever the background did.

**A key press with no character of its own is not input.** AWT reports one as `CHAR_UNDEFINED`,
which is U+FFFF, and every bare modifier arrives that way — so a pane that forwards the code point
sends the shell something nothing can draw, and pressing `Shift` puts a box reading FFFF (or a
question mark, depending on the font) into whatever you were typing. That happened. Two guards say
it, deliberately: `handleKey` drops the modifier keys by name, which is the statement a reader
wants, and `KeyEncoder.encodeCharacter` refuses U+FFFF and its noncharacter neighbour, which is the
one that holds whatever a toolkit decides to call the key. `pressing a modifier on its own sends
nothing` drives real key events through a scene, and `pressing a letter still sends it` is there
because a guard that swallows too much is a pane you cannot type in.

The pointer and the wheel follow one rule: **they belong to the program only when it asked for
them, and never while `Shift` is held.** That override is how every terminal lets you select text
inside a full-screen program that has taken the mouse; without it, copying out of `vim` is
impossible. `MouseEncoder` decides *what* to send — the modern `SGR` form when a program turned it
on, the original three-byte form otherwise, which simply cannot express a column past 223 and
reports nothing rather than a wrong number. Reporting more than was asked for is not harmless: a
program in click mode reads motion reports as gibberish keystrokes.

Selection is in document coordinates, not screen ones (`TerminalPosition` counts from the oldest
line of history), because a selection anchored to a screen row slides up a line every time a build
prints something. Copying joins lines that `wrapped` rather than breaking them — a wrapped path
pasted back with a newline in the middle is the classic terminal annoyance, and the flag exists to
avoid it. Copy happens on release rather than on a shortcut: a selection that vanishes when you
reach for the keyboard is no selection.

`OSC` carries the two decisions with consequences beyond drawing. **`OSC 4` repaints the palette of
one pane**, not of every pane — the palette lives on the model, and a query is answered rather than
ignored because a program that asks what colour 4 is and hears nothing decides the terminal has no
colours at all. **`OSC 52` writes to the clipboard and will never read from it**: answering a read
would let anything able to write to a terminal — a `cat` of a file someone sent you — exfiltrate
whatever you last copied. That refusal is a decision with a test (`a program cannot read the
clipboard`), not an unimplemented case.

**Both engines run side by side, and each pane remembers its own.** `TerminalBackends` is a routing
table rather than a global `if`: an engine cannot be changed under a running pane, because its
process is attached to one implementation. The setting therefore decides what the *next* pane opens
on, live panes finish on the engine they started with, and both can be on screen at once — which is
also the best way to compare them. The choice lives in `~/.worktree/terminal` beside `recent` and
`last`, and is made from the sliders button in the Projects header; `FOREST_TERMINAL` overrides it
for one run but is not how a user switches, because an app launched from Finder never sees a
variable — the same reason a packaged build could not find `claude` on its `PATH`. The menu is
filled in from `main.kt` through `ui/TerminalEngines.kt`, the same platform-fills-a-global pattern
as `AgentShortcuts` and `WindowChrome`, so nothing in `:shared` knows that one of the engines is a
Swing widget — and a build with one engine has no such menu at all.

That coexistence has a price, and it is deliberate: while the Swing engine can still be chosen,
`compose.interop.blending`, the `suspended` detach-while-a-modal-is-open and the global AWT key
dispatcher all have to keep working. They are not leftovers.

**The window talks to a terminal only through `TerminalBackend`.** The composable slot `App` already
took (`terminal: @Composable (session, focused, modifier) -> Unit`, filled in by `main.kt`) draws a
pane; the interface carries the rest of the surface — the focus report and the session lifecycle —
and `TerminalSessionManager` is its JediTerm implementation. `main.kt` types the field as the
interface deliberately: that one line is the whole reason a second implementation is a matter of
writing one rather than of unpicking the app. `EmbeddedTerminal` is `internal` for the same reason —
nothing outside this package reaches a particular terminal's own composable.

Four constraints worth remembering:

- Terminal colours come from `getDefaultStyle()`, not from `getDefaultForeground()` /
  `getDefaultBackground()` — those two *derive* from it. JediTerm's own default style is black on
  white, and cells carry it, so overriding only the derived getters leaves the block cursor (painted
  with the cell's foreground) black on a dark background.
- JediTerm draws its own Swing scrollbar, which looks nothing like the rest of the window.
  `ChromelessTerminalWidget` hides it rather than removing it: the widget wires the terminal panel
  to that scrollbar's model, so dropping it would take scrolling and the search highlights with it.
  Scrollback still works through the wheel and the page keys.

- The terminal is a **heavyweight Swing component**, so Compose modals would paint underneath it.
  `main.kt` sets `compose.interop.blending`, and `App` additionally detaches the terminal while a
  dialog is open.
- `DefaultSettingsProvider` is Java, so Kotlin exposes its getters as synthetic properties. Naming a
  field after one (`terminalFont`) makes an override call itself — this caused a real
  `StackOverflowError`. Keep helper names distinct from the getter names.

Dependencies: JediTerm and pty4j come from the JetBrains repository declared in
`settings.gradle.kts`, not Maven Central. **JediTerm is LGPL 3.0** and is linked unmodified.

**Renaming a worktree** is `git worktree move` for the folder and `git branch -m` for the branch,
offered together because the two are normally named after each other. Three things are worth
knowing, and each is pinned by a test:

- Given a destination that already exists, `git worktree move` moves the worktree *inside* it the
  way `mv` does — and reports success. `AppState.renameWorktree` checks first, or a rename silently
  becomes a nested checkout.
- The branch is renamed **first**, from the repository rather than from the worktree, so a failure
  leaves nothing moved. A branch checked out in another working tree renames fine that way and that
  tree's `HEAD` follows.
- Git moves a *dirty* worktree happily and the directory keeps its inode, so a shell running inside
  it follows along. What does not follow is the path each agent pane recorded — `retarget` rewrites
  those, since that path is how a pane's usage is found and how "show worktree in project" gets
  back. Git refuses on the main working tree, so the dialog only offers its branch.

## Releasing

```bash
./gradlew :desktopApp:packageDmg     # → desktopApp/build/compose/binaries/main/dmg/Forest-<v>.dmg
```

Verified on this machine: an 81 MB DMG holding a 142 MB bundle, `LSMinimumSystemVersion` 11.0,
`io.mainactor.forest`, the developer-tools category, the drawn `.icns`, and a trimmed 10-module
runtime that does contain `jdk.unsupported` — JNA needs it, and pty4j needs JNA, so a runtime
without it fails the first time a pane opens rather than at build time. Launched with the
environment Finder actually gives an app (`PATH=/usr/bin:/bin:/usr/sbin:/sbin` and nothing else) it
starts and finds git; `GitLocator` and `FileSystemAccess.findOnPath` both carry fallback directories
for exactly that reason.

### Signing and notarization

A release has to be both. Apple will not notarize an unsigned build, and macOS will not run a
*downloaded* signed build that has not been notarized — `spctl` calls that state
`source=Unnotarized Developer ID`.

```bash
export FOREST_MACOS_SIGNING_IDENTITY="Developer ID Application: Dmitry Tsvetkov (7FCH84EN89)"
./gradlew :desktopApp:stapleDmg      # packages, hardens, signs, submits, waits, staples
```

Done and verified: Apple returned **Accepted**, the ticket is stapled, and a copy of the DMG carrying
the quarantine flag — what a download actually looks like — is `accepted, source=Notarized Developer
ID`, as is the app inside it.

Two steps in that chain exist because the first submission came back **Invalid**, and both are easy
to leave out:

- **`hardenEmbeddedNatives`, which does two repairs rather than one** — the name is narrower than
  the job, and they share a step, since both edit sealed resources and the seals then have to be
  rewritten innermost first.

  The second one is **jlink's licence symlinks**. `legal/java.desktop/LICENSE` and twenty-six like
  it are links to `../java.base/LICENSE`, and **jpackage's DMG step copies the app image by
  following them** — so the image holds twenty-seven regular files where the runtime's seal recorded
  twenty-seven links, and the copy inside it fails `codesign --verify --deep --strict` with "file
  modified" for each. Apple reports that as "The signature of the binary is invalid" against
  `Contents/MacOS/Forest` and `runtime/Contents/MacOS/libjli.dylib`, naming the bundles whose seals
  broke rather than the files that broke them; it cost a submission to find. This is jpackage's copy
  and not the image format, which was established rather than assumed: `cp -R` and `hdiutil create
  -srcfolder` both keep the links, and every JDK 25 on this machine loses them. The script resolves
  them before anything is signed, so the runtime seals real files and jpackage's flattening becomes
  a copy of what is already there. It also re-signs `Contents/runtime` — a bundle with a seal of its
  own, whose cdhash the app's seal records — keeping the signature jpackage gave it: its own
  identifier, read back off the bundle, a hardened runtime, and no entitlements.

  The first one is buried Mach-O. Notarization looks *inside* jars. The Compose plugin signs the native
  libraries it finds there by extension, and pty4j ships an executable with no extension —
  `resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper`, signed by JetBrains with
  `flags=0x0(none)`. It passes `codesign --verify --deep --strict` and Apple rejects it with "The
  executable does not have the hardened runtime enabled". `packaging/harden-embedded-natives.sh`
  re-signs anything Mach-O that is not already hardened, so the next dependency to bury a binary
  does not cost another round trip. It skips `.class` entries, which is most of the speed and also
  sidesteps a fat Mach-O header and a Java class file sharing the `CAFEBABE` magic number.
- **`signDmg`.** The disk image itself needs a signature. Without one the app inside is notarized and
  passes, while the container the user double-clicks reports "no usable signature".

**A release notarizes twice, and the order is the point.** `notarizeApp` submits the bundle,
staples the ticket to it, and only then is the image packaged around the stapled copy — which is
what Apple's own instructions describe. The image gets a ticket of its own at the end, and that
covers the app while it is inside the image; it stops covering it the moment someone drags the app
to /Applications, because a bundle carries only what is stapled to *it*. Staple the bundle after
the image is built and the copy inside the image is the unstapled one, which is why `packageDmg`
depends on `notarizeApp` rather than the other way round. Verified both ways: on 1.2.0, before the
step existed, `stapler validate` on the app inside a stapled image answered "does not have a ticket
stapled to it"; on 1.3.0, the first release to run it, the same command on the same place answered
"The validate action worked!".

Signing is verified working: full Developer ID chain, secure timestamp, `flags=0x10000(runtime)`,
`codesign --verify --deep --strict` clean, and the signed hardened-runtime build starts under a
Finder-like environment. The entitlements the Compose plugin applies by default are the ones this
app needs, and one of them is not optional: **`com.apple.security.cs.disable-library-validation`**.
pty4j extracts its native helper at runtime and loads it, and hardened runtime refuses an unsigned
dylib without that entitlement — so a pane would fail to open in a signed build and nowhere else.
The other two, `allow-jit` and `allow-unsigned-executable-memory`, are what any JVM needs.

Notarization needs an app-specific password. `stapleDmg` takes it from a **keychain profile** rather
than the environment, so it never reaches a command line, a build log or a shell history. One-time:

```bash
xcrun notarytool store-credentials forest-notary \
  --apple-id <your-apple-id> --team-id 7FCH84EN89
```

The plugin's own `notarizeDmg` reads `FOREST_APPLE_ID` / `FOREST_APPLE_PASSWORD` /
`FOREST_APPLE_TEAM_ID` instead — that is the CI path, where the secret comes from a runner's secret
store.

Stapling matters on its own: without the ticket attached, a first launch on a machine with no
network cannot verify the notarization.

An Intel Mac cannot run it: the bundled runtime and Skiko's native library are both arm64. A
universal build needs two runtimes and is not set up.

**Notices ship inside the bundle**, at `Contents/app/resources/THIRD-PARTY-NOTICES.md`, via
`appResourcesRootDir`. Not via `licenseFile` — that turns the DMG into an image with a click-through
agreement that has to be accepted before it will even mount, which also breaks any scripted
`hdiutil attach`. JediTerm is LGPL 3.0, pty4j is EPL 1.0 and JNA is LGPL/Apache dual, so the notices
are an obligation rather than a courtesy; every licence in that file was read from the artifact's own
POM except SLF4J's, which declares none. `PackagingTest` fails if the file goes missing or stops
naming the copyleft ones — regenerate it against the built distribution when dependencies change.

`windows.upgradeUuid` is fixed for the life of the product: MSI decides upgrade-versus-second-copy by
that id.

## Agents a pane can run

A pane used to be a login shell and nothing else. It now runs whatever agent was chosen for it, and
`model/AgentSpec.kt` is the whole vocabulary: a name, a command line, and — for the two CLIs Forest
understands — the command that *continues* the worktree's most recent session.

**Resume needs no session id.** `claude --continue` and `codex resume --last` both mean "the most
recent session in this working directory", which is exactly the worktree the pane runs in (`codex
resume` filters by cwd unless given `--all`). Whether there is anything to resume is asked before
running rather than discovered by a command that fails: `UsageReader.hasSessions` already knows,
because Forest reads both tools' session logs for the usage badge. That check is deliberately
separate from `read` — it must not depend on any of those sessions having spent a token.

`Os.shellRunning` builds `<shell> -l -i -c "<command>; exec '<shell>' -l"`. The login shell is
what gives an agent the user's own `PATH`, aliases and credential helpers; the trailing `exec` is what
keeps the pane alive — and keeps whatever the agent printed on its way out — when it exits or fails
to start. `;` rather than `&&` for the same reason.

**`-i` is not decoration, and dropping it only breaks the packaged build.** A non-interactive login
shell reads `.zshenv`/`.zprofile`/`.zlogin` but never `.zshrc`, which is where a shell's `PATH`
actually lives (`~/.local/bin`, Homebrew, version-manager shims). Started from a terminal the JVM
inherits that `PATH` anyway, so everything works; started from Finder it inherits
`/usr/bin:/bin:/usr/sbin:/sbin` and the pane answers `zsh:1: command not found: claude`.
`Os.loginShellCommand`, which runs the background project commands, carries the same flags for the
same reason. `OsTest` pins both, and runs a real shell under a Finder-like environment against a
command only an rc file puts on the `PATH`.

**And the shell is `Os.userShell`, not `$SHELL`.** The same Finder launch that strips the `PATH` can
leave `SHELL` unset entirely — a DMG-launched build had no `SHELL` while its own copy in
`/Applications` did — and the old `?: "/bin/bash"` fallback then ran bash for an account whose shell
is zsh: different rc files, none of them the `.zshrc` holding the `PATH`, so the pane opened on
`bash: claude: command not found`. `userShell` asks the account database instead — `dscl` on macOS,
the passwd entry elsewhere, which is where `chsh` writes and what Terminal itself reads — and falls
back to `/bin/sh`, the one shell a POSIX system must have, rather than guessing which others are
installed.

`openAgent` stays **synchronous unless a resume probe is actually needed**, which is why a hotkey
still opens a pane instantly and why the existing tests did not all have to learn to `join()`. Only
a resumable agent defers, and it returns the `Job` like every other action here.

Per-project settings live in `~/.worktree/agents.json` — JSON rather than the line-per-entry format
the other two files use, because it is a map of projects to a set and a list of records. A project
nobody has configured offers every built-in whose executable `FileSystemAccess.findOnPath` can find,
so the dialog exists to take something away or add a command, not to switch the feature on.
**Detection is a best effort and never a gate**: a windowed app inherits a minimal `PATH` that misses
Homebrew and version-manager shims, so the dialog lists every built-in whatever it finds and says
"not found on PATH" beside the ones it did not. The agent runs in a login shell, which will find
them anyway.

**A project command can run with no terminal at all.** A custom entry in the same dialog carries a
`background` flag; those appear in the worktree menu as *Run X* rather than *Start X here*, execute
through `ShellRunner` (a login shell, same reasoning as a pane), and say nothing when they succeed.
A failure sets `AppState.commandFailure`, which `App` renders as a modal holding the output — a
command with nothing on screen has no other way to explain itself. Every run, successful or not, is
appended to the Console tab beside the git commands; `recordGitLog` renumbers each entry, which is
what keeps the two sources from colliding on the `seq` the Console list is keyed by.

Background runs are outside `gitLock` — a build that takes a minute must not hold up a refresh — and
several can be in flight in different worktrees at once, which is why `backgroundRuns` is a list and
the status bar shows the first with a count. The refresh afterwards goes through `refresh()` so it
takes the lock like every other one.

## Tasks

The Tasks tab is a per-project list of work **written as prompts**, and its other half is on the
agent wall: a pane's header carries a task button, the picker lists what is still open, and the
chosen task is handed to the running agent as if it had been pasted and submitted. Writing something
down is only worth doing because using it later costs one click.

- **`done` is the whole of the state, and nothing sets it but the person.** Sending a task to an
  agent is not progress: the pane may finish it, fail at it or be closed on it, and none of that is
  visible from here. A tracker that guessed would be wrong in exactly the cases that matter, so it
  records the one thing only the user knows and asks for it once.
- **A task has no title.** Its first non-blank line is the name and the second is the preview
  (`model/Task.kt`) — naming a piece of work is a second job, and nobody does it when the work is
  the point. `isOpen` is "written down and not finished", which is what the wall offers and what
  the badge counts.
- **Finished tasks sink, they do not vanish.** `AppState.order` puts open first and newest within
  each half; a tracker that hides what was done cannot answer "did I do that already". The row is
  dimmed *and* struck through, because colour alone is what a colour-blind reader cannot use and
  also what a selected row's own background takes away.
- **The list is not reordered while you type.** `updateTask` leaves the row where it is even though
  the task just became the newest — a row that climbs to the top between two keystrokes takes the
  caret with it. It sorts into place the next time the project is opened.
- **There is no save button.** `updateTask` writes the file on every keystroke; a list with a dirty
  state is a list people stop using, and the file is a few kilobytes.
- **Ids count up (`taskSeq`), they are not derived from the list.** An id built from `tasks.size`
  hands a new task the id of one still in the list as soon as anything was deleted from the middle,
  and the editor writes through the id — two tasks with one id are edited as one. Pinned by `a task
  added after a deletion does not collide with a surviving one`, which stops the clock to reach the
  case at all.
- Storage is `~/.worktree/tasks.json`, keyed by project path, JSON for the same reason
  `agents.json` is: a prompt is many lines of arbitrary text, which no line-per-entry format holds.
  It sits beside the machine's other preferences rather than in the repository — a working list is
  not something to put in front of everyone who clones it, nor into the diffs and merge conflicts
  that living in a worktree would cost it.
- **`notes.json` is read *behind* `tasks.json`, not instead of it**, and never written to. The tab
  was called Notes for one release and the entries are the same shape, so the migration is only a
  matter of reading the old name — but `tasks.json` appears the moment *any one* project is edited,
  and a store keyed on that file's existence takes every other project's entries off the screen at
  that instant while leaving them on disk. The two are merged per project, the new file winning,
  and the next write of a project folds its entries over. That is also why `save` writes an **empty
  list** rather than skipping it: an emptied project has to stay empty instead of being refilled
  from the old file. `TaskStoreTest` pins both halves, and each fails on its own when reverted.

**Delivery is the backend's business, not `AppState`'s.** `AppState` calls `onSendPrompt(sessionId,
text)`, which `main.kt` binds to `TerminalBackend.sendPrompt`; only the engine knows whether the
program asked for bracketed paste, and without it a multi-line prompt submits its first line and
runs the rest as separate commands. Both engines implement it — the native one through
`KeyEncoder.paste`, JediTerm by wrapping the text itself, which is why `ChromelessTerminalWidget`
subclasses `TerminalPanel` purely to record whether mode 2004 is on. `a prompt reaches the engine
that owns the pane` covers the router.

The header button appears **only while something is open**, and the context menu keeps its entry
either way — a button whose every press could say no more than "nothing on the list" is furniture on
the header of everyone who does not keep one, while the menu is where the feature can still announce
itself. `a pane header sends a task without going through the context menu` drives a real click at
it.

## Usage statistics

Each agent pane's header shows what the agent CLIs have spent in that pane's worktree — tokens and
an estimated cost, across **Claude Code and Codex**. The source is always the session log the tool
writes for *itself*, not the terminal: it is the usage the API actually reported, reading it cannot
disturb a running agent, and it covers every session in the worktree, including ones started from an
ordinary terminal. `usage/UsageReader.kt` carries the alternatives that were rejected
(screen-scraping JediTerm, an `ANTHROPIC_BASE_URL` proxy).

`JsonlTail` is the shared half — both tools append JSONL and never rewrite it, so a poll costs only
the bytes added. What the lines *mean* is per tool, and the two are opposites:

| | Claude Code | Codex |
| --- | --- | --- |
| Where | `~/.claude/projects/<encoded cwd>/<sessionId>.jsonl` | `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` |
| Found by | directory name, corrected by the recorded `cwd` | `cwd` on the file's first line, cached |
| Usage line | `message.usage` on `type: assistant` | `payload.info.total_token_usage` on a `token_count` event |
| Folded by | **summing**, de-duplicated by `requestId` | **replacing** — the value is the session's running total |

Getting that last row backwards is the whole risk. Codex also carries a `last_token_usage` delta on
every event, and summing those looks equivalent — but on this machine one session in four had the
deltas add up to *more* than the same file's total (1,479,976 against 1,404,207), because a retry
counts in the delta and is rolled back out of the total.

Four more things are easy to get wrong, and each has a test:

- **One response is written as several lines**, one per content block, each repeating the identical
  `usage` object. A real transcript had 1050 assistant lines for 596 responses — summing lines
  over-reports by ~1.8x. `TranscriptTail` de-duplicates on `requestId` and keeps that set *across*
  polls, because a response's lines straddle the moment a poll stops.
- **Cache writes are split by lifetime.** `cache_creation.ephemeral_1h_input_tokens` is billed at 2x
  the input rate against 1.25x for the 5-minute one, and Claude Code uses the one-hour cache, so
  collapsing them understates the largest line on the bill.
- **The project directory name is lossy** — every character outside `[A-Za-z0-9-]` becomes `-`, so
  `/` and `-` are indistinguishable and the worktree `feature` prefix-matches its *sibling*
  `feature-two`. `UsageReader.claims` settles it by reading the `cwd` the transcript records.
- **Subagents write their own files** under `<sessionId>/subagents/agent-*.jsonl`. Skipping them
  under-reports every agent that delegates.
- **Codex's `input_tokens` already contains `cached_input_tokens`** — confirmed by arithmetic on
  real files, where `input + output == total`. Billing both would roughly double the input line,
  which on a long session is most of the cost. Anthropic reports the two separately instead.
- **A Codex session's model can change mid-way**, and a cumulative counter cannot be split, so the
  total is attributed to the model in force at the end.

`ModelPricing` holds both vendors' list prices with the date they were taken; nothing on disk
records a price (`additionalModelCostsCache` in `~/.claude.json` is empty), so the figure is always
presented as an estimate. `Pricing` keeps two cache-write rates because the vendors differ:
Anthropic charges 1.25x or 2x the input rate to *write* a cache entry depending on its lifetime,
OpenAI charges nothing to write one and only discounts the read. Fast mode is part of `ModelKey`
because it doubles Opus rates.

Reading is off `gitLock` and off the main thread — the first read of a session is the whole file and
the largest on this machine is 59 MB — and only happens while the agent wall is on screen, driven by
a `LaunchedEffect` there rather than a timer hidden in `AppState`.

`UsageReaderTest` also runs against this machine's real logs, skipping when there are none. The
Claude side copies them to a temp directory first — the live transcript is being appended to by the
session running the test, and a response written between the two passes made them disagree by one —
and cross-checks the response count against an independent text scan. The Codex side takes the
newest rollout, asks it which directory it belongs to, and checks the reader reports at least that
session's own total, so no personal path is hardcoded.

## Tests

`AppRenderTest` (`desktopApp/src/test`) renders the window with `ImageComposeScene` against a fake
`CommandRunner`, asserts it is not a flat fill, and writes four PNGs under
`desktopApp/build/reports/`: `app-render` (the default state), `app-render-many` (40 worktrees, the
filtered list), `app-render-conflicts` (a stopped merge, reached by feeding a conflicted status),
`app-render-agents` (a six-pane wall), `app-render-log` (a commit open in the Log tab, over a
history shaped like a real shared repository — long refs, merge subjects and eight-character
hashes, the size at which the hash column used to wrap onto a second, clipped line),
`app-render-search` (a file's history), `app-render-dialog`, `app-render-tooltip` (a synthesised hover — that one sleeps past the real hover
delay, since `TooltipArea` counts wall-clock time rather than frames), `app-render-splitter`,
`app-render-tasks` (the Tasks tab, two open tasks, one finished, and the editor),
`app-render-agent-task` and `app-render-send-task` (the picker that hands one to an agent).
Two tests drive real pointer press/move/release sequences over the dividers and assert the pane
boundary moved; they are what caught the frozen splitters. A third renders the same state into two
differently sized windows and asserts the dividers land in identical places — the guard against
pane sizing drifting back to fractions. All three locate boundaries by scanning the rendered pixels
for the border colour. Reading those is how layout regressions get caught — the conflict pane's action
bar overflowing its width was found exactly this way. It needs no display, and reading those PNGs is the fastest way to check
a layout change. The integration tests create real repositories in temp directories and skip
themselves when no `git` binary is found.

## Icon

`ui/components/Logo.kt` draws the mark, in the same resolution-independent style as the toolbar
glyphs (`drawIcon`) — fractional coordinates against a single `s` side — but with its own, thinner
stroke ratio, because the glyph ratio is a 92px stroke at 1024.

`./gradlew :desktopApp:generateIcons` renders `desktopApp/icons/forest.{png,ico,icns}` from that one
drawing. The generator is `desktopApp/src/test/.../tools/GenerateIcons.kt` — in the test source set
because it is a tool, and run through a `JavaExec` task on the test runtime classpath. Two things
about it are not obvious:

- The `.ico` container is written by hand. Neither ImageMagick nor Pillow is present, and neither is
  a reasonable build requirement; the format is only a header plus PNG payloads.
- The `.icns` step shells out to `iconutil`, which is macOS-only, so it *skips* elsewhere rather
  than failing — the file is committed, and a build on Linux still packages the right icon.

`IconAssetsTest` checks the committed files exist, carry the right magic bytes, that the `.ico`
holds all six sizes as valid PNGs, and that `forest.png` still matches what the drawing produces —
the last one is what catches an icon left stale after the mark was edited.

## Conventions

- Dependencies go through `gradle/libs.versions.toml`; the exception is `compose.desktop.currentOs`,
  which comes from the Compose plugin DSL.
- Desktop-only Compose APIs (`VerticalScrollbar`, `LocalScrollbarStyle`) are used from `commonMain`.
  That compiles because `jvm()` is the only target; adding a second target would break it and the UI
  would need to move to `jvmMain`.
