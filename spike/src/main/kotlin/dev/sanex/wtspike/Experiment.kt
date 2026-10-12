package dev.sanex.wtspike

import com.intellij.lang.typescript.compiler.TypeScriptServiceRestarter
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

private val LOG = logger<Experiment>()

/**
 * Waits until no scanning or indexing has run and the project has been smart for [quietMs], and returns when the last
 * of that activity happened (or [since] when there was none after it). Null on timeout.
 */
suspend fun awaitSettled(project: Project, since: Long, quietMs: Long = 5_000, timeoutMs: Long = 20 * 60_000L): Long? {
    val recorder = IndexingRecorder.of(project)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val last = maxOf(recorder.lastActivityMs, since)
        if (!recorder.isBusy && !DumbService.isDumb(project) && System.currentTimeMillis() - last >= quietMs) return last
        delay(100)
    }
    return null
}

/**
 * P0 waits for the project's own opening scan and indexing on the main checkout. P1 swaps to the other worktree for
 * the first time, P2 back to the main checkout, P3 to the worktree again (both indexed by now), and P4 back to the
 * main checkout after files in it changed on disk while it was inactive. Each phase records the swap timings, every
 * scan and indexing pass it caused, when the IDE settled, and what the TypeScript tooling resolves afterwards.
 */
class Experiment(private val project: Project, private val main: Path, private val worktree: Path) {
    private val recorder = IndexingRecorder.of(project)
    private val summary = mutableListOf<String>()
    private val details = StringBuilder()
    private lateinit var report: Path

    suspend fun run(reportPath: Path): Path {
        report = reportPath
        val stamp = System.currentTimeMillis()
        // P4 writes files into the main checkout, so it only runs where the probe folder was set up for the spike.
        val probeSetUp = withContext(Dispatchers.IO) { Files.isDirectory(main.resolve("wtspike")) }
        if (probeSetUp) withContext(Dispatchers.IO) { Files.deleteIfExists(main.resolve(EXTERNAL_NEW)) }

        baseline()
        swapPhase("P1 main → worktree (first activation)", worktree, expected = "'boolean'", stale = "'number'", observeDefaultMapping = true, restartTs = true)
        swapPhase("P2 worktree → main (indexed before)", main, expected = "'number'", stale = "'boolean'")
        swapPhase("P3 main → worktree (indexed before)", worktree, expected = "'boolean'", stale = "'number'")
        if (!probeSetUp) {
            details.appendLine("P4 skipped: no `wtspike/` probe folder in the main checkout.")
            swapPhase("P4 worktree → main", main, expected = "'number'", stale = "'boolean'")
            write()
            return report
        }

        val editedWord = "wtSpikeEdited_$stamp"
        withContext(Dispatchers.IO) {
            Files.writeString(main.resolve(EXTERNAL_EDIT), "export const $editedWord = 1;\n")
            Files.writeString(main.resolve(EXTERNAL_NEW), "export const wtSpikeNew = 1;\n")
        }
        swapPhase(
            "P4 worktree → main after editing main on disk while inactive",
            main, expected = "'number'", stale = "'boolean'",
            afterSettle = { checkExternalEdits(editedWord) },
        )
        write()
        return report
    }

    private suspend fun baseline() {
        waitFor(120_000) { recorder.scans.firstOrNull { it.type.contains("ON_PROJECT_OPEN") } }
        val settledAt = awaitSettled(project, since = 0, quietMs = 10_000)
        val mappings = ProjectLevelVcsManager.getInstance(project).directoryMappings.map { "${it.directory.ifEmpty { "<Project>" }} (${it.vcs})" }
        val repositories = GitRepositoryManager.getInstance(project).repositories.map { "${it.root.path} [${it.currentBranchName}]" }
        val ts = TsProbe.check(project, main, expected = "'number'", stale = "'boolean'")
        TsProbe.closeAllEditors(project)

        val scans = recorder.scans.toList()
        val indexings = recorder.indexings.toList()
        summary += row("P0 project open on main", null, null, settledAt?.let { scans.minOfOrNull(ScanRecord::startMs)?.let { first -> it - first } }, scans, indexings, ts.outcome)
        details.appendLine("## P0 project opened on the main checkout\n")
        details.appendLine("- VCS mappings: ${mappings.joinToString()}")
        details.appendLine("- Git repositories: ${repositories.joinToString()}")
        details.appendLine("- Settled: ${if (settledAt == null) "TIMEOUT" else "yes"}")
        appendActivity(scans, indexings)
        appendTs("TypeScript probe (main, expects 'number')", ts)
        write()
    }

