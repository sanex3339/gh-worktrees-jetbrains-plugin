package com.github.sanex3339.worktreetabs.ui

import com.github.sanex3339.worktreetabs.model.WorktreeStatus
import com.github.sanex3339.worktreetabs.registry.SwitchSupport
import com.github.sanex3339.worktreetabs.registry.WorktreeIndexHistory
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.registry.WorktreeStatusService
import com.github.sanex3339.worktreetabs.registry.WorktreesState
import com.github.sanex3339.worktreetabs.settings.WorktreeTabsSettings
import com.github.sanex3339.worktreetabs.switching.WorktreeSwitcher
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class WorktreeTabsNorthExtension : IdeRootPaneNorthExtension {
    override val key: String = "WorktreeTabs"

    override fun createComponent(project: Project, isDocked: Boolean): JComponent? = if (isDocked) null else WorktreeTabsStrip(project)
}

/** Owns the coroutines of the strips shown in this project's window; they end with the project. */
@Service(Service.Level.PROJECT)
class WorktreeTabsUiScope(val scope: CoroutineScope)

/**
 * The tab bar under the main toolbar, styled like macOS window tabs; hidden unless the repository has a worktree
 * besides the main checkout.
 * Tabs that don't fit either scroll in one row (wheel or trackpad; the active tab is kept in view) or wrap into more
 * rows. "+" creates a worktree; the chevron lists every worktree.
 */
class WorktreeTabsStrip(private val project: Project) : JPanel(BorderLayout()) {
    private val tabs = TabsPanel()
    private val scroll = JBScrollPane(tabs, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
        border = JBUI.Borders.empty()
        viewportBorder = null
        isOpaque = false
        viewport.isOpaque = false
        // No visible scrollbar: the wheel, the trackpad and the chevron reach the tabs that don't fit.
        horizontalScrollBar.preferredSize = Dimension(0, 0)
    }
    private val newWorktree = StripButton(AllIcons.General.Add, "Create worktree…") { event ->
        ActionManager.getInstance().getAction(NEW_WORKTREE_ACTION)?.let { ActionUtil.invokeAction(it, this, STRIP_POPUP_PLACE, event, null) }
    }
    private val allWorktrees = StripButton(AllIcons.General.ChevronDown, "All worktrees") { _ -> WorktreePopup.create(project).showUnderneathOf(this) }
    private var multipleRows: Boolean? = null
    private var updates: Job? = null

    /** One tab per worktree path, updated in place across renders. */
    private val tabsByPath = LinkedHashMap<String, WorktreeTab>()

    init {
        isVisible = false
        // Transparent: the tabs sit on the window's own background, like macOS window tabs.
        isOpaque = false
        // At the top of the strip, level with the first row of tabs (they stay there when tabs wrap into more rows).
        val buttons = JPanel(HorizontalLayout(JBUI.scale(2), SwingConstants.CENTER)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(TabsPanel.VERTICAL_PADDING, 2, 0, 6)
            add(newWorktree)
            add(allWorktrees)
        }
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(buttons, BorderLayout.NORTH)
        }, BorderLayout.EAST)
        tabs.addMouseWheelListener(::scrollSideways)
        val contextMenu = contextMenuListener(STRIP_POPUP_GROUP)
        addMouseListener(contextMenu)
        tabs.addMouseListener(contextMenu)
    }

    override fun addNotify() {
        super.addNotify()
        val registry = WorktreeRegistry.getInstance(project)
        val switcher = WorktreeSwitcher.getInstance(project)
        val settings = WorktreeTabsSettings.getInstance()
        val statuses = WorktreeStatusService.getInstance(project).statuses
        val indexHistory = WorktreeIndexHistory.getInstance()
        updates = project.service<WorktreeTabsUiScope>().scope.launch(Dispatchers.EDT) {
            combine(registry.state, switcher.switchingTo, settings.revision, statuses, indexHistory.revision) { state, switching, _, status, _ -> Triple(state, switching, status) }
                .collect { (state, switching, status) -> render(state, switching, status, settings.multipleRows) }
        }
    }

    override fun removeNotify() {
        updates?.cancel()
        updates = null
        super.removeNotify()
    }

    private fun render(state: WorktreesState, switching: String?, statuses: Map<String, WorktreeStatus>, wrap: Boolean) {
        // Only the main checkout means nothing to switch to; Git | Create Worktree… makes the first one.
        isVisible = state.worktrees.size > 1
        if (wrap != multipleRows) layOut(wrap)

        val indexHistory = WorktreeIndexHistory.getInstance()
        val unsupported = (state.support as? SwitchSupport.Unsupported)?.reason
        val ordered = state.worktrees.map { worktree ->
            val active = worktree.path == state.activePath
            val tabState = TabState(
                active = active,
                switching = worktree.path == switching,
                indexed = active || indexHistory.isIndexed(worktree.path),
                status = statuses[worktree.path],
                disabledReason = unsupported,
            )
            tabsByPath[worktree.path]?.also { it.update(worktree, tabState) }
                ?: WorktreeTab(worktree, tabState, onSelect = { WorktreeSwitcher.getInstance(project).switchTo(it.path) }, contextMenu = contextMenuListener(TAB_POPUP_GROUP))
        }
        tabsByPath.clear()
        ordered.forEach { tabsByPath[it.worktree.path] = it }
        // Touch the component tree only when tabs come, go or move; a status change repaints the tabs it concerns.
        if (tabs.components.toList() != ordered) {
            tabs.removeAll()
            ordered.forEach(tabs::add)
        }
        val focusTab = tabsByPath[switching ?: state.activePath]
        revalidate()
        repaint()
        focusTab?.let { tab -> SwingUtilities.invokeLater { if (!wrap) tabs.scrollRectToVisible(tab.bounds) } }
    }

    private fun layOut(wrap: Boolean) {
        multipleRows = wrap
        remove(scroll)
        remove(tabs)
        tabs.wrap = wrap
        if (wrap) {
            add(tabs, BorderLayout.CENTER)
        } else {
            scroll.setViewportView(tabs)
            add(scroll, BorderLayout.CENTER)
        }
    }

    /** The mouse wheel scrolls the single row sideways; trackpads scroll it natively. */
    private fun scrollSideways(e: MouseWheelEvent) {
        if (multipleRows != false) return
        val bar = scroll.horizontalScrollBar
        bar.value += (e.preciseWheelRotation * JBUI.scale(WHEEL_STEP)).toInt()
        e.consume()
    }

    private fun contextMenuListener(groupId: String) = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = showIfTrigger(e)

        override fun mouseReleased(e: MouseEvent) = showIfTrigger(e)

        private fun showIfTrigger(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            val group = ActionManager.getInstance().getAction(groupId) as? ActionGroup ?: return
            val menu = ActionManager.getInstance().createActionPopupMenu(STRIP_POPUP_PLACE, group)
            // The tab under the mouse provides its worktree to the menu's actions.
            (e.component as? JComponent)?.let(menu::setTargetComponent)
            menu.component.show(e.component, e.x, e.y)
            e.consume()
        }
    }

    private companion object {
        const val WHEEL_STEP = 40
        const val STRIP_POPUP_GROUP = "WorktreeTabs.StripPopup"
        const val TAB_POPUP_GROUP = "WorktreeTabs.TabPopup"
        const val NEW_WORKTREE_ACTION = "WorktreeTabs.New"
        const val STRIP_POPUP_PLACE = "WorktreeTabsStrip"
    }
}
