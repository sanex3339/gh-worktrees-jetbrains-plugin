package com.github.sanex3339.worktreetabs.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingUtilities

/**
 * Lays tabs out left to right, each as wide as its content (but not narrower than a minimum, so short names don't
 * look cramped). In one row it scrolls sideways when they don't fit; with [wrap] they flow into as many rows as needed.
 */
internal class TabsPanel : JPanel(), Scrollable {
    var wrap: Boolean = false
        set(value) {
            field = value
            revalidate()
        }

    private val gap: Int get() = JBUI.scale(GAP)
    private val padding get() = JBUI.insets(VERTICAL_PADDING, HORIZONTAL_PADDING)
    private val rowHeight: Int get() = JBUI.scale(WorktreeTab.TAB_HEIGHT)

    init {
        isOpaque = false
        layout = TabsLayout()
    }

    /** The width the tabs are laid out in: the visible part when scrolling, the panel's own width when wrapping. */
    private fun availableWidth(): Int {
        val outer = (parent as? JViewport)?.width ?: width
        return (outer - padding.left - padding.right).coerceAtLeast(0)
    }

    private fun preferredWidths(): List<Int> = components.map { it.preferredSize.width.coerceAtLeast(JBUI.scale(MIN_TAB_WIDTH)) }

    private fun rows(): List<List<Int>> {
        val widths = preferredWidths()
        val available = availableWidth()
        return if (wrap && available > 0) TabWidths.rows(widths, available, gap) else listOf(widths.indices.toList())
    }

    private fun heightFor(rowCount: Int): Int =
        padding.top + padding.bottom + rowCount.coerceAtLeast(1) * rowHeight + (rowCount - 1).coerceAtLeast(0) * gap

    private inner class TabsLayout : LayoutManager {
        override fun preferredLayoutSize(parent: Container): Dimension {
            val widths = preferredWidths()
            val natural = padding.left + padding.right + widths.sum() + gap * (widths.size - 1).coerceAtLeast(0)
            return if (wrap) Dimension(natural.coerceAtMost(availableWidth() + padding.left + padding.right).coerceAtLeast(0), heightFor(rows().size))
            else Dimension(natural, heightFor(1))
        }

        override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, heightFor(1))

        override fun layoutContainer(parent: Container) {
            val tabs: List<Component> = components.toList()
            val widths = preferredWidths()
            val rows = rows()
            var y = padding.top
            for (row in rows) {
                var x = padding.left
                for (index in row) {
                    tabs[index].setBounds(x, y, widths[index], rowHeight)
                    x += widths[index] + gap
                }
                y += rowHeight + gap
            }
            // Wrapping depends on the width, which is only known now; let the strip take the height the rows need.
            if (wrap && height != heightFor(rows.size)) SwingUtilities.invokeLater { revalidate() }
        }

        override fun addLayoutComponent(name: String?, comp: Component?) = Unit

        override fun removeLayoutComponent(comp: Component?) = Unit
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(40)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.width

    /** Takes the viewport's width while the tabs fit in it; scrolls once they don't. */
    override fun getScrollableTracksViewportWidth(): Boolean = (parent as? JViewport)?.let { preferredSize.width <= it.width } ?: true

    override fun getScrollableTracksViewportHeight(): Boolean = true

    companion object {
        private const val GAP = 4
        /** Space above the first row (and below the last); the strip's buttons line up with the first row by it. */
        const val VERTICAL_PADDING = 4
        private const val HORIZONTAL_PADDING = 6
        private const val MIN_TAB_WIDTH = 96
    }
}