    private suspend fun swapPhase(
        title: String,
        to: Path,
        expected: String,
        stale: String,
        observeDefaultMapping: Boolean = false,
        restartTs: Boolean = false,
        afterSettle: suspend () -> List<String> = { emptyList() },
    ) {
        LOG.info("wtspike: $title")
        TsProbe.closeAllEditors(project)
        val scansBefore = recorder.scans.size
        val indexingsBefore = recorder.indexings.size

        val swap = WorktreeSwapper.swap(project, to, observeDefaultMapping)
        val settledAt = awaitSettled(project, since = swap.startMs)
        // The P1 observation pause (2 s before re-mapping VCS) is not part of a real swap.
        val settledMs = settledAt?.let { it - swap.startMs - if (observeDefaultMapping) 2_000 else 0 }
        val scans = recorder.scans.drop(scansBefore)
        val indexings = recorder.indexings.drop(indexingsBefore)
        val checks = afterSettle()

        val ts = TsProbe.check(project, to, expected, stale)
        var tsOutcome = ts.outcome
        details.appendLine("## $title\n")
        details.appendLine("- ${swap.from} → ${swap.to}")
        details.appendLine("- Swap mutations: ${swap.mutationsMs} ms (VFS refresh ${swap.refreshMs} ms, content root ${swap.modelMs} ms, VCS mapping call ${swap.vcsMappingMs} ms, waiting for the old root to be released ${swap.oldRootReleasedMs ?: "TIMEOUT"} ms, project root ${swap.projectRootMs} ms)")
        details.appendLine("- Git repository for target ready ${swap.repositoryAfterMappingMs?.let { "$it ms after the mapping call" } ?: "NEVER (30 s timeout)"}; branch ${swap.branch}")
        details.appendLine("- guessProjectDir(): ${swap.guessProjectDir} (target after ${swap.guessProjectDirMs?.let { "$it ms" } ?: "NEVER within 3 s"}); base directories: ${swap.baseDirectoriesAfter.joinToString()}")
        details.appendLine("- Entities still referencing the old root: ${swap.oldRootReferences.joinToString().ifEmpty { "none" }}")
        details.appendLine("- Project root entities: ${swap.projectRootsBefore.joinToString()} → ${swap.projectRootsAfter.joinToString()}")
        details.appendLine("- Settled (no scanning/indexing, smart for 5 s) after: ${settledMs?.let { "$it ms" } ?: "TIMEOUT"}")
        details.appendLine("- VCS mappings before: ${swap.mappingsBefore.joinToString()}")
        swap.repositoriesBeforeExplicitMapping?.let { details.appendLine("- Repositories 2 s after the content-root swap, before re-mapping: ${it.joinToString()}") }
        details.appendLine("- Repositories after: ${swap.repositoriesAfter.joinToString()}")
        details.appendLine("- Content roots after: ${swap.contentRootsAfter.joinToString()} (excludes carried: ${swap.excludesCarried}, source folders dropped: ${swap.sourceFoldersDropped})")
        checks.forEach { details.appendLine("- $it") }
        appendActivity(scans, indexings)
        appendTs("TypeScript probe (expects $expected)", ts)

        if (restartTs) {
            TsProbe.closeAllEditors(project)
            val restartStart = System.currentTimeMillis()
            TypeScriptServiceRestarter.restartServicesAsync(project)
            val restartMs = System.currentTimeMillis() - restartStart
            val afterRestart = TsProbe.check(project, to, expected, stale)
            details.appendLine("- TypeScriptServiceRestarter.restartServicesAsync: $restartMs ms")
            appendTs("TypeScript probe after restarting the services", afterRestart)
            tsOutcome += " / after restart: ${afterRestart.outcome}"
        }

        summary += row(title, swap.mutationsMs, swap.repositoryAfterMappingMs, settledMs, scans, indexings, tsOutcome)
        write()
    }

