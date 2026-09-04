# Forest

A desktop manager for git worktrees, styled after the Android Studio / IntelliJ **New UI** dark
theme. Kotlin Multiplatform + Compose Multiplatform, targeting macOS, Linux and Windows.

The window has two modes, switched from the top-left.

**Project** — three vertical panes over a terminal tool window:

| Pane | What it does |
| --- | --- |
| **Projects** | Repositories you have opened, plus Open / New / Clone. Right-click for Show in Finder / Copy path |
| **Worktrees** | Every working tree, newest activity first — branch, age, dirty count, ahead/behind, lock state. Create, remove, lock, prune; right-click to start or jump to an agent. |
| **Changes** | The selected worktree's diff, as `Changes`, `Conflicts`, `Log`, `Search` and `Console` tabs |
| **Terminal** | A real shell in the selected worktree, one tab per worktree |

`Log` lists the worktree's history; clicking a commit shows the files it touched beside their
patch. Merges included — the diff is taken against the first parent, which is the only way a merge
shows anything at all.

`Search` finds a tracked file by name and then answers the follow-up question: every commit that
touched it, and that commit's patch for that file. The history follows renames, so it reaches back
past the commit that moved the file. `git ls-files` runs once per worktree and the filtering happens
in memory, so typing never waits on a process.

Panes are sized absolutely and dragged with the dividers: making the window bigger grows the changes
pane, and leaves the side panels and the terminal the size you set them.

**Agents** — the whole window becomes a wall of terminals arranged as a split tree, like a
multiplexer. Several agents in one worktree is the normal case, not the exception: they share a
checkout on purpose, so panes are tiled rather than tabbed and are numbered per worktree
(`main · 1`, `main · 2`, …).

Click anywhere in a pane — including inside its terminal — to focus it; the focused pane is the one
receiving keystrokes, marked with an accent border. Split it right or down from the toolbar, the
pane's own header, its context menu, or the keyboard. Drag any divider to resize. Double-click a header to zoom that pane to the whole wall
and back. Switching to Project mode leaves every agent running.

| | macOS | Windows / Linux |
| --- | --- | --- |
| Split right | `⌘D` | `Ctrl+Shift+D` |
| Split down | `⌘⇧D` | `Ctrl+Shift+E` |
| New agent | `⌘T` | `Ctrl+Shift+T` |
| Close pane | `⌘W` | `Ctrl+Shift+W` |
| Zoom pane | `⌘Enter` | `Ctrl+Shift+Enter` |

The bindings differ for a concrete reason: on Linux and Windows a bare `Ctrl` chord belongs to the
shell — `Ctrl+D` is end-of-input — so the wall uses `Ctrl+Shift`, the convention terminal emulators
already follow there. They are only live in Agents mode.

Each pane can send you the other way too: its first header button (and its context menu) opens that
agent's worktree in the Project view, loading its repository first when the pane belongs to one that
is not currently open.

Nothing starts a shell on its own: opening a repository does not open a terminal, and entering the
agent wall does not start an agent. A worktree's context menu offers **Start agent here**, or
**Focus agent** when one is already running in it.

**Every** way of adding a pane — the split buttons, the shortcuts, the `+` — asks which repository
and which worktree it runs in, because agents are commonly spread across several repositories and a
split is how one on a *different* worktree gets started. The picker opens on the worktree the pane
being split is already using, so staying put is one confirmation while another repository is one
selection away. The wall is therefore not tied to the project the rest of the window has open. The
picker lists worktrees in the same order as the Worktrees pane — main pinned, then by last
activity — and shows the same ages, so the two never disagree about which one you were last in.

The icon is drawn in code, not shipped as artwork: `ui/components/Logo.kt` paints a git branch graph
shaped like a tree, and `./gradlew :desktopApp:generateIcons` renders it out to
`desktopApp/icons/forest.{png,ico,icns}` — one committed file per platform, all from the same
drawing, so there is nothing to keep in step by hand.

## Running

```bash
./gradlew :desktopApp:hotRun --auto   # with Compose hot reload
./gradlew :desktopApp:run             # plain run
./gradlew :desktopApp:packageDistributionForCurrentOS   # .dmg / .msi / .deb
```

Requires the `git` binary on the machine — the app drives it directly rather than reimplementing
git on the JVM, so worktrees, hooks, credential helpers, merge drivers and conflict handling
behave exactly as they do on your own command line. When launched as a bundled app (where the
GUI inherits a minimal `PATH`), it also looks in the usual install locations.

