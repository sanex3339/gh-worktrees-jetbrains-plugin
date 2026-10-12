package com.github.sanex3339.worktreetabs.git

import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.model.WorktreeListParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler

private val LOG = logger<WorktreeGit>()

/** Git commands about worktrees, through git4idea's handlers (its executable, environment and logging). */
object WorktreeGit {
    /** The repository's worktrees, the main one first; null when git fails. Runs git: call it off the EDT. */
    fun list(project: Project, repositoryRoot: VirtualFile): List<Worktree>? {
        val handler = GitLineHandler(project, repositoryRoot, GitCommand.WORKTREE)
        handler.addParameters("list", "--porcelain")
        handler.setSilent(true)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            LOG.warn("git worktree list failed in ${repositoryRoot.path}: ${result.errorOutputAsJoinedString}")
            return null
        }
        return WorktreeListParser.parse(result.output)
    }
}
