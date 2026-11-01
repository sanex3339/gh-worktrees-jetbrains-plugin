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
 * The swap the spike measured: refresh the target, re-map Git, move the module's content root, then move the project
 * root entity once nothing else references the old root. All-or-nothing: if moving the content root fails, Git is
 * mapped back.
 *
 * Git goes first, and the next step waits until git4idea has built the new repository: git4idea registers a repository
 * for disposal before its constructor finishes and lets the constructor be cancelled, so firing the root and mapping
 * changes back to back could cancel a repository mid-construction and leave a half-built one that fails when the
 * project closes.
 */
internal object CoreSwap {
    suspend fun swap(project: Project, from: String, to: String, worktreePaths: List<String>) {
        val module = ModuleManager.getInstance(project).modules.single()
        val target = withContext(Dispatchers.IO) {
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(to)) ?: error("No such folder: $to")
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
            root
        }

        val source = LocalFileSystem.getInstance().findFileByPath(from)
        remapVcs(project, worktreePaths, target)
        waitForRepository(project, target)
        try {
            moveContentRoot(module, from, to, worktreePaths)
        } catch (e: Throwable) {
            LOG.warn("Moving the content root to $to failed; mapping Git back to $from", e)
            if (source != null) runCatching { remapVcs(project, worktreePaths, source) }.onFailure { LOG.warn("Mapping Git back failed", it) }
            throw e
        }

        val fromUrl = VfsUtilCore.pathToUrl(from)
        // BaseProjectDirectories drops the old root only if, when the project root entity changes, no other entity
        // references it; WebStorm's package.json entities follow the content root a moment later (spike: 0.1–0.16 s).
        val deadline = System.currentTimeMillis() + RELEASE_TIMEOUT_MS
        while (PlatformInternals.isReferencedByOtherEntities(project, fromUrl) && System.currentTimeMillis() < deadline) delay(25)
        PlatformInternals.moveProjectRoot(project, fromUrl, Path.of(to))
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

    /**
     * Maps Git to [target] only: drops the `<Project>` mapping (it doesn't follow a content-root move, and the IDE
     * re-adds it for the main checkout) and every mapping of another worktree of the repository; keeps the rest.
     */
    suspend fun remapVcs(project: Project, worktreePaths: List<String>, target: VirtualFile) = withContext(Dispatchers.EDT) {
        val manager = ProjectLevelVcsManager.getInstance(project)
        val worktrees = worktreePaths.map(WorktreePaths::normalized).toSet()
        val kept = manager.directoryMappings.filterNot { it.vcs == GitVcs.NAME && (it.isDefaultMapping || WorktreePaths.normalized(it.directory) in worktrees) }
        manager.setDirectoryMappings(kept + VcsDirectoryMapping(target.path, GitVcs.NAME))
    }

    /** Whether Git is mapped to [active] alone (the `<Project>` mapping counts as the project's base path). */
    fun isGitMappedTo(project: Project, active: String): Boolean {
        val mappings = ProjectLevelVcsManager.getInstance(project).directoryMappings.filter { it.vcs == GitVcs.NAME }
        val mapped = mappings.singleOrNull()?.let { if (it.isDefaultMapping) project.basePath else it.directory } ?: return false
        return WorktreePaths.normalized(mapped) == WorktreePaths.normalized(active)
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
