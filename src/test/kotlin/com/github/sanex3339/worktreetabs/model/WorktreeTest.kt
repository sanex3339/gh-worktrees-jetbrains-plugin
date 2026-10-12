package com.github.sanex3339.worktreetabs.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorktreeTest {
    @Test
    fun `a worktree on a branch is labelled with the branch`() {
        assertEquals("feat/login", Worktree("/repo/feat", "2222222", "feat/login", isMain = false).label)
    }

    @Test
    fun `a detached worktree is labelled with its folder and short hash`() {
        assertEquals("review @ 3333333", Worktree("/repo/review", "3333333333333333", null, isMain = false).label)
    }

    @Test
    fun `tabs show the main checkout first, then the others by label, without bare or prunable entries`() {
        val main = Worktree("/repo/main", "1", "main", isMain = true)
        val zeta = Worktree("/repo/z", "2", "Zeta", isMain = false)
        val alpha = Worktree("/repo/a", "3", "alpha", isMain = false)
        val gone = Worktree("/repo/gone", "4", "beta", isMain = false, isPrunable = true)
        val bare = Worktree("/repo/bare.git", null, null, isMain = true, isBare = true)

        assertEquals(listOf(main, alpha, zeta), WorktreeOrder.forTabs(listOf(zeta, gone, main, alpha)))
        assertEquals(listOf(alpha, zeta), WorktreeOrder.forTabs(listOf(bare, zeta, alpha)))
    }
}
