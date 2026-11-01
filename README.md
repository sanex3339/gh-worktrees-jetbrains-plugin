# Worktree Tabs — JetBrains plugin

A tab for every git worktree of your project, and one click switches the whole IDE to it **in the same
window, inside the same project**: code, project tree, search, Git, editor tabs, run configurations and
new terminals follow. Nothing is reopened, so terminals, run and debug sessions and AI agents that are
already running keep going.

Other worktree tools for JetBrains IDEs (JetBrains' own included) open a worktree as a separate project.
Worktree Tabs moves the open project's content root, Git mapping and project folder to the worktree you
click instead. Switching to a worktree that was indexed before takes about a second (measured on a
20,000-file repository); the first switch to a worktree indexes it once, like opening it as a project would.

## Requirements

- WebStorm or another IntelliJ-based IDE, 2026.2+
- A project with one module and one content root inside a single Git repository, as WebStorm creates
  for an opened folder. Gradle, Maven and other build-tool projects, and projects with several Git
  repositories, are not supported: the tabs show, greyed out, with the reason in their tooltip.

## Features

- **Worktree tabs** under the main toolbar, styled like macOS window tabs: the main checkout first, then
  the other worktrees in the order they were created. The strip only shows once the repository has a
  worktree besides the main checkout.
  - A **blue name** means uncommitted changes; `↑2 ↓1` counts commits against the upstream; a lock marks a
    locked worktree; an *italic* name means the worktree hasn't been indexed yet.
  - Hover for the path, the branch, how many files are uncommitted and when they were last edited, commits
    ahead and behind, and the lock reason.
  - A soft highlight sweeps across the tab you are switching to until the switch is done.
  - Too many to fit: the row scrolls with the mouse wheel or trackpad and keeps the active tab in view, or
    right-click the strip ▸ **Show Worktree Tabs in Multiple Rows**. The `⌄` button lists every worktree
    with type-to-filter.
- **Each worktree keeps its own** editor tabs (with splits and caret positions), bookmarks, breakpoints and
  changelists (with their commit message drafts). The first visit to a worktree carries the current editor
  tabs, bookmarks and breakpoints over to the same files there.
- **Follows the switch:** run configurations (paths inside the old worktree point at the new one), new
  terminals, the npm scripts list, and the TypeScript, Vue and Angular language services (restarted, so a
  worktree's own `node_modules/typescript` is picked up).
- **Create, lock, unlock, remove:** `+` on the strip (or **Git ▸ Create Worktree…**) creates a worktree on a
  new or existing branch and switches to it. Right-click a tab to switch, lock, unlock or remove it; removing
  the active worktree switches to the main checkout first, and the branch stays.
- **Git ▸ Switch Worktree…** opens the worktree list from anywhere.
- Worktrees nested inside the active one (for example `.claude/worktrees/`) are excluded from it, so their
  files aren't indexed twice.

## Good to know

- The active worktree is saved with the project: while a worktree other than the main checkout is active,
  `.idea/<module>.iml` and `.idea/vcs.xml` point at it, so the IDE comes back on that worktree after a
  restart. If your repository commits those files, they show as modified meanwhile.
- Statuses refresh on Git changes, when the IDE gets focus, and every minute while it has it; `git status`
  runs with `GIT_OPTIONAL_LOCKS=0`, so it never takes `index.lock` from an agent working in a worktree.
- The swap relies on a few internal IDE APIs (moving the workspace model's project root, restoring an editor
  layout). They are kept in one place (`PlatformInternals.kt`) and fall back when they change, but a future IDE
  version may need a plugin update.

## Build & run

```bash
./gradlew test                       # unit tests
./gradlew runIde                     # sandbox IDE with the plugin
./gradlew buildPlugin                # build/distributions/*.zip → Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk
./gradlew verifyPlugin               # binary compatibility with JetBrains' recommended IDE releases
./scripts/sign-plugin.sh             # signed zip (see below)
```

Add `-PideLocalPath=/path/to/WebStorm.app` to use an installed IDE instead of downloading one,
`-PrunIdeProject=/path/to/repo` to open a project in the sandbox, and `-PrunIdeQuiet=true` to skip the
sandbox's first-run consent and project trust dialogs.

### Signing

`./scripts/sign-plugin.sh` (or the **Sign Plugin** run configuration) builds the plugin, signs it with the
key in `~/.jetbrains-signing` (`chain.crt`, `private.pem`) and verifies the signature. It asks for the key's
pass phrase unless `PRIVATE_KEY_PASSWORD` is set, and writes
`build/distributions/worktree-tabs-<version>-signed.zip`. CI can pass the key as the `CERTIFICATE_CHAIN`,
`PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` environment variables instead.

### End-to-end harness

`e2e/` is a dev-only plugin that runs next to Worktree Tabs in a sandbox WebStorm, drives real switches
between two worktrees and writes a pass/fail report: content root, Git, `guessProjectDir()`, editor tabs,
carets, bookmarks, breakpoints, changelists, run configurations, the terminal folder, creating, locking and
removing a worktree.

```bash
./gradlew buildPlugin
cd e2e && ./gradlew runIde -Pmain=/path/to/main-checkout -Pworktree=/path/to/linked-worktree -Preport=/tmp/report.md
```

The main checkout and the worktree each need `wtspike/use.ts` and `wtspike/marker.ts`; the harness edits the
main checkout's `README.md` and restores it on the next run. Its window minimizes itself and closes when done.

### Layout

- `src/` — the plugin
- `e2e/` — the end-to-end harness
- `spike/` — the throwaway experiment that measured an in-place switch before the plugin was built
  (`spike/RESULTS.md`)
- `docs/superpowers/specs/` — the design

## License

[MIT](LICENSE)
