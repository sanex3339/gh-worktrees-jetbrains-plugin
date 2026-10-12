package com.github.sanex3339.worktreetabs.registry

import com.github.sanex3339.worktreetabs.git.WorktreeGit
import com.github.sanex3339.worktreetabs.model.WorktreeStatus
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.wm.IdeFrame
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Badges for every worktree: uncommitted files and commits against upstream, from `git status` in each worktree.
 * Refreshed when the worktree list or Git state changes, when the IDE gains focus, and every minute while it has it
 * (agents change inactive worktrees without the IDE noticing).
 */
@Service(Service.Level.PROJECT)
class WorktreeStatusService(private val project: Project, scope: CoroutineScope) {
    private val mutableStatuses = MutableStateFlow<Map<String, WorktreeStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, WorktreeStatus>> = mutableStatuses.asStateFlow()

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val parallelGit = Semaphore(PARALLEL_GIT)

    @Volatile
    private var appActive = true

    init {
        @OptIn(FlowPreview::class)
        scope.launch(Dispatchers.IO) {
            requests.debounce(DEBOUNCE_MS).collect { reload() }
        }
        scope.launch {
            WorktreeRegistry.getInstance(project).state.collect { refresh() }
        }
        scope.launch {
            while (true) {
                delay(PERIOD_MS)
                if (appActive) refresh()
            }
        }
        val connection = project.messageBus.connect(scope)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refresh() })
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = refresh()
        })
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) {
                appActive = true
                refresh()
            }

            override fun applicationDeactivated(ideFrame: IdeFrame) {
                appActive = false
            }
        })
    }

    fun refresh() {
        requests.tryEmit(Unit)
    }

    private suspend fun reload() {
        val registry = WorktreeRegistry.getInstance(project)
        if (registry.isClosing || project.isDisposed) return
        // A worktree removed a moment ago can still be listed until the registry reloads; git can't run in a missing folder.
        val commands = com.github.sanex3339.worktreetabs.commands.WorktreeCommands.getInstance(project)
        val worktrees = registry.state.value.worktrees.filter { !commands.isBeingRemoved(it.path) && java.nio.file.Files.isDirectory(java.nio.file.Path.of(it.path)) }
        val loaded = kotlinx.coroutines.coroutineScope {
            worktrees.map { worktree ->
                async { parallelGit.withPermit { worktree.path to WorktreeGit.status(project, worktree.path) } }
            }.awaitAll()
        }
        mutableStatuses.value = loaded.mapNotNull { (path, status) -> status?.let { path to it } }.toMap()
    }

    companion object {
        private const val PARALLEL_GIT = 4
        private const val DEBOUNCE_MS = 1_000L
        private const val PERIOD_MS = 60_000L

        fun getInstance(project: Project): WorktreeStatusService = project.service()
    }
}
