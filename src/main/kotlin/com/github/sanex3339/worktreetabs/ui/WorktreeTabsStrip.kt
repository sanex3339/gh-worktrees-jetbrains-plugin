package com.github.sanex3339.worktreetabs.ui

import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.registry.SwitchSupport
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.registry.WorktreesState
import com.github.sanex3339.worktreetabs.switching.WorktreeSwitcher
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

class WorktreeTabsNorthExtension : IdeRootPaneNorthExtension {
    override val key: String = "WorktreeTabs"

    override fun createComponent(project: Project, isDocked: Boolean): JComponent? = if (isDocked) null else WorktreeTabsStrip(project)
}

/** Owns the coroutines of the strips shown in this project's window; they end with the project. */
@Service(Service.Level.PROJECT)
class WorktreeTabsUiScope(val scope: CoroutineScope)

/** The full-width row of worktree tabs under the main toolbar. Hidden in projects without a Git repository. */
class WorktreeTabsStrip(private val project: Project) : JPanel(BorderLayout()) {
    private val tabs = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply { isOpaque = false }
    private var updates: Job? = null

    init {
        isVisible = false
        border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.EditorTabs.borderColor())
        add(tabs, BorderLayout.CENTER)
    }

    override fun addNotify() {
        super.addNotify()
        val registry = WorktreeRegistry.getInstance(project)
        val switcher = WorktreeSwitcher.getInstance(project)
        updates = project.service<WorktreeTabsUiScope>().scope.launch(Dispatchers.EDT) {
            combine(registry.state, switcher.switchingTo) { state, switching -> state to switching }.collect { (state, switching) -> render(state, switching) }
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

    private fun render(state: WorktreesState, switching: String?) {
        isVisible = state.worktrees.isNotEmpty()
        tabs.removeAll()
        val unsupported = (state.support as? SwitchSupport.Unsupported)?.reason
        for (worktree in state.worktrees) {
            tabs.add(
                WorktreeTab(
                    worktree = worktree,
                    active = worktree.path == state.activePath,
                    switching = worktree.path == switching,
                    disabledReason = unsupported,
                    onSelect = { WorktreeSwitcher.getInstance(project).switchTo(worktree.path) },
                ),
            )
        }
        revalidate()
        repaint()
    }
}

private class WorktreeTab(
    private val worktree: Worktree,
    private val active: Boolean,
    switching: Boolean,
    private val disabledReason: String?,
    onSelect: () -> Unit,
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
                if (selectable && e.button == MouseEvent.BUTTON1 && contains(e.point)) onSelect()
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
