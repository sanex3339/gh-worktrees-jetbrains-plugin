# Worktree Tabs: design

Date: 2026-10-12. Status: parts 1–2 agreed in chat; parts 3–4 written straight into this spec at the user's request
("start prototyping"), open for review.

## Goal

A JetBrains IDE plugin (WebStorm first, any IntelliJ-based IDE from build 262) that shows a tab for the main checkout
and each git worktree of the project's repository. Clicking a tab switches the whole IDE to that worktree **in the
same window, inside the same project**: code, project tree, search, Git, editor tabs, run configurations and new
terminals follow. Already-running terminals, run and debug sessions keep running (agents are not interrupted).

Success: switching to a worktree that was indexed before takes about 1–2 s until the IDE is fully ready (spike:
0.9–1.7 s on a 20k-file repo, no re-indexing), and afterwards everything the user touches points at the selected
worktree. After an IDE restart the project comes back on the last active worktree.

Name: **Worktree Tabs**, plugin id `com.github.sanex3339.worktreetabs`, package `com.github.sanex3339.worktreetabs`.
Build setup like `gh-stack-jetbrains-plugin` (Kotlin, IntelliJ Platform Gradle Plugin 2.x, since-build 262, Plugin
Verifier, Marketplace publishing).

Background: `spike/README.md` and `spike/RESULTS.md` (measurements, pitfalls). Every other worktree tool for
JetBrains IDEs, JetBrains' own included, opens a worktree as a separate project; this plugin's point is the in-place
switch.

## Decisions

| Topic | Decision |
|---|---|
| Mechanism | Swap the single module's content root in place (approach 1). Rejected: one module per worktree with unloading (same scan cost, saves the project on every switch, experimental API, known excluded-folder bug), and one window per worktree. |
| Tab placement | Full-width strip under the main toolbar (`com.intellij.ideRootPaneNorth`). |
| Editor tabs | Each worktree remembers its own tabs, splits and caret positions; the first visit carries the current tabs over to the same files in the new worktree. |
| First visit | Switch immediately; never-indexed worktrees carry a marker and a tooltip; the IDE's own indexing progress shows. |
| Tab actions | "+" creates a worktree; right-click removes, locks or unlocks it; status badges on tabs. Not in v1: open in new window, reveal, copy path. |
| Also follows a switch | Run configurations, terminal start folder, breakpoints and bookmarks (per worktree), changelists (per worktree). |
| Restart | The last active worktree is remembered (the IDE persists the content root and VCS mapping; `.idea/vcs.xml` and the module `.iml` of the folder the project was opened from point at it while it is active). |

## Part 1: architecture

| Unit | Responsibility | Built on |
|---|---|---|
| `WorktreeRegistry` (project service) | Worktrees of the repository and the active one, as a `StateFlow` the UI observes. Refreshes on Git repository changes, VCS mapping and root changes and when the IDE frame gains focus; idle once the project starts closing (asking git4idea for repositories then leaves a half-built repository behind). | `git worktree list --porcelain` through git4idea's command API (its worktree holder has no public change event) |
| `WorktreeStatusService` | Badges per worktree: branch (or short hash when detached), uncommitted changes, commits ahead/behind upstream, not indexed yet. Inactive worktrees: `git status --porcelain=v2 --branch` in the background; the active one from `ChangeListManager`. | `GitCommand.STATUS` via git4idea handlers |
| `WorktreeSwitcher` (project service) | Runs a switch as an ordered list of steps, one at a time; a click during a switch replaces the queued target. Reports progress and failures. | the steps below |
| Core steps | Save documents, refresh the target, swap the content root, move the Git mapping, move the project root once the old root is released. | spike |
| State steps (`before` / `after` the swap) | Editors, bookmarks and breakpoints, changelists, run configurations, terminal start folder, npm scripts list, TypeScript service restart. | per-worktree snapshots |
| `WorktreeStateStore` | Per-worktree snapshots in the project's workspace file; app-wide set of worktree paths that were indexed. | `PersistentStateComponent` |
| `WorktreeTabsStrip` | The tab row: tabs, badges, context menu, "+". | `IdeRootPaneNorthExtension` |
| `WorktreeCommands` | Create, remove, lock and unlock worktrees with small dialogs of our own. | `GitCommand.WORKTREE` via git4idea handlers |
| `PlatformInternals.kt` | The only file touching internal API (project root entity, editor layout serialization). Each call fails safe: logged, the step degrades. | — |

JavaScript-specific steps (TypeScript restart, npm scripts list) live in an optional descriptor loaded with the
JavaScript plugin; the terminal step in one loaded with the terminal plugin.

Switching is enabled only when the project has exactly one module with exactly one content root, that root is a
worktree of exactly one Git repository mapped in the project, and no external build system (Gradle, Maven) owns the
module. Otherwise the strip lists the worktrees disabled, with the reason in the tooltip.

## Part 2: the switch pipeline (A → B)

1. **Checks**: switching is supported, B exists on disk, no other switch runs (clicks meanwhile keep only the newest
   target).
