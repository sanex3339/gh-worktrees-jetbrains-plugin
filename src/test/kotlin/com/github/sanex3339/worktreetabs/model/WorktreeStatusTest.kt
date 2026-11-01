package com.github.sanex3339.worktreetabs.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorktreeStatusTest {
    @Test
    fun `counts changed, renamed, conflicted and untracked files and reads ahead and behind`() {
        val output = """
            # branch.oid 1111111111111111111111111111111111111111
            # branch.head feat/login
            # branch.upstream origin/feat/login
            # branch.ab +2 -1
            1 .M N... 100644 100644 100644 aaaaaaa bbbbbbb src/app.ts
            2 R. N... 100644 100644 100644 aaaaaaa bbbbbbb R100 src/new.ts	src/old.ts
            u UU N... 100644 100644 100644 100644 aaaaaaa bbbbbbb ccccccc src/conflict.ts
            ? notes.md
        """.trimIndent().lines()

        assertEquals(
            WorktreeStatus(4, ahead = 2, behind = 1, upstream = "origin/feat/login", changedPaths = listOf("src/app.ts", "src/new.ts", "src/conflict.ts", "notes.md")),
            WorktreeStatus.parse(output),
        )
    }

    @Test
    fun `a branch without upstream has no ahead or behind`() {
        val output = listOf("# branch.oid 1111", "# branch.head local-only", "1 .M N... 100644 100644 100644 a b x.ts")
        assertEquals(WorktreeStatus(1, ahead = null, behind = null, upstream = null, changedPaths = listOf("x.ts")), WorktreeStatus.parse(output))
    }

    @Test
    fun `quoted paths are unquoted, including non-ASCII ones git writes as octal bytes`() {
        val output = listOf("1 .M N... 100644 100644 100644 a b \"caf\\303\\251 \\\"menu\\\".ts\"", "? \"tab\\there.md\"")
        assertEquals(listOf("café \"menu\".ts", "tab\there.md"), WorktreeStatus.parse(output).changedPaths)
    }

    @Test
    fun `badges show changes as a dot and commits against upstream as arrows`() {
        assertEquals("↑2 ↓1", WorktreeStatus(changes = 0, ahead = 2, behind = 1, upstream = "origin/x").commitsBadge)
        assertEquals("↑3", WorktreeStatus(changes = 0, ahead = 3, behind = 0, upstream = "origin/x").commitsBadge)
        assertEquals("↓4", WorktreeStatus(changes = 0, ahead = 0, behind = 4, upstream = "origin/x").commitsBadge)
        assertEquals(null, WorktreeStatus(changes = 5, ahead = 0, behind = 0, upstream = "origin/x").commitsBadge)
        assertEquals(null, WorktreeStatus(changes = 5, ahead = null, behind = null, upstream = null).commitsBadge)
    }
}
