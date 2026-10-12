package com.github.sanex3339.worktreetabs.e2e

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private val LOG = logger<E2EStartup>()

class E2EStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (System.getProperty("e2e.autorun") != "true") return
        val worktree = System.getProperty("e2e.worktree") ?: return LOG.warn("e2e.worktree is not set")
        val report = Path.of(System.getProperty("e2e.report") ?: "${project.basePath}/../worktree-tabs-e2e.md")
        project.service<E2EService>().run(worktree, report, exit = System.getProperty("e2e.exit") == "true")
    }
}

@Service(Service.Level.PROJECT)
class E2EService(private val project: Project, private val scope: CoroutineScope) {
    fun run(worktree: String, report: Path, exit: Boolean) {
        scope.launch {
            try {
                Scenario(project, project.basePath!!, worktree).run(report)
            } finally {
                if (exit) withContext(Dispatchers.EDT) { ApplicationManager.getApplication().exit(true, true, false) }
            }
        }
    }
}
