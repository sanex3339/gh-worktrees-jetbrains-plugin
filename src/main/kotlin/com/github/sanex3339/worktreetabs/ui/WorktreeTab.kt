package com.github.sanex3339.worktreetabs.ui

import com.github.sanex3339.worktreetabs.actions.WorktreeDataKeys
import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.model.WorktreeStatus
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/** What a tab shows besides its worktree. */
internal data class TabState(
    val active: Boolean,
    val switching: Boolean,
    val indexed: Boolean,
    val status: WorktreeStatus?,
    val disabledReason: String?,
)

/**
 * One worktree tab: lock icon, label (dimmed until the worktree has been indexed), a dot for uncommitted changes and
 * ↑/↓ commits against upstream. Provides its worktree to the actions of its context menu.
 */
internal class WorktreeTab(
    val worktree: Worktree,
    private val state: TabState,
    onSelect: () -> Unit,
    contextMenu: MouseAdapter,
) : JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)), UiDataProvider {
    private var hovered = false
    private val selectable = !state.active && state.disabledReason == null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 10)
        val icon = when {
            state.switching -> AnimatedIcon.Default.INSTANCE
            worktree.isLocked -> AllIcons.Nodes.Locked
            else -> null
        }
        add(
            JBLabel(worktree.label, icon, JBLabel.LEADING).apply {
                font = JBUI.CurrentTheme.EditorTabs.font().let { if (!state.indexed && !state.active) it.deriveFont(Font.ITALIC) else it }
                foreground = when {
                    state.active -> JBUI.CurrentTheme.EditorTabs.underlinedTabForeground()
                    state.disabledReason != null || !state.indexed -> UIUtil.getLabelDisabledForeground()
                    else -> UIUtil.getLabelForeground()
                }
            },
        )
        if ((state.status?.changes ?: 0) > 0) {
            add(JBLabel(DotIcon(FileStatus.MODIFIED.color ?: UIUtil.getLabelForeground())))
        }
        state.status?.commitsBadge?.let { badge ->
            add(JBLabel(badge).apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() })
        }
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

    override fun uiDataSnapshot(sink: DataSink) {
        sink[WorktreeDataKeys.WORKTREE] = worktree
    }

    override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, JBUI.scale(TAB_HEIGHT))

    override fun doLayout() {
        super.doLayout()
        // Center the row of labels vertically in the tab.
        val content = components.maxOfOrNull { it.preferredSize.height } ?: return
        val top = (height - content) / 2
        for (child in components) child.setLocation(child.x, top + (content - child.height) / 2)
    }

    override fun paintComponent(g: Graphics) {
        val background = when {
            state.active -> JBUI.CurrentTheme.EditorTabs.underlinedTabBackground()
            hovered && selectable -> JBUI.CurrentTheme.EditorTabs.hoverBackground()
            else -> null
        }
        if (background != null) {
            g.color = background
            g.fillRect(0, 0, width, height)
        }
        if (state.active) {
            val thickness = JBUI.CurrentTheme.EditorTabs.underlineHeight()
            g.color = JBUI.CurrentTheme.EditorTabs.underlineColor()
            g.fillRect(0, height - thickness, width, thickness)
        }
    }

    private fun tooltip(): String {
        val html = HtmlBuilder().append(HtmlChunk.text(worktree.label).bold()).br().append(worktree.path)
        if (worktree.isMain) html.br().append("Main checkout")
        if (worktree.isLocked) html.br().append("Locked" + (worktree.lockReason?.let { ": $it" } ?: ""))
        state.status?.let { status ->
            if (status.changes > 0) html.br().append("${status.changes} uncommitted ${if (status.changes == 1) "file" else "files"}")
            val ahead = status.ahead ?: 0
            val behind = status.behind ?: 0
            if (status.upstream != null && (ahead > 0 || behind > 0)) html.br().append("$ahead ahead, $behind behind ${status.upstream}")
        }
        if (!state.indexed && !state.active) html.br().append(HtmlChunk.text("Not indexed yet: the first switch indexes the whole worktree").italic())
        state.disabledReason?.let { html.br().append(HtmlChunk.text("Switching unavailable: $it").italic()) }
        return html.wrapWithHtmlBody().toString()
    }

    private companion object {
        const val TAB_HEIGHT = 28
    }
}
