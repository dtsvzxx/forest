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
./gradlew :desktopApp:test            # off-screen render test
./gradlew :shared:jvmTest --tests "io.mainactor.worktree.git.DiffParserTest"          # one class
./gradlew :shared:jvmTest --tests "*GitIntegrationTest.lists the main worktree*"      # one test
./gradlew :desktopApp:packageDistributionForCurrentOS                                 # installer
```

No lint/format task is configured; `kotlin.code.style=official`. Gradle 9.1 with configuration
cache **and** build cache on, so build-script changes must stay configuration-cache compatible. JVM
toolchain pinned to Azul 21 via `gradle/gradle-daemon-jvm.properties`.

## Architecture

Three layers, and the seam between them is what keeps the app testable:

```
desktopApp/          Window, JediTerm terminal, wiring (main.kt)
shared/jvmMain/      ProcessCommandRunner, JvmFileSystemAccess, SwingDirectoryChooser, GitLocator, Os
shared/commonMain/   models · GitParsers · Git · AppState · the entire Compose UI
```

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

`RefreshCostTest` builds a 40-worktree repository and fails if either budget regresses. Before these
constraints existed, one refresh spawned 249 child processes and took ~1.5 s.

`GitParsers` holds the pure parsing of git's machine-readable formats and carries the bulk of the
unit tests. Two things there are easy to break:

- `parseStatus` reads `--porcelain=v2 -z`. Rename records (`2`) consume a **second** NUL-delimited
  token for the original path; missing that shifts every following record.
- `parseConflicts` handles both `merge` and `diff3` conflict styles. "No base section" and "empty
  base section" are different states — the `sawBase` flag, not `section >= 1`, decides.

Field separators: `GitParsers.FS` (`U+001F`, used in `--format` strings) and `GitParsers.NUL`.
Never put these characters in a source file literally.

**Persisted state** lives in `~/.worktree/`: `recent` is the project list in the order the user
added them, and `last` is the project to reopen on startup. The list is deliberately never reordered
by use — entries that move under the pointer are harder to navigate — so "most recent" is tracked in
its own file rather than encoded in the ordering.

### State

`AppState` is a plain class of `mutableStateOf` properties plus the actions the UI triggers. All git
work is serialised through a `Mutex` — concurrent commands race on `index.lock`. Every public action
returns the `Job`, which is what makes `AppStateIntegrationTest` able to `.join()` on it.

**Paths must be compared with `samePath`/`fs.canonicalPath`.** git always reports canonical worktree
paths; a path from a dialog or from `File(...)` may not be one (on macOS `/var/…` vs `/private/var/…`),
and a naive `==` silently selects the wrong worktree.

### UI

`WorktreeTheme` provides `WorktreeColors` through `LocalWorktreeColors` — Material3's scheme covers
only a fraction of what an IDE-style UI needs, so most colours come from there. Icons are drawn on a
`Canvas` (`ui/components/Icons.kt`) rather than pulled from an icon library. Dialogs are in-window
modals (`ui/dialogs/Dialogs.kt`), not separate windows.

Every `ToolButton` carries a `tooltip`, and the git-driven ones a `detail` holding the exact command
they run — at 14dp an icon does not say what `git worktree prune` will do, and the Console tab's
"nothing is hidden" promise is worth applying to the buttons too. `Tooltip` wraps Compose Desktop's
`TooltipArea`; `AppRenderTest` drives a real hover to prove one actually appears.

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

### Modes

`AppState.mode` picks between the three-pane project view and `AgentsPane`, a wall of terminals for
running several agents in one worktree. Its layout is a binary split tree (`model/PaneLayout.kt`) —
a tree rather than an auto-arranged grid because the operation users reach for is "split *this*
pane", which only means anything relative to an existing one. The tree functions are pure and carry
their own tests; `AppState` holds the root and derives `agents` from it. Both use the same `terminal` slot and the same
`TerminalSessionManager`, so leaving the wall — or the mode — never stops a shell; only
`closeAgent`, removing the worktree, or closing the project do, all of them through
`onTerminalDisposed`.

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

Navigation runs both ways: a worktree's menu starts or focuses an agent
(`startAgentFor`/`focusAgentFor`), and a pane's `showWorktreeInProject` goes back, opening the
pane's own repository first when it is not the loaded one — which is why `openProjectAt` takes a
`select` argument rather than always landing on the main worktree.

`AppState.worktreesOf` orders the picker through the same `withActivity` the pane uses, including
the status sweep that dates uncommitted work, so the two lists cannot disagree. For the open project
it reuses `worktreeStatuses`; for any other it sweeps that repository, outside `gitLock` so browsing
never blocks the window.

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

## Tests

`AppRenderTest` (`desktopApp/src/test`) renders the window with `ImageComposeScene` against a fake
`CommandRunner`, asserts it is not a flat fill, and writes four PNGs under
`desktopApp/build/reports/`: `app-render` (the default state), `app-render-many` (40 worktrees, the
filtered list), `app-render-conflicts` (a stopped merge, reached by feeding a conflicted status),
`app-render-agents` (a six-pane wall), `app-render-dialog`, `app-render-tooltip` (a synthesised hover — that one sleeps past the real hover
delay, since `TooltipArea` counts wall-clock time rather than frames) and `app-render-splitter`.
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
