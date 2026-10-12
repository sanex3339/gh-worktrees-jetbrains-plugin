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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

class WorktreeTabsNorthExtension : IdeRootPaneNorthExtension {
    override val key: String = "WorktreeTabs"

    override fun createComponent(project: Project, isDocked: Boolean): JComponent? = if (isDocked) null else WorktreeTabsStrip(project)
}

/** Owns the coroutines of the strips shown in this project's window; they end with the project. */
@Service(Service.Level.PROJECT)
class WorktreeTabsUiScope(val scope: CoroutineScope)

/**
 * The full-width row of worktree tabs under the main toolbar, hidden in projects without a Git repository. Tabs that
 * don't fit either scroll in one row (wheel or trackpad; the active tab is kept in view) or wrap into more rows; the
 * chevron at the end lists every worktree.
 */
class WorktreeTabsStrip(private val project: Project) : JPanel(BorderLayout()) {
    private val tabs = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply { isOpaque = false }
    private val scroll = JBScrollPane(tabs, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
        border = JBUI.Borders.empty()
        viewportBorder = null
        isOpaque = false
        viewport.isOpaque = false
        // No visible scrollbar: the wheel, the trackpad and the chevron reach the tabs that don't fit.
        horizontalScrollBar.preferredSize = Dimension(0, 0)
    }
    private val chevron = JBLabel(AllIcons.General.ChevronDown).apply {
        border = JBUI.Borders.empty(0, 8)
        toolTipText = "All worktrees"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val newButton = JBLabel(AllIcons.General.Add).apply {
        border = JBUI.Borders.empty(0, 6)
        toolTipText = "New worktree…"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private var multipleRows: Boolean? = null
    private var updates: Job? = null

    init {
        isVisible = false
        border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.EditorTabs.borderColor())
        // BorderLayout stretches both buttons to the strip's height, so their icons sit centered like the tab labels.
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(newButton, BorderLayout.WEST)
            add(chevron, BorderLayout.EAST)
        }, BorderLayout.EAST)
        chevron.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) WorktreePopup.create(project).showUnderneathOf(chevron)
            }
        })
        newButton.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val action = ActionManager.getInstance().getAction(NEW_WORKTREE_ACTION) ?: return
                ActionUtil.invokeAction(action, newButton, STRIP_POPUP_PLACE, e, null)
            }
        })
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

    override fun paintComponent(g: Graphics) {
        g.color = JBUI.CurrentTheme.EditorTabs.background()
        g.fillRect(0, 0, width, height)
    }

    private fun render(state: WorktreesState, switching: String?, statuses: Map<String, WorktreeStatus>, wrap: Boolean) {
        isVisible = state.worktrees.isNotEmpty()
        chevron.isVisible = state.worktrees.size > 1
        val indexHistory = WorktreeIndexHistory.getInstance()
        if (wrap != multipleRows) layOut(wrap)

        tabs.removeAll()
        val unsupported = (state.support as? SwitchSupport.Unsupported)?.reason
        var activeTab: WorktreeTab? = null
        for (worktree in state.worktrees) {
            val active = worktree.path == state.activePath
            val tab = WorktreeTab(
                worktree = worktree,
                state = TabState(
                    active = active,
                    switching = worktree.path == switching,
                    indexed = active || indexHistory.isIndexed(worktree.path),
                    status = statuses[worktree.path],
                    disabledReason = unsupported,
                ),
                onSelect = { WorktreeSwitcher.getInstance(project).switchTo(worktree.path) },
                contextMenu = contextMenuListener(TAB_POPUP_GROUP),
            )
            if (worktree.path == (switching ?: state.activePath)) activeTab = tab
            tabs.add(tab)
        }
        revalidate()
        repaint()
        activeTab?.let { tab -> SwingUtilities.invokeLater { if (!wrap) tabs.scrollRectToVisible(tab.bounds) } }
    }

    private fun layOut(wrap: Boolean) {
        multipleRows = wrap
        remove(scroll)
        remove(tabs)
        if (wrap) {
            tabs.layout = WrapLayout(FlowLayout.LEADING, 0, 0)
            add(tabs, BorderLayout.CENTER)
        } else {
            tabs.layout = FlowLayout(FlowLayout.LEADING, 0, 0)
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
