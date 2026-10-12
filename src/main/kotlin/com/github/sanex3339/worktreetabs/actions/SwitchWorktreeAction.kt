package com.github.sanex3339.worktreetabs.actions

import com.github.sanex3339.worktreetabs.registry.SwitchSupport
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.switching.WorktreeSwitcher
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory

/** A popup listing the other worktrees; choosing one switches the project to it. */
class SwitchWorktreeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val state = e.project?.let { WorktreeRegistry.getInstance(it).state.value }
        e.presentation.isEnabledAndVisible = state != null && state.worktrees.isNotEmpty()
        e.presentation.isEnabled = state?.support is SwitchSupport.Supported && state.worktrees.size > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val state = WorktreeRegistry.getInstance(project).state.value
        val targets = state.worktrees.filter { it.path != state.activePath }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(targets)
            .setTitle("Switch Worktree")
            .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { "${it.label}  ${it.path}" })
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { WorktreeSwitcher.getInstance(project).switchTo(it.path) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}
