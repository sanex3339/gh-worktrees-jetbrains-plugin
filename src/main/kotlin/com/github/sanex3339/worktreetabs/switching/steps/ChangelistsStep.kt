package com.github.sanex3339.worktreetabs.switching.steps

import com.github.sanex3339.worktreetabs.model.WorktreePaths
import com.github.sanex3339.worktreetabs.switching.SwitchContext
import com.github.sanex3339.worktreetabs.switching.SwitchStep
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jdom.Element
import kotlin.coroutines.resume

private val LOG = logger<ChangelistsStep>()

/**
 * Each worktree keeps its changelists: which file is in which list, each list's comment (the commit message draft)
 * and the active list. Lists left empty by the worktree that went away are removed (they live on in its snapshot).
 */
class ChangelistsStep : SwitchStep {
    override val title = "changelists"
    override val order = 130
    override val snapshotKey = "changelists"

    override suspend fun capture(context: SwitchContext): Element? = withContext(Dispatchers.EDT) {
        val snapshot = Element(ROOT)
        for (list in ChangeListManager.getInstance(context.project).changeLists) {
            val element = Element("list").setAttribute("name", list.name).setAttribute("default", list.isDefault.toString())
            list.comment?.takeIf { it.isNotEmpty() }?.let { element.setAttribute("comment", it) }
            for (change in list.changes) {
                val path = change.path ?: continue
                if (WorktreePaths.isStrictlyUnder(path, context.from)) element.addContent(Element("file").setAttribute("path", path.removePrefix("${context.from}/")))
            }
            snapshot.addContent(element)
        }
        snapshot
    }

    /** Git reports the new worktree's changes only after a refresh; the lists are rebuilt once it is done, in the background. */
    override suspend fun restore(context: SwitchContext, saved: Element?, carried: Element?) {
        context.project.service<ChangelistRestorer>().restoreAfterRefresh(context, saved)
    }

    private companion object {
        const val ROOT = "changelists"
    }
}

@Service(Service.Level.PROJECT)
class ChangelistRestorer(private val project: Project, private val scope: CoroutineScope) {
    fun restoreAfterRefresh(context: SwitchContext, saved: Element?) {
        scope.launch {
            val manager = ChangeListManager.getInstance(project)
            val refreshed = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation -> manager.invokeAfterUpdate(false) { continuation.resume(Unit) } }
            }
            if (refreshed == null) LOG.warn("Changes of ${context.to} were not refreshed within ${REFRESH_TIMEOUT_MS / 1000} s; restoring changelists anyway")
            withContext(Dispatchers.EDT) { apply(manager, context.to, saved) }
        }
    }

    private fun apply(manager: ChangeListManager, worktree: String, saved: Element?) {
        val lists = saved?.getChildren("list").orEmpty()
        for (element in lists) {
            val name = element.getAttributeValue("name") ?: continue
            val list = manager.findChangeList(name) ?: manager.addChangeList(name, element.getAttributeValue("comment"))
            element.getAttributeValue("comment")?.let { manager.editComment(name, it) }
            val changes = element.getChildren("file").mapNotNull { file ->
                LocalFileSystem.getInstance().findFileByPath("$worktree/${file.getAttributeValue("path")}")?.let(manager::getChange)
            }
            if (changes.isNotEmpty()) manager.moveChangesTo(list, changes)
        }
        val active = lists.firstOrNull { it.getAttributeValue("default") == "true" }?.getAttributeValue("name")
        val current = manager.defaultChangeList
        if (active != null) {
            manager.setDefaultChangeList(active)
        } else if (current.changes.isEmpty() && current.name != LocalChangeList.getDefaultName()) {
            // First visit: the active list belonged to the worktree that went away; fall back to the IDE's default list.
            manager.findChangeList(LocalChangeList.getDefaultName())?.let(manager::setDefaultChangeList)
        }
        val kept = lists.mapNotNull { it.getAttributeValue("name") }.toSet()
        manager.changeLists
            .filter { it.changes.isEmpty() && !it.isDefault && it.name !in kept && it.name != LocalChangeList.getDefaultName() }
            .forEach { manager.removeChangeList(it.name) }
    }

    private companion object {
        const val REFRESH_TIMEOUT_MS = 120_000L
    }
}

private val Change.path: String? get() = (afterRevision ?: beforeRevision)?.file?.path