2. **Capture A**, stored under A's path: editor layout (tabs, splits, pinned, selected, caret and scroll); bookmarks
   and breakpoints in A's files, removed from the IDE's lists into the snapshot; changelists (file → list name, each
   list's comment, the active list). Then save all documents and close all editors.
3. **Swap** (spike-measured):
   1. Refresh B in the VFS (synchronous, recursive; ≈ 0.5 s for 20k files).
   2. Content root A → B, excluded folders and patterns carried over. Worktrees nested inside the new root (for example
      `.claude/worktrees/` in the main checkout) are added as excluded folders; exclusions the plugin added for the
      old root are dropped.
   3. Explicit Git mapping to B; mappings outside A are kept. (The default `<Project>` mapping does not follow.)
   4. Wait (≤ 10 s) until no workspace entity except the project root references A (WebStorm's `PackageJsonEntity`
      lags ≈ 0.1–0.16 s), then move the `ProjectRootEntity` A → B. Without the wait, `BaseProjectDirectories` keeps A
      for the session and `guessProjectDir()` returns A.
4. **Restore B**:
   - Run configurations: serialize each, rewrite paths under A to B, read back. Skip configurations stored in
     arbitrary `.run.xml` files (B's own load by themselves; editing them would rewrite the branch's files).
   - Terminal: new terminals start in B (`ShellExecOptionsCustomizer`); a user-set start folder under A moves to B.
     Running terminals are untouched.
   - npm scripts tool window: package.json files under A replaced by the same relative paths under B.
   - TypeScript: restart the TypeScript, Vue and Angular services (B may resolve a different TypeScript).
   - Editors, bookmarks, breakpoints: B's snapshot; on B's first visit, A's snapshot remapped to B (files missing in B
     dropped).
   - Changelists: after `ChangeListManager` has refreshed, recreate B's lists, move B's files back into them, restore
     comments and the active list. First visit: default list only.
5. **Bookkeeping**: when indexing has settled, record B as indexed (its tab loses the marker).

Failures: steps 3.2–3.4 are all-or-nothing (on failure swap back to A, restore A, show an error). Steps 2 and 4 are
best-effort (log, then one notification naming what could not be restored). A worktree deleted outside the IDE loses
its tab; if it was active the plugin switches to the main checkout first. Running processes are never touched.

## Part 3: tab strip and worktree management

- **Strip**: one row under the main toolbar in every project window of a Git project, height of an editor tab row,
  colors and selection underline from the editor tabs theme. Hidden with View | Appearance | Worktree Tabs (also
  in Settings).
- **Tabs**: the main checkout first, then the other worktrees ordered by label. Label: branch name, or folder name and
  short hash when detached. The active tab is selected. Badges: a dot for uncommitted changes, `↑n ↓m` against
  upstream, a lock icon, and a dimmed label for never-indexed worktrees. Tooltip: path, branch, status, and for
  never-indexed worktrees a note that the first switch indexes it. Prunable worktrees (folder missing) are not shown.
- **Behavior**: click switches; the target tab shows a spinner until the switch completes.
- **Many worktrees**: by default one row that scrolls sideways (mouse wheel or trackpad, no visible scrollbar; the
  active tab is kept in view); with Show Worktree Tabs in Multiple Rows (strip context menu) the tabs wrap into as
  many rows as needed. A chevron at the end of the strip lists every worktree with type-to-filter.
- **"+"**: New Worktree dialog: new branch name (or an existing local branch), base (current HEAD by default),
  location (default: a sibling folder `<repo>.worktrees/<branch>`), "Switch to it" (on). Runs `git worktree add`, then
  switches.
- **Context menu**: Switch, Lock / Unlock, Remove Worktree… (confirmation; refused when it has uncommitted changes
  unless the user chooses Force; if it is active, switch to the main checkout first). The main checkout has no
  Remove or Lock.
- **Actions** (Git | Worktree Tabs and Find Action): Switch Worktree… (popup list), Next / Previous Worktree, New
  Worktree…, Show Worktree Tabs (toggle).

## Part 4: limits and testing

Not supported in v1: projects with several Git repositories or several modules or content roots; Gradle, Maven and
other external-system projects (strip disabled with the reason); remote development split mode.

API risk: internal API is limited to `PlatformInternals.kt` (`registerProjectRoot` / `unregisterProjectRoot`, editor
layout serialization), each with a fallback. Experimental API: `TypeScriptServiceRestarter`, `ShellExecOptionsCustomizer`.

Testing:
- Unit tests (JUnit 5, no IDE) for pure logic: path rebasing, nested worktree exclusions, run configuration XML
  rewrite, editor and changelist snapshot remapping, tab labels and ordering, `git status` parsing, switch queueing.
- Platform integration tests (IntelliJ test framework) on a temporary repository with worktrees: content root, VCS
  mapping, project root and `guessProjectDir()` after a switch, rollback on failure.
- The spike harness (`spike/`) for timing on a large repository.
- Manual checks in a sandbox WebStorm on a real TypeScript project with `node_modules`.

## Milestones

1. **Prototype**: plugin scaffold, `WorktreeRegistry`, core switch (steps 1, 3), simple editor carry-over, tab strip
   with click-to-switch and the active tab. Runs in a sandbox IDE.
2. **State**: per-worktree editors, bookmarks and breakpoints, changelists, run configurations, terminal, npm scripts,
   TypeScript restart.
3. **Management and status**: "+", remove, lock and unlock, badges, not-indexed marker.
4. **Release**: settings and actions, platform tests, Plugin Verifier clean except the documented internal calls,
   README and Marketplace description.
