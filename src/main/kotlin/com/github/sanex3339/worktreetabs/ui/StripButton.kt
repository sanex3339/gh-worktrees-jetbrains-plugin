package com.github.sanex3339.worktreetabs.ui

import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent

/** A small square icon button at the end of the tab bar ("+", the worktree list), rounded like the tabs on hover. */
internal class StripButton(private val icon: Icon, tooltip: String, onClick: (MouseEvent) -> Unit) : JComponent() {
    private var hovered = false

    init {
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
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
                if (e.button == MouseEvent.BUTTON1 && contains(e.point)) onClick(e)
            }
        })
    }

    override fun getPreferredSize(): Dimension = JBUI.scale(WorktreeTab.TAB_HEIGHT).let { Dimension(it, it) }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            if (hovered) {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(9).toFloat()
                g2.color = TabColors.hover
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            }
            icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
        } finally {
            g2.dispose()
        }
    }
}
