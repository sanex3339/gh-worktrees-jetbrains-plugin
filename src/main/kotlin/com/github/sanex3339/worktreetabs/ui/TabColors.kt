package com.github.sanex3339.worktreetabs.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * Colors of the macOS-style tabs, light and dark. The strip itself is transparent, so the tab fills are translucent
 * and sit well on whatever the window shows behind them. (Not theme keys: themes' catch-all `*.background` rules would
 * match them.)
 */
internal object TabColors {
    val tab: Color = JBColor(Color(0, 0, 0, 14), Color(255, 255, 255, 16))
    val hover: Color = JBColor(Color(0, 0, 0, 26), Color(255, 255, 255, 30))
    val selected: Color = JBColor(Color(255, 255, 255, 235), Color(255, 255, 255, 40))
    val selectedBorder: Color = JBColor(Color(0, 0, 0, 40), Color(255, 255, 255, 46))
    val foreground: Color get() = UIUtil.getLabelForeground()
    val secondaryForeground: Color get() = UIUtil.getContextHelpForeground()
    val disabledForeground: Color get() = UIUtil.getLabelDisabledForeground()

    /** The sweep across a tab that is being switched to. */
    val loading: Color = JBColor(0x3574F0, 0x548AF7)
}