## What it does

**Worktrees.** Create one from a new branch, an existing branch or a detached ref, with the folder
name defaulting to the branch. Branches already checked out somewhere else are filtered out of the
picker, because git will refuse them. Remove (with a forced variant for dirty trees), lock/unlock,
and prune, and switch an existing worktree to another branch — remote branches become local
tracking branches rather than a detached HEAD, and branches another worktree holds are left out of
the picker because git refuses them. The repository's own working tree stays pinned at the top; the
rest are ordered by last activity — the newer of the branch's last commit and your newest
uncommitted edit — so whatever you were working on leads them. The row shows the age and its tooltip
says which of the two it came from. Past eight worktrees the pane grows a filter box, and the sweep that keeps every row's
dirty/ahead-behind counts current runs in the background, so a repository with dozens of worktrees
stays responsive.

**Changes.** Two modes: the uncommitted working tree, and everything the worktree's branch carries
over a base ref — the view that answers "what is this worktree actually for?". Stage, unstage,
discard, commit (with amend and `--all`).

**Remote and integration.** Fetch, pull (merge or rebase, chosen explicitly), push — publishing the
branch with `-u` when it has no upstream. Merge and rebase from a branch picker.

**Conflicts.** When a merge or rebase stops, a banner offers Continue / Skip / Abort, and each
conflicted file opens as a list of cards — one per `<<<<<<< / ======= / >>>>>>>` region — with
*ours*, *theirs* and (in diff3 style) the common ancestor side by side. Choose per region or for
the whole file; applying writes the merged text back and stages it. `git checkout --ours/--theirs`
is one click away for whole-file resolutions.

**Terminal.** A login shell on a real pty, rendered by [JediTerm], the emulator the IntelliJ
terminal uses. It opens in the selected worktree, keeps running while its tab is hidden, and gets
your own prompt, aliases and pagers. Shells are torn down when you close the tab, remove the
worktree, or quit.

**Console.** Every git command the app runs, with its output and exit code. Nothing is hidden
behind a button label — and hovering a toolbar button shows both what it does and the exact git
command it will run.

**Project list.** Opening a project never reorders the list; the app remembers which one you had
open and restores it on the next launch instead.

## Layout

```
shared/
  commonMain/   models, git parsers, the Git facade, AppState, the whole Compose UI
  jvmMain/      process execution, filesystem, folder chooser
desktopApp/     window, JediTerm terminal, wiring
```

`shared/commonMain` holds everything that does not touch the JVM directly; the platform pieces sit
behind `CommandRunner`, `FileSystemAccess` and `DirectoryChooser`, which is what makes the git
layer testable without a repository and `AppState` testable without a window.

## Tests

```bash
./gradlew :shared:jvmTest      # parsers, Git against a real repo, AppState end-to-end
./gradlew :desktopApp:test     # renders the whole window off-screen
./gradlew build                # everything
```

`GitParsersTest` covers git's machine-readable formats (`--porcelain=v2` under `-z`, worktree
records, unified diffs, both conflict styles). `GitIntegrationTest` and `AppStateIntegrationTest`
run against real repositories in temp directories, including a real merge conflict resolved through
the same code path the UI uses. `RefreshCostTest` builds a 40-worktree repository and fails if a
refresh starts spawning child processes in proportion to the worktree count. `AppRenderTest` renders
the window with `ImageComposeScene` into `desktopApp/build/reports/` — no display needed, so it runs
in CI.

## Theming

The palette is not eyeballed from screenshots: chrome colours and metrics come from JetBrains'
`expUI_dark.theme.json` (the `Gray1…Gray14` / `Blue1…Blue13` ramps, `Component.arc`,
`EditorTabs.underlineHeight`), and the VCS/diff semantics come from the editor colour scheme
Android Studio inherits (`FILESTATUS_*`, `DIFF_INSERTED`, `CONSOLE_*` for the terminal). Swapping
`WorktreeColors` in `ui/theme/Theme.kt` re-skins the whole app.

## Licensing note

The embedded terminal links [JediTerm] and [pty4j]. JediTerm is **LGPL 3.0**; it is used unmodified
as a library, which is what that licence allows for an otherwise separately-licensed application.
Take a look before shipping this commercially.

[JediTerm]: https://github.com/JetBrains/jediterm
[pty4j]: https://github.com/JetBrains/pty4j
