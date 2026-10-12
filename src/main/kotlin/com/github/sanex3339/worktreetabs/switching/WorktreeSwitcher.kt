package com.github.sanex3339.worktreetabs.switching

import com.github.sanex3339.worktreetabs.registry.SwitchSupport
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.registry.WorktreesState
import com.github.sanex3339.worktreetabs.ui.WorktreeNotifications
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private val LOG = logger<WorktreeSwitcher>()

/**
 * Switches the project between worktrees, one switch at a time. A request made while a switch runs replaces any
 * request still waiting, so only the newest target is switched to next.
 */
@Service(Service.Level.PROJECT)
class WorktreeSwitcher(private val project: Project, scope: CoroutineScope) {
    private val requests = Channel<String>(Channel.CONFLATED)
    private val mutableSwitchingTo = MutableStateFlow<String?>(null)

    /** The worktree a switch is running or queued for, or null when idle. */
    val switchingTo: StateFlow<String?> = mutableSwitchingTo.asStateFlow()

    init {
        scope.launch {
            for (target in requests) switch(target)
        }
        scope.launch {
            WorktreeRegistry.getInstance(project).state.collect { keepNestedWorktreesExcluded(it) }
        }
    }

    fun switchTo(path: String) {
        mutableSwitchingTo.value = path
        requests.trySend(path)
    }

    private suspend fun switch(target: String) {
        val registry = WorktreeRegistry.getInstance(project)
        val state = registry.state.value
        val from = state.activePath
        val worktree = state.worktrees.firstOrNull { it.path == target }
        try {
            when {
                from == null || worktree == null || target == from -> return
                state.support is SwitchSupport.Unsupported -> {
                    WorktreeNotifications.warn(project, "Can't switch worktrees: ${state.support.reason}")
                    return
                }
                !Files.isDirectory(Path.of(target)) -> {
                    WorktreeNotifications.warn(project, "The worktree folder $target no longer exists")
                    return
                }
            }
            withBackgroundProgress(project, "Switching to ${worktree!!.label}…") {
                val editors = withContext(Dispatchers.EDT) {
                    val captured = EditorCarryOver.capture(project, from!!)
                    FileDocumentManager.getInstance().saveAllDocuments()
                    FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
                    captured
                }
                CoreSwap.swap(project, from!!, target, state.worktrees.map { it.path })
                withContext(Dispatchers.EDT) { EditorCarryOver.restore(project, target, editors) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Switching to $target failed", e)
            WorktreeNotifications.error(project, "Couldn't switch to ${worktree?.label ?: target}: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            mutableSwitchingTo.compareAndSet(target, null)
            registry.refresh()
        }
    }

    /** Worktrees inside the active one (e.g. `.claude/worktrees/`) would be indexed twice; keep them excluded. */
    private suspend fun keepNestedWorktreesExcluded(state: WorktreesState) {
        val active = state.activePath ?: return
        if (state.support !is SwitchSupport.Supported || switchingTo.value != null) return
        val module = readAction { ModuleManager.getInstance(project).modules.singleOrNull() } ?: return
        CoreSwap.excludeNestedWorktrees(module, active, state.worktrees.map { it.path })
    }

    companion object {
        fun getInstance(project: Project): WorktreeSwitcher = project.service()
    }
}
