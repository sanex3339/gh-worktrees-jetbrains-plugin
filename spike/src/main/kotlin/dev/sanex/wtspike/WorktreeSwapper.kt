package dev.sanex.wtspike

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.workspaceModel.ide.ProjectRootEntity
import com.intellij.workspaceModel.ide.registerProjectRoot
import com.intellij.workspaceModel.ide.unregisterProjectRoot
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path

data class SwapResult(
    val from: String,
    val to: String,
    val startMs: Long,
    val refreshMs: Long,
    val modelMs: Long,
    val projectRootMs: Long,
    /** How long until no entity but the project root itself referenced the old root (null: still referenced after 10 s). */
    val oldRootReleasedMs: Long?,
    val vcsMappingMs: Long,
    /** From the start until every mutation (content root, project root, VCS mapping) was applied; no waiting included. */
    val mutationsMs: Long,
    /** From the VCS mapping call until git4idea has a repository for the target root. */
    val repositoryAfterMappingMs: Long?,
    val guessProjectDir: String?,
    /** From the start until `guessProjectDir()` returned the target (waits at most 3 s). */
    val guessProjectDirMs: Long?,
    val baseDirectoriesAfter: List<String>,
    /** Workspace entities that still point at the old root after the swap (they keep it in the base directories). */
    val oldRootReferences: List<String>,
    val projectRootsBefore: List<String>,
    val projectRootsAfter: List<String>,
    val mappingsBefore: List<String>,
    /** Repositories 2 s after the content-root swap, before the explicit mapping: does `<Project>` follow on its own? */
    val repositoriesBeforeExplicitMapping: List<String>?,
    val repositoriesAfter: List<String>,
    val branch: String?,
    val contentRootsAfter: List<String>,
    val excludesCarried: Int,
    val sourceFoldersDropped: Int,
)

/**
 * The in-place swap under test. The project's single module gets [to] as its content root instead of the current one
 * (exclusions carried over), the workspace model's project root moves along (it feeds `guessProjectDir()`), and the
 * Git mapping moves to [to]. Editors, run configs and the terminal are not touched; the spike measures the core.
 */
object WorktreeSwapper {
    suspend fun swap(project: Project, to: Path, observeDefaultMapping: Boolean = false, moveProjectRoot: Boolean = true): SwapResult {
        val start = System.currentTimeMillis()
        val module = ModuleManager.getInstance(project).modules.single()
        val fromUrl = ModuleRootManager.getInstance(module).contentRootUrls.single()
        val toUrl = VfsUtilCore.pathToUrl(to.toString())
        val mappingsBefore = ProjectLevelVcsManager.getInstance(project).directoryMappings.map { "${it.directory.ifEmpty { "<Project>" }} (${it.vcs})" }
        val projectRootsBefore = projectRoots(project)

        withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }

        var mark = System.currentTimeMillis()
        val target = withContext(Dispatchers.IO) {
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(to) ?: error("No such directory: $to")
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
            root
        }
        val refreshMs = System.currentTimeMillis() - mark

        mark = System.currentTimeMillis()
        var excludes = 0
        var droppedSources = 0
        ModuleRootModificationUtil.updateModel(module) { model ->
            val old = model.contentEntries.single()
            val entry = model.addContentEntry(toUrl)
            for (url in old.excludeFolderUrls) {
                entry.addExcludeFolder(rebase(url, fromUrl, toUrl))
                excludes++
            }
            old.excludePatterns.forEach(entry::addExcludePattern)
            droppedSources = old.sourceFolders.size
            model.removeContentEntry(old)
        }
        val modelMs = System.currentTimeMillis() - mark

        val gitManager = GitRepositoryManager.getInstance(project)
        val beforeExplicit = if (observeDefaultMapping) {
            delay(2_000)
            gitManager.repositories.map { it.root.path }
        } else {
            null
        }

