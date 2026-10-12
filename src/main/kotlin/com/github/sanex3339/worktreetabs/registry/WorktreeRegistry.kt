package com.github.sanex3339.worktreetabs.registry

import com.github.sanex3339.worktreetabs.git.WorktreeGit
import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.model.WorktreeOrder
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.IdeFrame
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

sealed interface SwitchSupport {
    data object Supported : SwitchSupport

    data class Unsupported(val reason: String) : SwitchSupport
}

/**
 * The worktrees of the project's repository, in tab order, and the one the project shows now. [activePath] is the
 * module's content root; worktree paths that are the same folder as the project's base path use the base path's
 * spelling, so comparisons with what the IDE reports hold even across symlinks.
 */
data class WorktreesState(
    val worktrees: List<Worktree> = emptyList(),
    val activePath: String? = null,
    val support: SwitchSupport = SwitchSupport.Unsupported("Loading worktrees…"),
) {
    val active: Worktree? get() = worktrees.firstOrNull { it.path == activePath }
}

@Service(Service.Level.PROJECT)
class WorktreeRegistry(private val project: Project, scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(WorktreesState())
    val state: StateFlow<WorktreesState> = mutableState.asStateFlow()

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        @OptIn(FlowPreview::class)
        scope.launch(Dispatchers.IO) {
            requests.debounce(150).collect { mutableState.value = load() }
        }
        val connection = project.messageBus.connect(scope)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refresh() })
        connection.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener { refresh() })
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) = refresh()
        })
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) = refresh()
        })
        refresh()
    }

    /** Reloads the worktree list soon; bursts of requests collapse into one `git worktree list`. */
    fun refresh() {
        requests.tryEmit(Unit)
    }

    private suspend fun load(): WorktreesState {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        val repository = repositories.firstOrNull() ?: return WorktreesState(support = SwitchSupport.Unsupported("The project has no Git repository"))
        val listed = WorktreeGit.list(project, repository.root) ?: return state.value
        val basePath = project.basePath
        val worktrees = WorktreeOrder.forTabs(listed).map { it.copy(path = spelledLikeBasePath(it.path, basePath)) }

        val roots = readAction { projectRoots() }
        val activePath = roots.contentRoot?.let { root -> worktrees.firstOrNull { sameFolder(it.path, root) }?.path ?: root }
        val support = when {
            repositories.size > 1 -> SwitchSupport.Unsupported("The project has ${repositories.size} Git repositories")
            roots.moduleCount != 1 -> SwitchSupport.Unsupported("The project has ${roots.moduleCount} modules")
            roots.contentRootCount != 1 -> SwitchSupport.Unsupported("The project's module has ${roots.contentRootCount} content roots")
            roots.externalSystem != null -> SwitchSupport.Unsupported("The project is managed by ${roots.externalSystem}")
            worktrees.none { it.path == activePath } -> SwitchSupport.Unsupported("The project's folder is not a worktree of ${repository.root.name}")
            else -> SwitchSupport.Supported
        }
        return WorktreesState(worktrees, activePath, support)
    }

    private class ProjectRoots(val moduleCount: Int, val contentRootCount: Int, val contentRoot: String?, val externalSystem: String?)

    private fun projectRoots(): ProjectRoots {
        val modules = ModuleManager.getInstance(project).modules
        val module: Module? = modules.singleOrNull()
        val contentRoots = module?.let { ModuleRootManager.getInstance(it).contentRootUrls.toList() }.orEmpty()
        return ProjectRoots(
            moduleCount = modules.size,
            contentRootCount = contentRoots.size,
            contentRoot = contentRoots.singleOrNull()?.let { FileUtil.toSystemIndependentName(VfsUtilCore.urlToPath(it)) },
            externalSystem = module?.let { ExternalSystemModulePropertyManager.getInstance(it).getExternalSystemId() },
        )
    }

    private fun spelledLikeBasePath(path: String, basePath: String?): String =
        if (basePath != null && path != basePath && sameFolder(path, basePath)) basePath else path

    private fun sameFolder(a: String, b: String): Boolean = a == b || runCatching {
        val first = Path.of(a)
        val second = Path.of(b)
        Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second)
    }.getOrDefault(false)

    companion object {
        fun getInstance(project: Project): WorktreeRegistry = project.service()
    }
}
