package com.github.sanex3339.worktreetabs.ui

import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.registry.SwitchSupport
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.registry.WorktreesState
import com.github.sanex3339.worktreetabs.settings.WorktreeTabsSettings
import com.github.sanex3339.worktreetabs.switching.WorktreeSwitcher
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
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
    private var multipleRows: Boolean? = null
    private var updates: Job? = null

    init {
        isVisible = false
        border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.EditorTabs.borderColor())
        add(chevron, BorderLayout.EAST)
        chevron.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) WorktreePopup.create(project).showUnderneathOf(chevron)
            }
        })
        tabs.addMouseWheelListener(::scrollSideways)
        val contextMenu = contextMenuListener()
        addMouseListener(contextMenu)
        tabs.addMouseListener(contextMenu)
    }

    override fun addNotify() {
        super.addNotify()
        val registry = WorktreeRegistry.getInstance(project)
        val switcher = WorktreeSwitcher.getInstance(project)
        val settings = WorktreeTabsSettings.getInstance()
        updates = project.service<WorktreeTabsUiScope>().scope.launch(Dispatchers.EDT) {
            combine(registry.state, switcher.switchingTo, settings.revision) { state, switching, _ -> state to switching }
                .collect { (state, switching) -> render(state, switching, settings.multipleRows) }
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

    private fun render(state: WorktreesState, switching: String?, wrap: Boolean) {
        isVisible = state.worktrees.isNotEmpty()
        chevron.isVisible = state.worktrees.size > 1
        if (wrap != multipleRows) layOut(wrap)

        tabs.removeAll()
        val unsupported = (state.support as? SwitchSupport.Unsupported)?.reason
        var activeTab: WorktreeTab? = null
        for (worktree in state.worktrees) {
            val tab = WorktreeTab(
                worktree = worktree,
                active = worktree.path == state.activePath,
                switching = worktree.path == switching,
                disabledReason = unsupported,
                onSelect = { WorktreeSwitcher.getInstance(project).switchTo(worktree.path) },
                contextMenu = contextMenuListener(),
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

    private fun contextMenuListener() = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = showIfTrigger(e)

        override fun mouseReleased(e: MouseEvent) = showIfTrigger(e)

        private fun showIfTrigger(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            val group = ActionManager.getInstance().getAction(STRIP_POPUP_GROUP) as? ActionGroup ?: return
            ActionManager.getInstance().createActionPopupMenu(STRIP_POPUP_PLACE, group).component.show(e.component, e.x, e.y)
            e.consume()
        }
    }

    private companion object {
        const val WHEEL_STEP = 40
        const val STRIP_POPUP_GROUP = "WorktreeTabs.StripPopup"
        const val STRIP_POPUP_PLACE = "WorktreeTabsStrip"
    }
}

private class WorktreeTab(
    private val worktree: Worktree,
    private val active: Boolean,
    switching: Boolean,
    private val disabledReason: String?,
    onSelect: () -> Unit,
    contextMenu: MouseAdapter,
) : JPanel(BorderLayout()) {
    private var hovered = false
    private val selectable = !active && disabledReason == null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 12)
        add(
            JBLabel(worktree.label, if (switching) AnimatedIcon.Default.INSTANCE else null, SwingConstants.LEADING).apply {
                font = JBUI.CurrentTheme.EditorTabs.font()
                foreground = when {
                    active -> JBUI.CurrentTheme.EditorTabs.underlinedTabForeground()
                    disabledReason != null -> UIUtil.getLabelDisabledForeground()
                    else -> UIUtil.getLabelForeground()
                }
            },
        )
        toolTipText = tooltip()
        if (selectable) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(contextMenu)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (selectable && e.button == MouseEvent.BUTTON1 && !e.isPopupTrigger && contains(e.point)) onSelect()
            }
        })
    }

    override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, JBUI.scale(TAB_HEIGHT))

    override fun paintComponent(g: Graphics) {
        val background = when {
            active -> JBUI.CurrentTheme.EditorTabs.underlinedTabBackground()
            hovered && selectable -> JBUI.CurrentTheme.EditorTabs.hoverBackground()
            else -> null
        }
        if (background != null) {
            g.color = background
            g.fillRect(0, 0, width, height)
        }
        if (active) {
            val thickness = JBUI.CurrentTheme.EditorTabs.underlineHeight()
            g.color = JBUI.CurrentTheme.EditorTabs.underlineColor()
            g.fillRect(0, height - thickness, width, thickness)
        }
    }

    private fun tooltip(): String {
        val html = HtmlBuilder().append(HtmlChunk.text(worktree.label).bold()).br().append(worktree.path)
        if (worktree.isMain) html.br().append("Main checkout")
        if (worktree.isLocked) html.br().append("Locked" + (worktree.lockReason?.let { ": $it" } ?: ""))
        disabledReason?.let { html.br().append(HtmlChunk.text("Switching unavailable: $it").italic()) }
        return html.wrapWithHtmlBody().toString()
    }

    private companion object {
        const val TAB_HEIGHT = 28
    }
}
