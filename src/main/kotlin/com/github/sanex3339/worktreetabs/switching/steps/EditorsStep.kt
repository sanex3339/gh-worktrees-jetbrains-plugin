package com.github.sanex3339.worktreetabs.switching.steps

import com.github.sanex3339.worktreetabs.model.EditorLayoutRewrite
import com.github.sanex3339.worktreetabs.platform.PlatformInternals
import com.github.sanex3339.worktreetabs.switching.EditorCarryOver
import com.github.sanex3339.worktreetabs.switching.SwitchContext
import com.github.sanex3339.worktreetabs.switching.SwitchStep
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element

/** Each worktree keeps its own editor tabs, splits and caret positions; a first visit carries the current ones over. */
class EditorsStep : SwitchStep {
    override val title = "editor tabs"
    override val order = 100
    override val snapshotKey = "editors"

    override suspend fun capture(context: SwitchContext): Element? = withContext(Dispatchers.EDT) {
        PlatformInternals.writeEditorLayout(context.project)
    }

    override suspend fun restore(context: SwitchContext, saved: Element?, carried: Element?) {
        val layout = saved ?: carried?.let { EditorLayoutRewrite.moveToWorktree(it, context.fromUrl, context.toUrl) } ?: return
        if (!PlatformInternals.restoreEditorLayout(context.project, layout)) {
            withContext(Dispatchers.EDT) { EditorCarryOver.openFilesOf(context.project, layout) }
        }
    }
}
