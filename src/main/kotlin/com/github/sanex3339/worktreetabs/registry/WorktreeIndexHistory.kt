package com.github.sanex3339.worktreetabs.registry

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Worktrees this IDE has had as a project's content root until indexing settled. Indexes are app-wide and survive
 * restarts, so switching to one of these only re-scans; any other worktree is fully indexed on its first switch.
 * (Invalidating caches makes the list optimistic, which only hides the "not indexed yet" hint.)
 */
@Service(Service.Level.APP)
@State(name = "WorktreeTabsIndexHistory", storages = [Storage("worktreeTabsIndexHistory.xml", roamingType = RoamingType.DISABLED)])
class WorktreeIndexHistory : SimplePersistentStateComponent<WorktreeIndexHistory.Paths>(Paths()) {
    class Paths : BaseState() {
        var indexed by stringSet()
    }

    private val mutableRevision = MutableStateFlow(0)

    /** Changes whenever a worktree is added or removed, so tabs can update their hint. */
    val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    fun isIndexed(path: String): Boolean = path in state.indexed

    fun markIndexed(path: String) {
        if (state.indexed.add(path)) {
            state.intIncrementModificationCount()
            mutableRevision.value++
        }
    }

    fun forget(path: String) {
        if (state.indexed.remove(path)) {
            state.intIncrementModificationCount()
            mutableRevision.value++
        }
    }

    companion object {
        fun getInstance(): WorktreeIndexHistory = service()
    }
}
