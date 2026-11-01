package com.github.sanex3339.worktreetabs.ui

import com.github.sanex3339.worktreetabs.actions.WorktreeDataKeys
import com.github.sanex3339.worktreetabs.model.RelativeTime
import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.model.WorktreeStatus
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager

/** What a tab shows besides its worktree. */
internal data class TabState(
    val active: Boolean,
    val switching: Boolean,
    val indexed: Boolean,
    val status: WorktreeStatus?,
    val disabledReason: String?,
)

/**
 * One worktree tab, drawn like a macOS window tab: a rounded rectangle with the label, in the IDE's
 * "modified" color when the worktree has uncommitted changes, then ↑/↓ commits against upstream. While a switch to it
 * runs, a highlight sweeps across it (the tab keeps its size, so nothing next to it moves). Provides its worktree to
 * the actions of its context menu.
 *
 * The strip keeps one tab per worktree and [update]s it in place, so hovering and clicking survive the frequent status
 * refreshes; a click switches on press, like the IDE's editor tabs.
 */
internal class WorktreeTab(
    worktree: Worktree,
    state: TabState,
    onSelect: (Worktree) -> Unit,
    contextMenu: MouseAdapter,
) : JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(5), 0)), UiDataProvider {
    var worktree: Worktree = worktree
        private set
    private var state: TabState = state
    private var hovered = false
    // Ide.Readonly is the centered padlock in the new UI; Nodes.Locked is an overlay drawn in its bottom corner.
    private val lock = JBLabel(AllIcons.Ide.Readonly)
    private val name = JBLabel()
    private val commits = JBLabel().apply { font = JBFont.small() }
    private val animation = Timer(1000 / FRAMES_PER_SECOND) { repaint() }

    private val selectable: Boolean get() = !state.active && state.disabledReason == null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, HORIZONTAL_PADDING)
        add(lock)
        add(name)
        add(commits)
        // Built on each hover (getToolTipText), so "last edited 5 minutes ago" is never out of date.
        ToolTipManager.sharedInstance().registerComponent(this)
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

            override fun mousePressed(e: MouseEvent) {
                if (selectable && SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger) onSelect(this@WorktreeTab.worktree)
            }
        })
        apply()
    }

    fun update(worktree: Worktree, state: TabState) {
        if (worktree == this.worktree && state == this.state) return
        this.worktree = worktree
        this.state = state
        apply()
    }

    private fun apply() {
        lock.isVisible = worktree.isLocked
        name.text = worktree.label
        name.font = JBFont.label().let { if (!state.indexed && !state.active) it.deriveFont(Font.ITALIC) else it }
        // Grey only when switching is unavailable; "not indexed yet" is italic, not dimmed, so every tab reads as clickable.
        name.foreground = when {
            state.disabledReason != null -> TabColors.disabledForeground
            (state.status?.changes ?: 0) > 0 -> FileStatus.MODIFIED.color ?: TabColors.foreground
            else -> TabColors.foreground
        }
        val badge = state.status?.commitsBadge
        commits.isVisible = badge != null
        commits.text = badge.orEmpty()
        commits.foreground = TabColors.secondaryForeground
        cursor = if (selectable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        if (state.switching && isDisplayable) animation.start() else animation.stop()
        revalidate()
        repaint()
    }

    override fun addNotify() {
        super.addNotify()
        if (state.switching) animation.start()
    }

    override fun removeNotify() {
        animation.stop()
        super.removeNotify()
    }

    override fun getToolTipText(event: MouseEvent?): String = tooltip()

    override fun uiDataSnapshot(sink: DataSink) {
        sink[WorktreeDataKeys.WORKTREE] = worktree
    }

    override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, JBUI.scale(TAB_HEIGHT))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun doLayout() {
        super.doLayout()
        // Center the row of labels vertically in the tab.
        val visible = components.filter { it.isVisible }
        val content = visible.maxOfOrNull { it.preferredSize.height } ?: return
        val top = (height - content) / 2
        for (child in visible) child.setLocation(child.x, top + (content - child.height) / 2)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(ARC).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
            g2.color = when {
                state.active -> TabColors.selected
                hovered && selectable -> TabColors.hover
                else -> TabColors.tab
            }
            g2.fill(shape)
            if (state.switching) paintSweep(g2, shape)
            if (state.active) {
                g2.color = TabColors.selectedBorder
                g2.draw(shape)
            }
        } finally {
            g2.dispose()
        }
    }

    /** A soft band of the accent color that crosses the tab once per period, clipped to its rounded shape. */
    private fun paintSweep(g2: Graphics2D, shape: RoundRectangle2D) {
        val phase = (System.currentTimeMillis() % SWEEP_PERIOD_MS).toFloat() / SWEEP_PERIOD_MS
        val band = width * 0.6f
        val start = -band + phase * (width + band)
        val clear = ColorUtil.withAlpha(TabColors.loading, 0.0)
        val strong = ColorUtil.withAlpha(TabColors.loading, 0.35)
        val clip = g2.clip
        g2.clip(shape)
        g2.paint = LinearGradientPaint(start, 0f, start + band, 0f, floatArrayOf(0f, 0.5f, 1f), arrayOf(clear, strong, clear))
        g2.fillRect(0, 0, width, height)
        g2.clip = clip
    }

    private fun tooltip(): String {
        val html = HtmlBuilder().append(HtmlChunk.text(worktree.label).bold()).br().append(worktree.path)
        if (worktree.isMain) html.br().append("Main checkout")
        if (worktree.isLocked) html.br().append("Locked" + (worktree.lockReason?.let { ": $it" } ?: ""))
        state.status?.let { status ->
            if (status.changes > 0) {
                val files = "${status.changes} uncommitted ${if (status.changes == 1) "file" else "files"}"
                val edited = status.lastEdited?.let { ", last edited ${RelativeTime.ago(it, System.currentTimeMillis())}" }.orEmpty()
                html.br().append(files + edited)
            }
            val ahead = status.ahead ?: 0
            val behind = status.behind ?: 0
            if (status.upstream != null && (ahead > 0 || behind > 0)) html.br().append("$ahead ahead, $behind behind ${status.upstream}")
        }
        if (!state.indexed && !state.active) html.br().append(HtmlChunk.text("Not indexed yet: the first switch indexes the whole worktree").italic())
        state.disabledReason?.let { html.br().append(HtmlChunk.text("Switching unavailable: $it").italic()) }
        return html.wrapWithHtmlBody().toString()
    }

    companion object {
        const val TAB_HEIGHT = 26
        private const val HORIZONTAL_PADDING = 14
        private const val ARC = 9
        private const val FRAMES_PER_SECOND = 40
        private const val SWEEP_PERIOD_MS = 1_200L
    }
}
