package com.github.sanex3339.worktreetabs.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NewWorktreeTest {
    @Test
    fun `a new worktree goes next to the main checkout, in a folder named after the branch`() {
        assertEquals("/work/app.worktrees/feat-login", NewWorktree.defaultLocation("/work/app", "feat/login"))
        assertEquals("/work/app.worktrees/fix-1", NewWorktree.defaultLocation("/work/app/", "fix-1"))
    }

    @Test
    fun `accepts ordinary branch names`() {
        listOf("feat/login", "fix-1", "release/1.94", "agent_3").forEach { assertNull(NewWorktree.branchNameProblem(it), it) }
    }

    @Test
    fun `rejects names git would refuse`() {
        listOf("", " ", "has space", "-starts-with-dash", "a..b", "ends/", "ends.lock", "a:b", "a~b", "a^b", "a?b", "a*b", "a[b", "a\\b", "@").forEach {
            assertNotNull(NewWorktree.branchNameProblem(it), it)
        }
    }
}
