package com.github.sanex3339.worktreetabs.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.APP)
@State(name = "WorktreeTabsSettings", storages = [Storage("worktreeTabs.xml")])
class WorktreeTabsSettings : SimplePersistentStateComponent<WorktreeTabsSettings.Options>(Options()) {
    class Options : BaseState() {
        /** Wrap the tabs into as many rows as needed instead of one scrollable row. */
        var multipleRows by property(false)
    }

    private val mutableRevision = MutableStateFlow(0)

    /** Changes whenever a setting changes, so open strips can re-layout. */
    val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    var multipleRows: Boolean
        get() = state.multipleRows
        set(value) {
            state.multipleRows = value
            mutableRevision.value++
        }

    override fun loadState(state: Options) {
        super.loadState(state)
        mutableRevision.value++
    }

    companion object {
        fun getInstance(): WorktreeTabsSettings = service()
    }
}
