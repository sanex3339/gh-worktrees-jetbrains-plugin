# Worktree swap spike (throwaway)

Measures an in-place worktree swap inside one open WebStorm project: the module's content root, the workspace
model's project root and the Git mapping move from one worktree to another, without reopening the project.
Editors, run configurations, the terminal and other state are not handled here.

## Run

```
./gradlew runIde -PspikeProject=/path/to/main-checkout -PspikeTarget=/path/to/other-worktree \
                 -PspikeReport=/path/to/report.md -PspikeAutorun=true -PspikeExit=true
```

A sandbox WebStorm (`~/Applications/WebStorm.app` by default, override with `-PideLocalPath`) opens the
main checkout, runs phases P0–P4 and exits. Without `-PspikeAutorun`, run *Worktree Spike: Run Experiment* or
*Worktree Spike: Swap to Worktree…* from Find Action.

The TypeScript probe needs `wtspike/marker.ts` (`export const wtSpikeValue: number = 1;` in main, `boolean` in the
worktree) and `wtspike/use.ts` (`import { wtSpikeValue } from "./marker"; export const s: string = wtSpikeValue;`).
P4 writes files into the main checkout, so it runs only when that `wtspike/` folder exists.

## Findings (2026-10-12, WebStorm 2026.2.1, VS Code repo ≈ 20k files, no `node_modules`)

See `RESULTS.md` for the full report of the last run.

| | Measured |
|---|---|
| Swap to a worktree indexed before | 0.6–0.9 s of swap work (≈ 0.5–0.7 s of it the synchronous VFS refresh of the target), Git repository ready 0.1–0.2 s after re-mapping, IDE settled after 0.9–1.7 s |
| Re-indexing on such a swap | none: one PARTIAL scan of ≈ 20k files (0.4–0.7 s), 0 files indexed, 0.13–0.23 s of dumb mode |
| Files changed on disk while the worktree was inactive | exactly those re-indexed (2 of 2), found in the file name and word indexes |
| First activation of a worktree | full indexing: ≈ 19.4k files in ≈ 22–25 s, settled after ≈ 25–28 s |
| IDE restart with persisted indexes | settled in under 1 s (2–5 files scanned) |
| Full rescans triggered by bundled plugins | none observed (no FULL scan after any swap) |
| TypeScript | right worktree's types every time, with or without `TypeScriptServiceRestarter` (weak probe: inferred project, bundled TS) |

Pitfalls found:

- The default `<Project>` VCS mapping does not follow: it keeps the old root and adds the new one. Set an explicit
  mapping to the target.
- `guessProjectDir()` comes from `BaseProjectDirectories`, which also includes the workspace model's
  `ProjectRootEntity` (internal API: `registerProjectRoot` / `unregisterProjectRoot`). It must move too, and only
  after WebStorm's `PackageJsonEntity` stops referencing the old root (≈ 0.1–0.16 s after the content-root change).
  Otherwise the old root stays a base directory for the rest of the session, and `guessProjectDir()` keeps
  returning it.
- Every swap flags ≈ 486 files "for indexing" that end up indexing nothing (≈ 15 ms); cause unknown.
