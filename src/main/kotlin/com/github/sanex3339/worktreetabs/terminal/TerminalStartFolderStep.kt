package com.github.sanex3339.worktreetabs.terminal

import com.github.sanex3339.worktreetabs.model.WorktreePaths
import com.github.sanex3339.worktreetabs.switching.SwitchContext
import com.github.sanex3339.worktreetabs.switching.SwitchStep
import com.intellij.openapi.util.io.FileUtil
import org.jdom.Element
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider

/**
 * New terminals start in the project directory, which follows the switch on its own (the project root moves with it).
 * A start folder set by hand inside the old worktree moves to the same place in the new one. Running terminals stay.
 */
class TerminalStartFolderStep : SwitchStep {
    override val title = "terminal start folder"
    override val order = 210

    override suspend fun restore(context: SwitchContext, saved: Element?, carried: Element?) {
        val options = TerminalProjectOptionsProvider.getInstance(context.project)
        val folder = options.startingDirectory?.let(FileUtil::toSystemIndependentName) ?: return
        val moved = WorktreePaths.rebase(folder, context.from, context.to)
        if (moved != folder) options.startingDirectory = FileUtil.toSystemDependentName(moved)
    }
}
