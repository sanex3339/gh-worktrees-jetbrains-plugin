package com.github.sanex3339.worktreetabs.ui

import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.switching.WorktreeSwitcher
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

/** Every worktree in a filterable list (type to filter); choosing one switches to it. */
object WorktreePopup {
    fun create(project: Project): JBPopup {
        val state = WorktreeRegistry.getInstance(project).state.value
        return JBPopupFactory.getInstance()
            .createPopupChooserBuilder(state.worktrees)
            .setTitle("Switch Worktree")
            .setRenderer(Renderer(state.activePath))
            .setNamerForFiltering { "${it.label} ${it.path}" }
            .setSelectedValue(state.active, true)
            .setItemChosenCallback { if (it.path != state.activePath) WorktreeSwitcher.getInstance(project).switchTo(it.path) }
            .createPopup()
    }

    private class Renderer(private val activePath: String?) : ColoredListCellRenderer<Worktree>() {
        override fun customizeCellRenderer(list: JList<out Worktree>, value: Worktree, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = if (value.path == activePath) AllIcons.Actions.Checked else AllIcons.Nodes.Folder
            append(value.label, if (value.path == activePath) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ${value.path}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
