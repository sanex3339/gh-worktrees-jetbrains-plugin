# Worktree swap spike

WebStorm 2026.2.1 (WS-262.9437.145), 2026-10-12T11:48:41.318276

Main checkout: `main`; worktree: `wt-d`

| Phase | Swap ms | Git repo ready ms | Settled ms | Scans | Files scanned | Sent to indexing | Dumb-indexed | Dumb ms | TypeScript |
|---|---|---|---|---|---|---|---|---|---|
| P0 project open on main | – | – | 737 | PARTIAL_ON_PROJECT_OPEN×1 | 2 | 0 | 0 | 0 | correct |
| P1 main → worktree (first activation) | 121 | 27 | 27749 | PARTIAL×1 | 20349 | 19917 | 19448 | 1517 | correct / after restart: correct |
| P2 worktree → main (indexed before) | 904 | 184 | 1690 | PARTIAL×1 | 20354 | 486 | 1 | 232 | correct |
| P3 main → worktree (indexed before) | 632 | 117 | 1030 | PARTIAL×1 | 20349 | 486 | 0 | 169 | correct |
| P4 worktree → main after editing main on disk while inactive | 667 | 162 | 944 | PARTIAL×1 | 20355 | 488 | 2 | 125 | correct |

## P0 project opened on the main checkout

- VCS mappings: main (Git)
- Git repositories: main [main]
- Settled: yes

| Scan type | Reason | Scanned | For indexing | Dumb ms | Duration ms | Cancelled |
|---|---|---|---|---|---|---|
| PARTIAL_ON_PROJECT_OPEN | On project open | 2 | 0 | 0 | 473 | false |

- TypeScript probe (main, expects 'number'): **correct** after 1 ms; file in project content: true; errors: TS2322: Type 'number' is not assignable to type 'string'.

## P1 main → worktree (first activation)

- main → wt-d
- Swap mutations: 121 ms (VFS refresh 21 ms, content root 66 ms, VCS mapping call 2 ms, waiting for the old root to be released 1 ms, project root 24 ms)
- Git repository for target ready 27 ms after the mapping call; branch wtspike-d
- guessProjectDir(): wt-d (target after 2121 ms); base directories: wt-d
- Entities still referencing the old root: none
- Project root entities: main → wt-d
- Settled (no scanning/indexing, smart for 5 s) after: 27749 ms
- VCS mappings before: main (Git)
- Repositories 2 s after the content-root swap, before re-mapping: main, wt-d
- Repositories after: main, wt-d
- Content roots after: wt-d (excludes carried: 0, source folders dropped: 0)

| Scan type | Reason | Scanned | For indexing | Dumb ms | Duration ms | Cancelled |
|---|---|---|---|---|---|---|
| PARTIAL | Changes from WorkspaceFileIndex (1 iterators) | 20349 | 19917 | 1517 | 3098 | false |

| Dumb indexing: files | Duration ms | Cancelled |
|---|---|---|
| 19448 | 26517 | false |
| 0 | 0 | false |

- TypeScript probe (expects 'boolean'): **correct** after 1209 ms; file in project content: true; errors: TS2322: Type 'boolean' is not assignable to type 'string'.

- TypeScriptServiceRestarter.restartServicesAsync: 7 ms
- TypeScript probe after restarting the services: **correct** after 0 ms; file in project content: true; errors: TS2322: Type 'boolean' is not assignable to type 'string'.

## P2 worktree → main (indexed before)

- wt-d → main
- Swap mutations: 904 ms (VFS refresh 698 ms, content root 11 ms, VCS mapping call 9 ms, waiting for the old root to be released 160 ms, project root 15 ms)
- Git repository for target ready 184 ms after the mapping call; branch main
- guessProjectDir(): main (target after 904 ms); base directories: main
- Entities still referencing the old root: none
- Project root entities: wt-d → main
- Settled (no scanning/indexing, smart for 5 s) after: 1690 ms
- VCS mappings before: wt-d (Git)
- Repositories after: main
- Content roots after: main (excludes carried: 0, source folders dropped: 0)

| Scan type | Reason | Scanned | For indexing | Dumb ms | Duration ms | Cancelled |
|---|---|---|---|---|---|---|
| PARTIAL | Changes from WorkspaceFileIndex (1 iterators) | 20354 | 486 | 232 | 935 | false |

| Dumb indexing: files | Duration ms | Cancelled |
|---|---|---|
| 1 | 29 | false |

- TypeScript probe (expects 'number'): **correct** after 0 ms; file in project content: true; errors: TS2322: Type 'number' is not assignable to type 'string'.

## P3 main → worktree (indexed before)

- main → wt-d
- Swap mutations: 632 ms (VFS refresh 503 ms, content root 6 ms, VCS mapping call 2 ms, waiting for the old root to be released 108 ms, project root 7 ms)
- Git repository for target ready 117 ms after the mapping call; branch wtspike-d
- guessProjectDir(): wt-d (target after 632 ms); base directories: wt-d
- Entities still referencing the old root: none
- Project root entities: main → wt-d
- Settled (no scanning/indexing, smart for 5 s) after: 1030 ms
- VCS mappings before: main (Git)
- Repositories after: wt-d
- Content roots after: wt-d (excludes carried: 0, source folders dropped: 0)

| Scan type | Reason | Scanned | For indexing | Dumb ms | Duration ms | Cancelled |
|---|---|---|---|---|---|---|
| PARTIAL | Changes from WorkspaceFileIndex (1 iterators) | 20349 | 486 | 169 | 499 | false |

| Dumb indexing: files | Duration ms | Cancelled |
|---|---|---|
| 0 | 13 | false |

- TypeScript probe (expects 'boolean'): **correct** after 0 ms; file in project content: true; errors: TS2322: Type 'boolean' is not assignable to type 'string'.

## P4 worktree → main after editing main on disk while inactive

- wt-d → main
- Swap mutations: 667 ms (VFS refresh 493 ms, content root 6 ms, VCS mapping call 4 ms, waiting for the old root to be released 154 ms, project root 4 ms)
- Git repository for target ready 162 ms after the mapping call; branch main
- guessProjectDir(): main (target after 667 ms); base directories: main
- Entities still referencing the old root: none
- Project root entities: wt-d → main
- Settled (no scanning/indexing, smart for 5 s) after: 944 ms
- VCS mappings before: wt-d (Git)
- Repositories after: main
- Content roots after: main (excludes carried: 0, source folders dropped: 0)
- File created on disk while inactive is in the file name index: true
- Identifier written on disk while inactive is in the word index: true

| Scan type | Reason | Scanned | For indexing | Dumb ms | Duration ms | Cancelled |
|---|---|---|---|---|---|---|
| PARTIAL | Changes from WorkspaceFileIndex (1 iterators) | 20355 | 488 | 125 | 418 | false |

| Dumb indexing: files | Duration ms | Cancelled |
|---|---|---|
| 2 | 16 | false |

- TypeScript probe (expects 'number'): **correct** after 0 ms; file in project content: true; errors: TS2322: Type 'number' is not assignable to type 'string'.

