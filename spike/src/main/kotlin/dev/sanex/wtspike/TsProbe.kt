package dev.sanex.wtspike

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path

data class TsProbeResult(val outcome: String, val ms: Long, val inProjectContent: Boolean, val errors: List<String>)

/**
 * Each worktree has `wtspike/use.ts` assigning `marker.ts`'s value to a `string`. The marker is a `number` in the main
 * checkout and a `boolean` in the other worktree, so the error text tells which worktree the TypeScript tooling
 * actually resolved: [expected] means it is right, [stale] means it still sees the worktree we swapped away from.
 */
object TsProbe {
    suspend fun check(project: Project, worktree: Path, expected: String, stale: String, timeoutMs: Long = 60_000): TsProbeResult {
        val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(worktree.resolve("wtspike/use.ts")) }
            ?: return TsProbeResult("probe file missing", 0, false, emptyList())
        val inContent = readAction { ProjectFileIndex.getInstance(project).isInContent(file) }
        withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFile(file, true) }
        val document = readAction { FileDocumentManager.getInstance().getDocument(file) }
            ?: return TsProbeResult("no document", 0, inContent, emptyList())

        val start = System.currentTimeMillis()
        var errors = emptyList<String>()
        var staleSeen = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            errors = readAction {
                val found = mutableListOf<String>()
                DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.ERROR, 0, document.textLength) { info ->
                    info.description?.let(found::add)
                    true
                }
                found
            }
            if (errors.any { expected in it }) {
                return TsProbeResult(if (staleSeen) "correct (after showing stale)" else "correct", System.currentTimeMillis() - start, inContent, errors)
            }
            if (errors.any { stale in it }) staleSeen = true
            delay(200)
        }
        val outcome = if (staleSeen) "STALE: still resolves the previous worktree" else "timeout: no type error reported"
        return TsProbeResult(outcome, System.currentTimeMillis() - start, inContent, errors)
    }

    suspend fun closeAllEditors(project: Project) {
        withContext(Dispatchers.EDT) { FileEditorManagerEx.getInstanceEx(project).closeAllFiles() }
    }
}
