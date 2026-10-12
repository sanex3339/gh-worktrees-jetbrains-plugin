package com.github.sanex3339.worktreetabs.platform

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorSplitterState
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.workspaceModel.ide.ProjectRootEntity
import com.intellij.workspaceModel.ide.registerProjectRoot
import com.intellij.workspaceModel.ide.unregisterProjectRoot
import kotlinx.coroutines.CancellationException
import org.jdom.Element
import java.nio.file.Path

private val LOG = logger<PlatformInternals>()

/**
 * Every use of internal platform API in the plugin. Each call fails safe: when the API is gone or throws, it logs and
 * returns a value that makes the caller fall back (or skip the step).
 */
object PlatformInternals {
    /**
     * Moves the workspace model's project root entity (internal API) from [fromUrl] to [to]. `BaseProjectDirectories`,
     * and with it `guessProjectDir()`, includes it next to the content roots, so without this the old worktree would
     * still be "the project directory". Does nothing when the project has no project root entity for [fromUrl].
     */
    suspend fun moveProjectRoot(project: Project, fromUrl: String, to: Path) {
        try {
            val stale = project.workspaceModel.currentSnapshot.entities(ProjectRootEntity::class.java).map { it.root }.filter { it.url == fromUrl }.toList()
            if (stale.isEmpty()) return
            stale.forEach { unregisterProjectRoot(project, it) }
            registerProjectRoot(project, to)
        } catch (e: LinkageError) {
            LOG.warn("Project root entity API unavailable; guessProjectDir() may keep returning the previous worktree", e)
        }
    }

    /** Whether any workspace entity other than the project root still references [url]. */
    fun isReferencedByOtherEntities(project: Project, url: String): Boolean = try {
        val model = project.workspaceModel
        val virtualFileUrl = model.getVirtualFileUrlManager().getOrCreateFromUrl(url)
        model.currentSnapshot.getVirtualFileUrlIndex().findEntitiesByUrl(virtualFileUrl).any { it !is ProjectRootEntity }
    } catch (e: LinkageError) {
        LOG.warn("Workspace model URL index unavailable", e)
        false
    }

    /** The main editor area's layout (tabs, splits, carets) as the IDE serializes it, or null when unavailable. */
    @RequiresEdt
    fun writeEditorLayout(project: Project): Element? = try {
        val splitters = (FileEditorManagerEx.getInstanceEx(project) as? FileEditorManagerImpl)?.mainSplitters
        splitters?.let { Element(LAYOUT).also(it::writeExternal) }
    } catch (e: LinkageError) {
        LOG.warn("Editor layout serialization unavailable", e)
        null
    }

    /** Restores a layout from [writeEditorLayout] (internal API); false when it could not, so the caller falls back. */
    suspend fun restoreEditorLayout(project: Project, layout: Element): Boolean = try {
        val splitters = (FileEditorManagerEx.getInstanceEx(project) as? FileEditorManagerImpl)?.mainSplitters
        if (splitters == null) {
            false
        } else {
            splitters.restoreEditors(EditorSplitterState(layout), requestFocus = false)
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        LOG.warn("Restoring the editor layout failed", e)
        false
    }

    const val LAYOUT = "layout"
}
