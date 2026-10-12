package com.github.sanex3339.worktreetabs.switching

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import org.jdom.Element

/** A switch from the worktree at [from] to the one at [to]; paths are system-independent. */
data class SwitchContext(val project: Project, val from: String, val to: String, val worktreePaths: List<String>) {
    val fromUrl: String get() = VfsUtilCore.pathToUrl(from)
    val toUrl: String get() = VfsUtilCore.pathToUrl(to)
}

/**
 * One kind of state that follows a switch (editor tabs, run configurations, …). Steps run in ascending [order]:
 * every [capture] while the old worktree is still active, then, after the swap, every [restore]. A failing step is
 * reported and skipped; it never stops the switch.
 */
interface SwitchStep {
    /** What the step moves, for logs and the "couldn't restore" notification. */
    val title: String

    val order: Int

    /** Key of the snapshot the step keeps per worktree, or null when it keeps none. */
    val snapshotKey: String? get() = null

    /** With [SwitchContext.from] still active: what belongs to it, stored under its path. */
    suspend fun capture(context: SwitchContext): Element? = null

    /**
     * With [SwitchContext.to] active. [saved] is the snapshot stored for `to` (null on its first visit), [carried] what
     * [capture] just returned for `from` (to carry over on a first visit).
     */
    suspend fun restore(context: SwitchContext, saved: Element?, carried: Element?) = Unit

    companion object {
        val EP_NAME = ExtensionPointName.create<SwitchStep>("com.github.sanex3339.worktreetabs.switchStep")
    }
}
