package com.github.sanex3339.worktreetabs.actions

import com.github.sanex3339.worktreetabs.registry.SwitchSupport
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.settings.WorktreeTabsSettings
import com.github.sanex3339.worktreetabs.ui.WorktreePopup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction

/** A popup listing every worktree; choosing one switches the project to it. */
class SwitchWorktreeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val state = e.project?.let { WorktreeRegistry.getInstance(it).state.value }
        e.presentation.isVisible = state != null && state.worktrees.isNotEmpty()
        e.presentation.isEnabled = state?.support is SwitchSupport.Supported && state.worktrees.size > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        WorktreePopup.create(project).showCenteredInCurrentWindow(project)
    }
}

class ToggleMultipleRowsAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = WorktreeTabsSettings.getInstance().multipleRows

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        WorktreeTabsSettings.getInstance().multipleRows = state
    }
}
