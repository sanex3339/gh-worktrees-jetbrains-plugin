package com.github.sanex3339.worktreetabs.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorktreeAdminDirTest {
    @Test
    fun `a linked worktree's git file points at its admin folder`() {
        assertEquals("/repo/main/.git/worktrees/feat", WorktreeAdminDir.fromGitFile("gitdir: /repo/main/.git/worktrees/feat\n", "/repo/feat"))
    }

    @Test
    fun `a relative gitdir is resolved against the worktree`() {
        assertEquals("/repo/main/.git/worktrees/feat", WorktreeAdminDir.fromGitFile("gitdir: ../main/.git/worktrees/feat", "/repo/feat"))
    }

    @Test
    fun `anything else has no admin folder`() {
        assertNull(WorktreeAdminDir.fromGitFile("not a git file", "/repo/feat"))
    }
}
