package com.github.sanex3339.worktreetabs.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorktreeListParserTest {
    @Test
    fun `reads main, branch, detached, locked and prunable worktrees`() {
        val output = """
            worktree /repo/main
            HEAD 1111111111111111111111111111111111111111
            branch refs/heads/main

            worktree /repo/feat
            HEAD 2222222222222222222222222222222222222222
            branch refs/heads/feat/login
            locked agent at work

            worktree /repo/review
            HEAD 3333333333333333333333333333333333333333
            detached

            worktree /repo/gone
            HEAD 4444444444444444444444444444444444444444
            branch refs/heads/gone
            prunable gitdir file points to non-existent location
        """.trimIndent().lines()

        assertEquals(
            listOf(
                Worktree("/repo/main", "1111111111111111111111111111111111111111", "main", isMain = true),
                Worktree("/repo/feat", "2222222222222222222222222222222222222222", "feat/login", isMain = false, isLocked = true, lockReason = "agent at work"),
                Worktree("/repo/review", "3333333333333333333333333333333333333333", null, isMain = false),
                Worktree("/repo/gone", "4444444444444444444444444444444444444444", "gone", isMain = false, isPrunable = true),
            ),
            WorktreeListParser.parse(output),
        )
    }

    @Test
    fun `a lock without a reason is still a lock`() {
        val parsed = WorktreeListParser.parse(listOf("worktree /repo/main", "HEAD 1", "branch refs/heads/main", "", "worktree /repo/x", "HEAD 2", "detached", "locked"))
        assertEquals(Worktree("/repo/x", "2", null, isMain = false, isLocked = true), parsed[1])
    }

    @Test
    fun `a bare main repository is marked bare`() {
        val parsed = WorktreeListParser.parse(listOf("worktree /repo/bare.git", "bare", "", "worktree /repo/a", "HEAD 1", "branch refs/heads/a"))
        assertEquals(Worktree("/repo/bare.git", null, null, isMain = true, isBare = true), parsed[0])
        assertEquals(Worktree("/repo/a", "1", "a", isMain = false), parsed[1])
    }

    @Test
    fun `windows paths are made system independent`() {
        val parsed = WorktreeListParser.parse(listOf("worktree C:\\work\\repo", "HEAD 1", "branch refs/heads/main"))
        assertEquals("C:/work/repo", parsed.single().path)
    }

    @Test
    fun `no output means no worktrees`() {
        assertTrue(WorktreeListParser.parse(emptyList()).isEmpty())
    }
}
