package com.github.sanex3339.worktreetabs.git

import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.model.WorktreeListParser
import com.github.sanex3339.worktreetabs.model.WorktreeStatus
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.nio.file.Path

private val LOG = logger<WorktreeGit>()

/** Outcome of a git command that changes something: null error means it worked. */
data class GitOutcome(val error: String?) {
    val succeeded: Boolean get() = error == null
}

/**
 * Git commands about worktrees, through git4idea's handlers (its executable, environment and logging). They run git,
 * so call them off the EDT.
 */
object WorktreeGit {
    /** The repository's worktrees, the main one first; null when git fails. */
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

    /**
     * Uncommitted files and commits against upstream of the worktree at [path]. Runs without optional locks, so it
     * never takes `index.lock` from under an agent working in that worktree.
     */
    fun status(project: Project, path: String): WorktreeStatus? {
        val handler = GitLineHandler(project, Path.of(path), GitCommand.STATUS)
        handler.addParameters("--porcelain=v2", "--branch", "--untracked-files=normal")
        handler.addCustomEnvironmentVariable("GIT_OPTIONAL_LOCKS", "0")
        handler.setSilent(true)
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) WorktreeStatus.parse(result.output) else null
    }

    /** `git worktree add`: on a new branch from [base] when [newBranch] is set, otherwise on the existing [branch]. */
    fun add(project: Project, repositoryRoot: VirtualFile, location: String, branch: String, newBranch: Boolean, base: String?): GitOutcome =
        run(project, repositoryRoot, if (newBranch) listOfNotNull("add", "-b", branch, location, base) else listOf("add", location, branch))

    /** `git worktree remove`; [force] also removes it with uncommitted changes. */
    fun remove(project: Project, repositoryRoot: VirtualFile, location: String, force: Boolean): GitOutcome =
        run(project, repositoryRoot, listOfNotNull("remove", "--force".takeIf { force }, location))

    fun lock(project: Project, repositoryRoot: VirtualFile, location: String, reason: String?): GitOutcome =
        run(project, repositoryRoot, listOfNotNull("lock", reason?.takeIf { it.isNotBlank() }?.let { "--reason=$it" }, location))

    fun unlock(project: Project, repositoryRoot: VirtualFile, location: String): GitOutcome =
        run(project, repositoryRoot, listOf("unlock", location))

    private fun run(project: Project, repositoryRoot: VirtualFile, parameters: List<String>): GitOutcome {
        val handler = GitLineHandler(project, repositoryRoot, GitCommand.WORKTREE)
        handler.addParameters(parameters)
        val result = Git.getInstance().runCommand(handler)
        if (result.success()) return GitOutcome(null)
        LOG.warn("git worktree ${parameters.joinToString(" ")} failed: ${result.errorOutputAsJoinedString}")
        return GitOutcome(result.errorOutputAsJoinedString.ifBlank { "git worktree ${parameters.first()} failed" })
    }
}
