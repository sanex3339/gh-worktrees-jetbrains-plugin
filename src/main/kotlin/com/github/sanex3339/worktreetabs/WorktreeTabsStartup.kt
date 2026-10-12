package com.github.sanex3339.worktreetabs

import com.github.sanex3339.worktreetabs.switching.WorktreeSwitcher
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the switcher (and with it the registry) so nested worktrees are excluded even before the strip shows. */
class WorktreeTabsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        WorktreeSwitcher.getInstance(project)
    }
}
