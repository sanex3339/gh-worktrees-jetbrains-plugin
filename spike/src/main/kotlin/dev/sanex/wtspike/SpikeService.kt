package dev.sanex.wtspike

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<SpikeService>()

@Service(Service.Level.PROJECT)
class SpikeService(private val project: Project, private val scope: CoroutineScope) {
    private val busy = AtomicBoolean()

    fun runExperiment(target: Path, report: Path, exitWhenDone: Boolean) = launchExclusive {
        try {
            val written = Experiment(project, Path.of(project.basePath!!), target).run(report)
            notify(project, "Worktree spike finished: $written")
        } finally {
            if (exitWhenDone) withContext(Dispatchers.EDT) { ApplicationManager.getApplication().exit(true, true, false) }
        }
    }

    fun swapTo(target: Path) = launchExclusive {
        val swap = WorktreeSwapper.swap(project, target)
        val settledAt = awaitSettled(project, since = swap.startMs)
        val recorder = IndexingRecorder.of(project)
        val scans = recorder.scans.filter { it.startMs >= swap.startMs }
        notify(
            project,
            "Swapped to $target: swap ${swap.mutationsMs} ms, Git ready ${swap.repositoryAfterMappingMs} ms after mapping, " +
                "settled ${settledAt?.let { "${it - swap.startMs} ms" } ?: "timeout"}, " +
                "scanned ${scans.sumOf { it.scannedFiles }}, sent to indexing ${scans.sumOf { it.filesForIndexing }}",
        )
    }

    private fun launchExclusive(block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            notify(project, "A worktree swap or experiment is already running")
            return
        }
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.warn("wtspike failed", e)
                notify(project, "Worktree spike failed: ${e.message}", NotificationType.ERROR)
            } finally {
                busy.set(false)
            }
        }
    }
}

class SpikeStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        IndexingRecorder.of(project)
        if (System.getProperty("wtspike.autorun") != "true") return
        val target = System.getProperty("wtspike.target")
        if (target == null) {
            LOG.warn("wtspike.autorun is set but wtspike.target is not")
            return
        }
        project.service<SpikeService>().runExperiment(Path.of(target), reportPath(project), System.getProperty("wtspike.exitWhenDone") == "true")
    }
}

class RunExperimentAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = System.getProperty("wtspike.target")?.let(Path::of) ?: otherWorktrees(project).firstOrNull()
        if (target == null) {
            notify(project, "No other worktree found", NotificationType.WARNING)
            return
        }
        project.service<SpikeService>().runExperiment(target, reportPath(project), exitWhenDone = false)
    }
}

class SwapToWorktreeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val targets = otherWorktrees(project).map(Path::toString)
        if (targets.isEmpty()) {
            notify(project, "No other worktree found", NotificationType.WARNING)
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(targets)
            .setTitle("Swap to Worktree")
            .setItemChosenCallback { project.service<SpikeService>().swapTo(Path.of(it)) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}

/** The main checkout and every worktree git4idea knows, except the one the project shows now. */
private fun otherWorktrees(project: Project): List<Path> {
    val current = ModuleManager.getInstance(project).modules.singleOrNull()
        ?.let { ModuleRootManager.getInstance(it).contentRootUrls.singleOrNull() }
        ?.let { Path.of(VfsUtilCore.urlToPath(it)) }
    val fromGit = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        ?.workingTreeHolder?.getWorkingTrees().orEmpty()
        .map { Path.of(it.path.path) }
    return (listOfNotNull(project.basePath?.let(Path::of)) + fromGit).distinct().filter { it != current }
}

private fun reportPath(project: Project): Path =
    System.getProperty("wtspike.report")?.let(Path::of) ?: Path.of(project.basePath!!).resolveSibling("wtspike-report.md")

private fun notify(project: Project, text: String, type: NotificationType = NotificationType.INFORMATION) {
    NotificationGroupManager.getInstance().getNotificationGroup("Worktree Spike").createNotification(text, type).notify(project)
}
