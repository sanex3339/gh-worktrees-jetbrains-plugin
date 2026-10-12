package com.github.sanex3339.worktreetabs.switching

import com.github.sanex3339.worktreetabs.model.Worktree
import com.github.sanex3339.worktreetabs.registry.SwitchSupport
import com.github.sanex3339.worktreetabs.registry.WorktreeIndexHistory
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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jdom.Element
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
        scope.launch {
            WorktreeRegistry.getInstance(project).state.map { it.activePath }.distinctUntilChanged().collectLatest { active ->
                if (active != null && awaitIndexed()) WorktreeIndexHistory.getInstance().markIndexed(active)
            }
        }
    }

    /**
     * True once the project has been smart for a few seconds in a row: indexing of a just-activated worktree starts a
     * moment after the switch, so a single smart check could come too early.
     */
    private suspend fun awaitIndexed(): Boolean {
        val dumb = DumbService.getInstance(project)
        var smartSince: Long? = null
        while (!project.isDisposed) {
            val now = System.currentTimeMillis()
            if (dumb.isDumb) smartSince = null else if (smartSince == null) smartSince = now
            if (smartSince != null && now - smartSince >= INDEXED_QUIET_MS) return true
            delay(250)
        }
        return false
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
                project.isDisposed || registry.isClosing || from == null || worktree == null || target == from -> return
                state.support is SwitchSupport.Unsupported -> WorktreeNotifications.warn(project, "Can't switch worktrees: ${state.support.reason}")
                !Files.isDirectory(Path.of(target)) -> WorktreeNotifications.warn(project, "The worktree folder $target no longer exists")
                else -> withBackgroundProgress(project, "Switching to ${worktree.label}…") {
                    run(SwitchContext(project, from, target, state.worktrees.map { it.path }), worktree)
                }
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

    private suspend fun run(context: SwitchContext, worktree: Worktree) {
        val started = System.currentTimeMillis()
        val timings = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val steps = SwitchStep.EP_NAME.extensionList.sortedBy { it.order }
        val store = WorktreeStateStore.getInstance(project)

        val carried = mutableMapOf<SwitchStep, Element>()
        for (step in steps) {
            timed(timings, "capture ${step.title}") {
                runStep(step, failed) { step.capture(context) }?.let { snapshot ->
                    carried[step] = snapshot
                    step.snapshotKey?.let { store.put(context.from, it, snapshot) }
                }
            }
        }
        timed(timings, "save and close editors") {
            withContext(Dispatchers.EDT) {
                FileDocumentManager.getInstance().saveAllDocuments()
                FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            }
        }

        if (project.isDisposed || WorktreeRegistry.getInstance(project).isClosing) return
        timed(timings, "swap") { CoreSwap.swap(project, context.from, context.to, context.worktreePaths) }

        for (step in steps) {
            val saved = step.snapshotKey?.let { store.get(context.to, it) }
            timed(timings, "restore ${step.title}") { runStep(step, failed) { step.restore(context, saved, carried[step]) } }
        }

        LOG.info("Switched ${context.from} -> ${context.to} in ${System.currentTimeMillis() - started} ms (${timings.joinToString()})")
        if (failed.isNotEmpty()) WorktreeNotifications.warn(project, "Switched to ${worktree.label}, but couldn't move: ${failed.distinct().joinToString()}")
    }

    private suspend fun <T> runStep(step: SwitchStep, failed: MutableList<String>, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        LOG.warn("Worktree switch step '${step.title}' failed", e)
        failed += step.title
        null
    }

    private suspend fun <T> timed(timings: MutableList<String>, label: String, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        try {
            return block()
        } finally {
            timings += "$label ${System.currentTimeMillis() - start} ms"
        }
    }

    /** Worktrees inside the active one (e.g. `.claude/worktrees/`) would be indexed twice; keep them excluded. */
    private suspend fun keepNestedWorktreesExcluded(state: WorktreesState) {
        val active = state.activePath ?: return
        if (state.support !is SwitchSupport.Supported || switchingTo.value != null || project.isDisposed || WorktreeRegistry.getInstance(project).isClosing) return
        val module = readAction { ModuleManager.getInstance(project).modules.singleOrNull() } ?: return
        CoreSwap.excludeNestedWorktrees(module, active, state.worktrees.map { it.path })
    }

    companion object {
        private const val INDEXED_QUIET_MS = 5_000L

        fun getInstance(project: Project): WorktreeSwitcher = project.service()
    }
}
