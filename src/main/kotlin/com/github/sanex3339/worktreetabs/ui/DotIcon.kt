package com.github.sanex3339.worktreetabs.ui

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** A filled circle; drawn rather than typed (`●`) so it sits exactly at the vertical center of the tab. */
internal class DotIcon(private val color: Color, private val diameter: Int = JBUI.scale(7)) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, diameter, diameter)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth(): Int = diameter

    override fun getIconHeight(): Int = diameter
}