        mark = System.currentTimeMillis()
        withContext(Dispatchers.EDT) {
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(listOf(VcsDirectoryMapping(target.path, "Git")))
        }
        val vcsMappingMs = System.currentTimeMillis() - mark
        val mappingCallAt = mark

        // BaseProjectDirectories drops the old root only if, at the moment the project root entity changes, no other
        // entity references it; WebStorm's package.json entities follow the content root asynchronously, so wait for them.
        mark = System.currentTimeMillis()
        val released = waitFor(10_000) { if (referencesTo(project, fromUrl).none { "ProjectRootEntity" !in it }) Unit else null }
        val oldRootReleasedMs = released?.let { System.currentTimeMillis() - mark }
        mark = System.currentTimeMillis()
        if (moveProjectRoot) {
            val stale = project.workspaceModel.currentSnapshot.entities(ProjectRootEntity::class.java).map { it.root }.filter { it.url == fromUrl }.toList()
            stale.forEach { unregisterProjectRoot(project, it) }
            registerProjectRoot(project, to)
        }
        val projectRootMs = System.currentTimeMillis() - mark
        val mutationsMs = System.currentTimeMillis() - start - (if (observeDefaultMapping) 2_000 else 0)

        val repository = waitFor(30_000) { gitManager.repositories.firstOrNull { it.root == target } }
        val repositoryAfterMappingMs = repository?.let { System.currentTimeMillis() - mappingCallAt }
        val guessed = waitFor(3_000) { project.guessProjectDir()?.takeIf { it == target } }
        val guessProjectDirMs = guessed?.let { System.currentTimeMillis() - start }

        return SwapResult(
            from = VfsUtilCore.urlToPath(fromUrl),
            to = target.path,
            startMs = start,
            refreshMs = refreshMs,
            modelMs = modelMs,
            projectRootMs = projectRootMs,
            oldRootReleasedMs = oldRootReleasedMs,
            vcsMappingMs = vcsMappingMs,
            mutationsMs = mutationsMs,
            repositoryAfterMappingMs = repositoryAfterMappingMs,
            guessProjectDir = project.guessProjectDir()?.path,
            guessProjectDirMs = guessProjectDirMs,
            baseDirectoriesAfter = project.getBaseDirectories().map { it.path },
            oldRootReferences = referencesTo(project, fromUrl),
            projectRootsBefore = projectRootsBefore,
            projectRootsAfter = projectRoots(project),
            mappingsBefore = mappingsBefore,
            repositoriesBeforeExplicitMapping = beforeExplicit,
            repositoriesAfter = gitManager.repositories.map { it.root.path },
            branch = repository?.currentBranchName,
            contentRootsAfter = ModuleRootManager.getInstance(module).contentRootUrls.map(VfsUtilCore::urlToPath),
            excludesCarried = excludes,
            sourceFoldersDropped = droppedSources,
        )
    }

    private fun referencesTo(project: Project, url: String): List<String> {
        val virtualFileUrl = project.workspaceModel.getVirtualFileUrlManager().getOrCreateFromUrl(url)
        return project.workspaceModel.currentSnapshot.getVirtualFileUrlIndex().findEntitiesByUrl(virtualFileUrl)
            .map { "${it.javaClass.name} (source ${it.entitySource.javaClass.name})" }
            .toList()
    }

    private fun projectRoots(project: Project): List<String> =
        project.workspaceModel.currentSnapshot.entities(ProjectRootEntity::class.java).map { it.root.presentableUrl }.toList()

    fun rebase(url: String, fromUrl: String, toUrl: String): String =
        if (url == fromUrl || url.startsWith("$fromUrl/")) toUrl + url.removePrefix(fromUrl) else url
}

suspend fun <T : Any> waitFor(timeoutMs: Long, probe: suspend () -> T?): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        probe()?.let { return it }
        if (System.currentTimeMillis() > deadline) return null
        delay(50)
    }
}
