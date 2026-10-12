package com.github.sanex3339.worktreetabs.switching

import com.github.sanex3339.worktreetabs.model.WorktreePaths
import com.github.sanex3339.worktreetabs.platform.PlatformInternals
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.SourceFolder
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitVcs
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.jps.model.JpsElement
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import java.nio.file.Path

private val LOG = logger<CoreSwap>()

/**
 * The swap the spike measured: refresh the target, move the module's content root, re-map Git, then move the project
 * root entity once nothing else references the old root. All-or-nothing: if re-mapping fails the content root goes back.
 */
internal object CoreSwap {
    suspend fun swap(project: Project, from: String, to: String, worktreePaths: List<String>) {
        val module = ModuleManager.getInstance(project).modules.single()
        val target = withContext(Dispatchers.IO) {
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(to)) ?: error("No such folder: $to")
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
            root
        }

        moveContentRoot(module, from, to, worktreePaths)
        try {
            remapVcs(project, from, target)
        } catch (e: Throwable) {
            LOG.warn("Re-mapping Git to $to failed; moving the content root back to $from", e)
            runCatching { moveContentRoot(module, to, from, worktreePaths) }.onFailure { LOG.warn("Rolling the content root back failed", it) }
            throw e
        }

        val fromUrl = VfsUtilCore.pathToUrl(from)
        // BaseProjectDirectories drops the old root only if, when the project root entity changes, no other entity
        // references it; WebStorm's package.json entities follow the content root a moment later (spike: 0.1–0.16 s).
        val deadline = System.currentTimeMillis() + RELEASE_TIMEOUT_MS
        while (PlatformInternals.isReferencedByOtherEntities(project, fromUrl) && System.currentTimeMillis() < deadline) delay(25)
        PlatformInternals.moveProjectRoot(project, fromUrl, Path.of(to))

        waitForRepository(project, target)
    }

    /** Puts the content root at [to], with [from]'s exclusions and source folders moved along and nested worktrees excluded. */
    fun moveContentRoot(module: Module, from: String, to: String, worktreePaths: List<String>) {
        val fromUrl = VfsUtilCore.pathToUrl(from)
        val toUrl = VfsUtilCore.pathToUrl(to)
        ModuleRootModificationUtil.updateModel(module) { model ->
            val old = model.contentEntries.singleOrNull { it.url == fromUrl } ?: model.contentEntries.single()
            val oldExcludes = old.excludeFolderUrls.map { FileUtil.toSystemIndependentName(VfsUtilCore.urlToPath(it)) }
            val entry = model.addContentEntry(toUrl)
            WorktreePaths.planExcludes(from, to, oldExcludes, worktreePaths).forEach { entry.addExcludeFolder(VfsUtilCore.pathToUrl(it)) }
            old.excludePatterns.forEach(entry::addExcludePattern)
            old.sourceFolders.forEach { copySourceFolder<JpsElement>(entry, it, from, to) }
            model.removeContentEntry(old)
        }
    }

    /** Adds exclusions for worktrees nested in the content root that are not excluded yet; false when nothing changed. */
    suspend fun excludeNestedWorktrees(module: Module, root: String, worktreePaths: List<String>): Boolean {
        val rootUrl = VfsUtilCore.pathToUrl(root)
        val nested = worktreePaths.filter { WorktreePaths.isStrictlyUnder(it, root) }.map(VfsUtilCore::pathToUrl)
        val missing = readAction {
            val entry = ModuleRootManager.getInstance(module).contentEntries.singleOrNull { it.url == rootUrl }
            entry?.let { nested - it.excludeFolderUrls.toSet() }.orEmpty()
        }
        if (missing.isEmpty()) return false
        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.singleOrNull { it.url == rootUrl } ?: return@updateModel
            missing.forEach(entry::addExcludeFolder)
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun <P : JpsElement> copySourceFolder(entry: ContentEntry, folder: SourceFolder, from: String, to: String) {
        val path = WorktreePaths.rebase(FileUtil.toSystemIndependentName(VfsUtilCore.urlToPath(folder.url)), from, to)
        val type = folder.rootType as JpsModuleSourceRootType<P>
        entry.addSourceFolder(VfsUtilCore.pathToUrl(path), type, folder.jpsElement.getProperties(type) as P)
    }

    /** Replaces the Git mapping of [from] (or the `<Project>` mapping, which does not follow a content-root move) with [target]. */
    private suspend fun remapVcs(project: Project, from: String, target: VirtualFile) = withContext(Dispatchers.EDT) {
        val manager = ProjectLevelVcsManager.getInstance(project)
        val kept = manager.directoryMappings.filterNot { it.vcs == GitVcs.NAME && (it.directory.isEmpty() || FileUtil.toSystemIndependentName(it.directory) == from) }
        manager.setDirectoryMappings(kept + VcsDirectoryMapping(target.path, GitVcs.NAME))
    }

    private suspend fun waitForRepository(project: Project, target: VirtualFile) {
        val manager = GitRepositoryManager.getInstance(project)
        val deadline = System.currentTimeMillis() + REPOSITORY_TIMEOUT_MS
        while (manager.repositories.none { it.root == target } && System.currentTimeMillis() < deadline) delay(25)
        if (manager.repositories.none { it.root == target }) LOG.warn("Git did not pick up ${target.path} within ${REPOSITORY_TIMEOUT_MS / 1000} s")
    }

    private const val RELEASE_TIMEOUT_MS = 10_000L
    private const val REPOSITORY_TIMEOUT_MS = 30_000L
}
