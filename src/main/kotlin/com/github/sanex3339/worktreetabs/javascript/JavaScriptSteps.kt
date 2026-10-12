package com.github.sanex3339.worktreetabs.javascript

import com.github.sanex3339.worktreetabs.model.WorktreePaths
import com.github.sanex3339.worktreetabs.switching.SwitchContext
import com.github.sanex3339.worktreetabs.switching.SwitchStep
import com.intellij.lang.javascript.buildTools.npm.PackageJsonBuildFileManager
import com.intellij.lang.typescript.compiler.TypeScriptServiceRestarter
import com.intellij.openapi.application.EDT
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element

/**
 * Restarts the TypeScript, Vue and Angular language services: the new worktree may resolve another TypeScript from
 * its own `node_modules`, and the services read that only when they start. (Experimental platform API.)
 */
class TypeScriptRestartStep : SwitchStep {
    override val title = "TypeScript service"
    override val order = 50

    override suspend fun restore(context: SwitchContext, saved: Element?, carried: Element?) {
        TypeScriptServiceRestarter.restartServicesAsync(context.project)
    }
}

/** The npm tool window lists package.json files it detected once; point them at the new worktree's copies. */
class NpmScriptsStep : SwitchStep {
    override val title = "npm scripts"
    override val order = 220

    override suspend fun restore(context: SwitchContext, saved: Element?, carried: Element?) = withContext(Dispatchers.EDT) {
        val manager = PackageJsonBuildFileManager.getInstance(context.project)
        for (file in manager.buildfiles.toList()) {
            if (!WorktreePaths.isStrictlyUnder(file.path, context.from)) continue
            val moved = LocalFileSystem.getInstance().findFileByPath(WorktreePaths.rebase(file.path, context.from, context.to))
            if (moved != null) manager.changeBuildfile(file, moved) else manager.removeBuildfile(file)
        }
    }
}
