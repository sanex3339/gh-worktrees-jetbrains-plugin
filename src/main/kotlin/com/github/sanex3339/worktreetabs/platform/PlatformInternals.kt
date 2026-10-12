package com.github.sanex3339.worktreetabs.platform

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.workspaceModel.ide.ProjectRootEntity
import com.intellij.workspaceModel.ide.registerProjectRoot
import com.intellij.workspaceModel.ide.unregisterProjectRoot
import java.nio.file.Path

private val LOG = logger<PlatformInternals>()

/**
 * Every use of internal platform API in the plugin. Each call fails safe: when the API is gone or throws, it logs and
 * the switch goes on without that step.
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
}