    private suspend fun checkExternalEdits(editedWord: String): List<String> = smartReadAction(project) {
        val scope = GlobalSearchScope.projectScope(project)
        val newFound = FilenameIndex.getVirtualFilesByName("external-new.ts", scope).isNotEmpty()
        var editFound = false
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(editedWord, scope, { editFound = true; false }, true)
        listOf(
            "File created on disk while inactive is in the file name index: $newFound",
            "Identifier written on disk while inactive is in the word index: $editFound",
        )
    }

    private fun appendActivity(scans: List<ScanRecord>, indexings: List<IndexingRecord>) {
        if (scans.isEmpty()) {
            details.appendLine("\nNo scanning.\n")
        } else {
            details.appendLine("\n| Scan type | Reason | Scanned | For indexing | Dumb ms | Duration ms | Cancelled |")
            details.appendLine("|---|---|---|---|---|---|---|")
            scans.forEach { details.appendLine("| ${it.type} | ${it.reason} | ${it.scannedFiles} | ${it.filesForIndexing} | ${it.dumbModeMs} | ${it.endMs - it.startMs} | ${it.cancelled} |") }
            details.appendLine()
        }
        if (indexings.isNotEmpty()) {
            details.appendLine("| Dumb indexing: files | Duration ms | Cancelled |")
            details.appendLine("|---|---|---|")
            indexings.forEach { details.appendLine("| ${it.indexedFiles} | ${it.endMs - it.startMs} | ${it.cancelled} |") }
            details.appendLine()
        }
    }

    private fun appendTs(label: String, ts: TsProbeResult) {
        details.appendLine("- $label: **${ts.outcome}** after ${ts.ms} ms; file in project content: ${ts.inProjectContent}; errors: ${ts.errors.joinToString(" | ").ifEmpty { "none" }}\n")
    }

    private fun row(title: String, swapMs: Long?, repoMs: Long?, settledMs: Long?, scans: List<ScanRecord>, indexings: List<IndexingRecord>, ts: String): String {
        val types = scans.groupingBy { it.type }.eachCount().entries.joinToString { "${it.key}×${it.value}" }.ifEmpty { "none" }
        return "| $title | ${swapMs ?: "–"} | ${repoMs ?: "–"} | ${settledMs ?: "TIMEOUT"} | $types | ${scans.sumOf { it.scannedFiles }} | " +
            "${scans.sumOf { it.filesForIndexing }} | ${indexings.sumOf { it.indexedFiles }} | ${scans.sumOf { it.dumbModeMs }} | $ts |"
    }

    private suspend fun write() = withContext(Dispatchers.IO) {
        val text = buildString {
            appendLine("# Worktree swap spike\n")
            appendLine("${ApplicationInfo.getInstance().fullApplicationName} (${ApplicationInfo.getInstance().build.asString()}), ${LocalDateTime.now()}\n")
            appendLine("Main checkout: `$main`; worktree: `$worktree`\n")
            appendLine("| Phase | Swap ms | Git repo ready ms | Settled ms | Scans | Files scanned | Sent to indexing | Dumb-indexed | Dumb ms | TypeScript |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            summary.forEach(::appendLine)
            appendLine()
            append(details)
        }
        Files.writeString(report, text)
        LOG.info("wtspike: report updated at $report")
    }

    private companion object {
        const val EXTERNAL_EDIT = "wtspike/external-edit.ts"
        const val EXTERNAL_NEW = "wtspike/external-new.ts"
    }
}
