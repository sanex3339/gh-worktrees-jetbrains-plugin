package com.github.sanex3339.worktreetabs.commands

import com.github.sanex3339.worktreetabs.git.GitOutcome
import com.github.sanex3339.worktreetabs.git.WorktreeGit
import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.registry.WorktreeIndexHistory
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.registry.WorktreeStatusService
import com.github.sanex3339.worktreetabs.switching.WorktreeStateStore
import com.github.sanex3339.worktreetabs.switching.WorktreeSwitcher
import com.github.sanex3339.worktreetabs.ui.WorktreeNotifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Creates, removes, locks and unlocks worktrees in the background, reporting failures as notifications. */
@Service(Service.Level.PROJECT)
class WorktreeCommands(private val project: Project, private val scope: CoroutineScope) {
    private val beingRemoved = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Whether `git worktree remove` is running for the worktree at [path] (its folder is about to disappear). */
    fun isBeingRemoved(path: String): Boolean = path in beingRemoved

    data class NewWorktreeRequest(val location: String, val branch: String, val newBranch: Boolean, val base: String?, val switchToIt: Boolean)

    fun create(request: NewWorktreeRequest) {
        scope.launch {
            val root = repositoryRoot() ?: return@launch
            val outcome = withBackgroundProgress(project, "Creating worktree ${request.branch}…") {
                withContext(Dispatchers.IO) { WorktreeGit.add(project, root, request.location, request.branch, request.newBranch, request.base) }
            }
            if (!report(outcome, "Couldn't create the worktree")) return@launch
            val registry = WorktreeRegistry.getInstance(project)
            registry.refresh()
            val created = waitFor(LIST_TIMEOUT_MS) { registry.state.value.worktrees.firstOrNull { sameFolder(it.path, request.location) } }
            when {
                created == null -> WorktreeNotifications.warn(project, "Created the worktree at ${request.location}, but it isn't listed yet")
                request.switchToIt -> WorktreeSwitcher.getInstance(project).switchTo(created.path)
                else -> WorktreeNotifications.info(project, "Created the worktree ${created.label}")
            }
        }
    }

    /** Switches to the main checkout first when [worktree] is the active one; [force] removes uncommitted changes too. */
    fun remove(worktree: Worktree, force: Boolean) {
        scope.launch {
            val registry = WorktreeRegistry.getInstance(project)
            if (registry.state.value.activePath == worktree.path) {
                val main = registry.state.value.worktrees.firstOrNull { it.isMain } ?: return@launch
                val switcher = WorktreeSwitcher.getInstance(project)
                switcher.switchTo(main.path)
                val away = waitFor(SWITCH_TIMEOUT_MS) { (registry.state.value.activePath == main.path && switcher.switchingTo.value == null).takeIf { it } }
                if (away == null) {
                    WorktreeNotifications.error(project, "Couldn't switch away from ${worktree.label}, so it wasn't removed")
                    return@launch
                }
            }
            val root = repositoryRoot() ?: return@launch
            beingRemoved += worktree.path
            val outcome = try {
                withBackgroundProgress(project, "Removing worktree ${worktree.label}…") {
                    withContext(Dispatchers.IO) { WorktreeGit.remove(project, root, worktree.path, force) }
                }
            } finally {
                beingRemoved -= worktree.path
            }
            if (!report(outcome, "Couldn't remove the worktree ${worktree.label}")) return@launch
            WorktreeStateStore.getInstance(project).forget(worktree.path)
            WorktreeIndexHistory.getInstance().forget(worktree.path)
            registry.refresh()
            WorktreeNotifications.info(project, "Removed the worktree ${worktree.label}")
        }
    }

    fun lock(worktree: Worktree, reason: String?) = runAndRefresh("Couldn't lock ${worktree.label}") { root -> WorktreeGit.lock(project, root, worktree.path, reason) }

    fun unlock(worktree: Worktree) = runAndRefresh("Couldn't unlock ${worktree.label}") { root -> WorktreeGit.unlock(project, root, worktree.path) }

    private fun runAndRefresh(failure: String, command: (VirtualFile) -> GitOutcome) {
        scope.launch {
            val root = repositoryRoot() ?: return@launch
            val outcome = withContext(Dispatchers.IO) { command(root) }
            if (report(outcome, failure)) {
                WorktreeRegistry.getInstance(project).refresh()
                WorktreeStatusService.getInstance(project).refresh()
            }
        }
    }

    private fun repositoryRoot(): VirtualFile? = GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.root

    private fun report(outcome: GitOutcome, failure: String): Boolean {
        outcome.error?.let { WorktreeNotifications.error(project, "$failure: $it") }
        return outcome.succeeded
    }

    private suspend fun <T : Any> waitFor(timeoutMs: Long, probe: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            delay(100)
        }
        return probe()
    }

    private fun sameFolder(a: String, b: String): Boolean = a == b || runCatching { Files.isSameFile(Path.of(a), Path.of(b)) }.getOrDefault(false)

    companion object {
        private const val LIST_TIMEOUT_MS = 10_000L
        private const val SWITCH_TIMEOUT_MS = 120_000L

        fun getInstance(project: Project): WorktreeCommands = project.service()
    }
}
