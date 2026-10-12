package com.github.sanex3339.worktreetabs.switching

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.jdom.Element
import java.util.concurrent.ConcurrentHashMap

/** Per-worktree snapshots of the switch steps, kept in the project's workspace file (not shared through VCS). */
@Service(Service.Level.PROJECT)
@State(name = "WorktreeTabs", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class WorktreeStateStore : PersistentStateComponent<Element> {
    private val snapshots = ConcurrentHashMap<String, ConcurrentHashMap<String, Element>>()

    fun get(worktree: String, key: String): Element? = snapshots[worktree]?.get(key)?.clone()

    fun put(worktree: String, key: String, snapshot: Element) {
        snapshots.getOrPut(worktree) { ConcurrentHashMap() }[key] = snapshot.clone()
    }

    fun forget(worktree: String) {
        snapshots.remove(worktree)
    }

    override fun getState(): Element = Element("worktrees").apply {
        for ((path, byKey) in snapshots.toSortedMap()) {
            val worktree = Element("worktree").setAttribute("path", path)
            for ((key, snapshot) in byKey.toSortedMap()) worktree.addContent(Element("snapshot").setAttribute("key", key).addContent(snapshot.clone()))
            addContent(worktree)
        }
    }

    override fun loadState(state: Element) {
        snapshots.clear()
        for (worktree in state.getChildren("worktree")) {
            val path = worktree.getAttributeValue("path") ?: continue
            for (snapshot in worktree.getChildren("snapshot")) {
                val key = snapshot.getAttributeValue("key") ?: continue
                val content = snapshot.children.firstOrNull() ?: continue
                snapshots.getOrPut(path) { ConcurrentHashMap() }[key] = content.clone()
            }
        }
    }

    companion object {
        fun getInstance(project: Project): WorktreeStateStore = project.service()
    }
}
