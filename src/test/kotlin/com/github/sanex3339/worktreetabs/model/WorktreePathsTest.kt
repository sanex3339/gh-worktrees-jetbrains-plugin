package com.github.sanex3339.worktreetabs.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorktreePathsTest {
    @Test
    fun `a path is under a root only inside it, not next to it or equal to it`() {
        assertTrue(WorktreePaths.isStrictlyUnder("/repo/main/src", "/repo/main"))
        assertFalse(WorktreePaths.isStrictlyUnder("/repo/main", "/repo/main"))
        assertFalse(WorktreePaths.isStrictlyUnder("/repo/main-2/src", "/repo/main"))
    }

    @Test
    fun `rebase moves paths under the old root and leaves the rest`() {
        assertEquals("/repo/b/src/app.ts", WorktreePaths.rebase("/repo/a/src/app.ts", "/repo/a", "/repo/b"))
        assertEquals("/repo/b", WorktreePaths.rebase("/repo/a", "/repo/a", "/repo/b"))
        assertEquals("/repo/ab/x", WorktreePaths.rebase("/repo/ab/x", "/repo/a", "/repo/b"))
    }

    @Test
    fun `switching away from the main checkout carries its exclusions and drops the nested worktrees it excluded`() {
        val worktrees = listOf("/repo/main", "/repo/main/.claude/worktrees/agent", "/repo/wt-b")
        assertEquals(
            listOf("/repo/wt-b/dist"),
            WorktreePaths.planExcludes("/repo/main", "/repo/wt-b", listOf("/repo/main/dist", "/repo/main/.claude/worktrees/agent"), worktrees),
        )
    }

    @Test
    fun `switching to the main checkout excludes the worktrees nested inside it`() {
        val worktrees = listOf("/repo/main", "/repo/main/.claude/worktrees/agent", "/repo/wt-b")
        assertEquals(
            listOf("/repo/main/dist", "/repo/main/.claude/worktrees/agent"),
            WorktreePaths.planExcludes("/repo/wt-b", "/repo/main", listOf("/repo/wt-b/dist"), worktrees),
        )
    }

    @Test
    fun `switching into a nested worktree does not exclude the new root itself`() {
        val worktrees = listOf("/repo/main", "/repo/main/.claude/worktrees/agent")
        assertEquals(
            listOf("/repo/main/.claude/worktrees/agent/dist"),
            WorktreePaths.planExcludes("/repo/main", "/repo/main/.claude/worktrees/agent", listOf("/repo/main/dist", "/repo/main/.claude/worktrees/agent"), worktrees),
        )
    }
}
