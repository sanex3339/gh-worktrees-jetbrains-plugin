package com.github.sanex3339.worktreetabs.actions

import com.github.sanex3339.worktreetabs.commands.NewWorktreeDialog
import com.github.sanex3339.worktreetabs.commands.WorktreeCommands
import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.registry.SwitchSupport
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.registry.WorktreeStatusService
import com.github.sanex3339.worktreetabs.switching.WorktreeSwitcher
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import git4idea.repo.GitRepositoryManager

object WorktreeDataKeys {
    /** The worktree whose tab the action was invoked on. */
    val WORKTREE: DataKey<Worktree> = DataKey.create("WorktreeTabs.Worktree")
}

/** Base for the actions in a tab's context menu: they act on that tab's worktree. */
abstract class WorktreeTabAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    final override fun update(e: AnActionEvent) {
        val worktree = e.getData(WorktreeDataKeys.WORKTREE)
        e.presentation.isEnabledAndVisible = worktree != null && e.project != null
        if (worktree != null && e.project != null) update(e, worktree)
    }

    open fun update(e: AnActionEvent, worktree: Worktree) = Unit

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val worktree = e.getData(WorktreeDataKeys.WORKTREE) ?: return
        perform(project, worktree)
    }

    abstract fun perform(project: com.intellij.openapi.project.Project, worktree: Worktree)
}

class SwitchToWorktreeAction : WorktreeTabAction() {
    override fun update(e: AnActionEvent, worktree: Worktree) {
        val state = WorktreeRegistry.getInstance(e.project!!).state.value
        e.presentation.isEnabled = state.support is SwitchSupport.Supported && worktree.path != state.activePath
    }

    override fun perform(project: com.intellij.openapi.project.Project, worktree: Worktree) = WorktreeSwitcher.getInstance(project).switchTo(worktree.path)
}

class LockWorktreeAction : WorktreeTabAction() {
    override fun update(e: AnActionEvent, worktree: Worktree) {
        e.presentation.isVisible = !worktree.isMain && !worktree.isLocked
    }

    override fun perform(project: com.intellij.openapi.project.Project, worktree: Worktree) {
        val reason = Messages.showInputDialog(
            project,
            "Locked worktrees can't be pruned, moved or removed until unlocked.\nReason (optional):",
            "Lock Worktree ${worktree.label}",
            null,
        ) ?: return
        WorktreeCommands.getInstance(project).lock(worktree, reason)
    }
}

class UnlockWorktreeAction : WorktreeTabAction() {
    override fun update(e: AnActionEvent, worktree: Worktree) {
        e.presentation.isVisible = !worktree.isMain && worktree.isLocked
    }

    override fun perform(project: com.intellij.openapi.project.Project, worktree: Worktree) = WorktreeCommands.getInstance(project).unlock(worktree)
}

class RemoveWorktreeAction : WorktreeTabAction() {
    override fun update(e: AnActionEvent, worktree: Worktree) {
        e.presentation.isVisible = !worktree.isMain
        e.presentation.isEnabled = !worktree.isLocked
        if (worktree.isLocked) e.presentation.description = "Unlock the worktree before removing it"
    }

    override fun perform(project: com.intellij.openapi.project.Project, worktree: Worktree) {
        val changes = WorktreeStatusService.getInstance(project).statuses.value[worktree.path]?.changes ?: 0
        val branch = worktree.branch?.let { "The branch $it stays." } ?: "Its commits stay reachable only if a branch or tag points at them."
        val message = buildString {
            append("The folder ${worktree.path} will be deleted. $branch")
            if (changes > 0) append("\n\nIt has $changes uncommitted ${if (changes == 1) "file" else "files"}, which will be lost.")
        }
        val confirmed = MessageDialogBuilder.yesNo("Remove the worktree ${worktree.label}?", message)
            .yesText(if (changes > 0) "Remove Anyway" else "Remove")
            .noText("Cancel")
            .asWarning()
            .ask(project)
        if (confirmed) WorktreeCommands.getInstance(project).remove(worktree, force = changes > 0)
    }
}

class NewWorktreeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && WorktreeRegistry.getInstance(project).state.value.worktrees.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val state = WorktreeRegistry.getInstance(project).state.value
        val main = state.worktrees.firstOrNull { it.isMain } ?: return
        val repository = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return
        val checkedOut = state.worktrees.mapNotNull { it.branch }.toSet()
        val freeBranches = repository.branches.localBranches.map { it.name }.filterNot(checkedOut::contains).sorted()
        val dialog = NewWorktreeDialog(project, main.path, freeBranches, repository.currentBranchName)
        if (dialog.showAndGet()) WorktreeCommands.getInstance(project).create(dialog.request)
    }
}
