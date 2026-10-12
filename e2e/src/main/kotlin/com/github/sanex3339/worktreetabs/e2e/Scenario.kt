package com.github.sanex3339.worktreetabs.e2e

import com.github.sanex3339.worktreetabs.registry.SwitchSupport
import com.github.sanex3339.worktreetabs.registry.WorktreeRegistry
import com.github.sanex3339.worktreetabs.switching.WorktreeStateStore
import com.github.sanex3339.worktreetabs.switching.WorktreeSwitcher
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.providers.LineBookmarkProvider
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jdom.Element
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.coroutines.resume

private val LOG = logger<Scenario>()

private const val ORIGINAL_USE_TS = "import { wtSpikeValue } from \"./marker\";\n\nexport const s: string = wtSpikeValue;\n"
private const val CHANGELIST = "e2e-feature"
private const val CHANGELIST_COMMENT = "e2e draft"
private const val RUN_CONFIGURATION = "e2e-node"

/** A file git tracks, so editing it makes a change for the changelist checks; `git checkout` puts it back. */
private const val TRACKED = "README.md"

/**
 * Main checkout A, worktree B. Arranges editors, a bookmark, a breakpoint, a changelist and a run configuration in A,
 * then switches A → B (first visit), B → A, A → B, and checks after each switch what followed and what was restored.
 */
class Scenario(private val project: Project, private val main: String, private val worktree: String) {
    private data class Check(val phase: String, val name: String, val ok: Boolean, val detail: String)

    private val checks = mutableListOf<Check>()
    private val timings = mutableListOf<String>()
    private var phase = "setup"

    suspend fun run(report: Path) {
        try {
            setUp()
            arrangeMain()
            phase = "A → B (first visit)"
            switchTo(worktree)
            verifyFirstVisit()
            withContext(Dispatchers.EDT) {
                FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
                FileEditorManager.getInstance(project).openFile(file("$worktree/README.md"), true)
            }
            phase = "B → A (back)"
            switchTo(main)
            verifyBackOnMain()
            phase = "A → B (second visit)"
            switchTo(worktree)
            verifySecondVisit()
            phase = "cleanup"
            switchTo(main)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LOG.warn("E2E scenario crashed", e)
            check("scenario", false, "crashed: $e")
        } finally {
            write(report)
        }
    }

    private suspend fun setUp() {
        awaitSmart(quietMs = 5_000)
        val registry = WorktreeRegistry.getInstance(project)
        waitUntil(60_000) { registry.state.value.support is SwitchSupport.Supported && registry.state.value.worktrees.any { it.path == worktree } }
        check("worktree list has the main checkout and the worktree", registry.state.value.worktrees.map { it.path }.containsAll(listOf(main, worktree)), registry.state.value.worktrees.joinToString { it.path })
        if (registry.state.value.activePath != main) switchTo(main)

        withContext(Dispatchers.EDT) {
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            BookmarksManager.getInstance(project)?.let { manager -> manager.bookmarks.forEach(manager::remove) }
            RunManagerImpl.getInstanceImpl(project).findConfigurationByName(RUN_CONFIGURATION)?.let { RunManagerImpl.getInstanceImpl(project).removeConfiguration(it) }
        }
        val breakpoints = XDebuggerManager.getInstance(project).breakpointManager
        edtWriteAction { breakpoints.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().forEach(breakpoints::removeBreakpoint) }
        WorktreeStateStore.getInstance(project).forget(worktree)
        WorktreeStateStore.getInstance(project).forget(main)
        edtWriteAction { VfsUtil.saveText(file("$main/wtspike/use.ts"), ORIGINAL_USE_TS) }
        withContext(Dispatchers.IO) { ProcessBuilder("git", "-C", main, "checkout", "--", TRACKED).inheritIO().start().waitFor() }
        VfsUtil.markDirtyAndRefresh(false, false, false, file("$main/$TRACKED"))
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        awaitChanges()
        withContext(Dispatchers.EDT) {
            val changes = ChangeListManager.getInstance(project)
            changes.findChangeList(CHANGELIST)?.let { list ->
                if (list.isDefault) changes.findChangeList(com.intellij.openapi.vcs.changes.LocalChangeList.getDefaultName())?.let(changes::setDefaultChangeList)
                changes.removeChangeList(list.name)
            }
        }
    }

    private suspend fun arrangeMain() {
        val use = file("$main/wtspike/use.ts")
        val marker = file("$main/wtspike/marker.ts")
        withContext(Dispatchers.EDT) {
            val editors = FileEditorManager.getInstance(project)
            editors.openFile(use, true)
            (editors.getSelectedEditor(use) as TextEditor).editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 10))
            editors.openFile(marker, true)
            val bookmarks = BookmarksManager.getInstance(project)!!
            bookmarks.add(LineBookmarkProvider.Util.find(project)!!.createBookmark(use, 0)!!, BookmarkType.DEFAULT)
        }
        val breakpoints = XDebuggerManager.getInstance(project).breakpointManager
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList.filterIsInstance<XLineBreakpointType<*>>().first { readAction { it.canPutAt(use, 2, project) } }
        edtWriteAction { addLineBreakpoint(breakpoints, type, use, 2) }

        val tracked = file("$main/$TRACKED")
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(tracked)!!
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\n<!-- e2e change -->\n") }
            FileDocumentManager.getInstance().saveDocument(document)
        }
        VcsDirtyScopeManager.getInstance(project).fileDirty(tracked)
        awaitChanges()
        waitUntil(30_000) { withContext(Dispatchers.EDT) { ChangeListManager.getInstance(project).getChange(tracked) != null } }
        withContext(Dispatchers.EDT) {
            val changes = ChangeListManager.getInstance(project)
            val list = changes.addChangeList(CHANGELIST, CHANGELIST_COMMENT)
            changes.getChange(tracked)?.let { changes.moveChangesTo(list, it) }
            changes.setDefaultChangeList(list)
            val runManager = RunManagerImpl.getInstanceImpl(project)
            val element = JDOMUtil.load(
                """<configuration name="$RUN_CONFIGURATION" type="NodeJSConfigurationType" path-to-js-file="$main/wtspike/use.ts" working-dir="$main"><method v="2" /></configuration>""",
            )
            runManager.addConfiguration(runManager.loadConfiguration(element, false))
        }
        check("change in main is in its changelist", changesIn(CHANGELIST).any { it == "$main/$TRACKED" }, changesIn(CHANGELIST).joinToString())
        check("terminal starts in main", terminalFolder() == main, "${terminalFolder()}")
    }

    private suspend fun verifyFirstVisit() {
        verifyCore(worktree)
        val (open, selected) = openEditors()
        check("tabs carried over", open == listOf("$worktree/wtspike/use.ts", "$worktree/wtspike/marker.ts"), open.joinToString())
        check("selected tab carried over", selected == "$worktree/wtspike/marker.ts", "$selected")
        check("caret carried over", caret("$worktree/wtspike/use.ts") == LogicalPosition(2, 10), "${caret("$worktree/wtspike/use.ts")}")
        check("bookmark copied to the worktree, main's put away", bookmarkUrls() == listOf(url("$worktree/wtspike/use.ts")), bookmarkUrls().joinToString())
        check("breakpoint copied to the worktree, main's put away", breakpointsAt() == listOf("${url("$worktree/wtspike/use.ts")}:2"), breakpointsAt().joinToString())
        val listGone = waitUntil(30_000) { withContext(Dispatchers.EDT) { ChangeListManager.getInstance(project).findChangeList(CHANGELIST) == null } }
        check("main's changelist left with it", listGone, changelists())
        check("default changelist is active", activeChangelist() == "Changes", "${activeChangelist()}")
        check("run configuration points at the worktree", runConfiguration().let { worktree in it && "$main/" !in it }, runConfiguration())
        check("terminal starts in the worktree", terminalFolder() == worktree, "${terminalFolder()}")
    }

    private suspend fun verifyBackOnMain() {
        verifyCore(main)
        val (open, selected) = openEditors()
        check("main's tabs restored", open == listOf("$main/wtspike/use.ts", "$main/wtspike/marker.ts"), open.joinToString())
        check("main's selected tab restored", selected == "$main/wtspike/marker.ts", "$selected")
        check("main's caret restored", caret("$main/wtspike/use.ts") == LogicalPosition(2, 10), "${caret("$main/wtspike/use.ts")}")
        check("main's bookmark back, the worktree's put away", bookmarkUrls() == listOf(url("$main/wtspike/use.ts")), bookmarkUrls().joinToString())
        check("main's breakpoint back, the worktree's put away", breakpointsAt() == listOf("${url("$main/wtspike/use.ts")}:2"), breakpointsAt().joinToString())
        val listBack = waitUntil(30_000) { changesIn(CHANGELIST).any { it == "$main/$TRACKED" } }
        check("main's changelist back with its change", listBack, changelists())
        check("main's changelist active again", activeChangelist() == CHANGELIST, "${activeChangelist()}")
        check("main's changelist comment kept", withContext(Dispatchers.EDT) { ChangeListManager.getInstance(project).findChangeList(CHANGELIST)?.comment } == CHANGELIST_COMMENT, "")
        check("run configuration points at main", runConfiguration().let { "$main/" in it && worktree !in it }, runConfiguration())
        check("terminal starts in main", terminalFolder() == main, "${terminalFolder()}")
    }

    private suspend fun verifySecondVisit() {
        verifyCore(worktree)
        val (open, _) = openEditors()
        check("the worktree's own tabs restored", open == listOf("$worktree/README.md"), open.joinToString())
        check("the worktree's own bookmark restored", bookmarkUrls() == listOf(url("$worktree/wtspike/use.ts")), bookmarkUrls().joinToString())
    }

    private suspend fun verifyCore(expected: String) {
        val contentRoots = readAction { ModuleManager.getInstance(project).modules.flatMap { ModuleRootManager.getInstance(it).contentRootUrls.toList() } }
        check("content root", contentRoots == listOf(url(expected)), contentRoots.joinToString())
        check("guessProjectDir()", project.guessProjectDir()?.path == expected, "${project.guessProjectDir()?.path}")
        val repositories = GitRepositoryManager.getInstance(project).repositories.map { it.root.path }
        check("Git repository", repositories == listOf(expected), repositories.joinToString())
    }

    private suspend fun switchTo(target: String) {
        val switcher = WorktreeSwitcher.getInstance(project)
        val registry = WorktreeRegistry.getInstance(project)
        val start = System.currentTimeMillis()
        switcher.switchTo(target)
        val done = waitUntil(10 * 60_000) { switcher.switchingTo.value == null && registry.state.value.activePath == target }
        val switched = System.currentTimeMillis() - start
        awaitSmart()
        timings += "$phase: switch ${if (done) "$switched ms" else "TIMEOUT"}, smart again ${System.currentTimeMillis() - start - SMART_QUIET_MS} ms after the click"
    }

    private suspend fun awaitSmart(quietMs: Long = SMART_QUIET_MS) {
        var smartSince: Long? = null
        waitUntil(15 * 60_000) {
            val now = System.currentTimeMillis()
            if (DumbService.isDumb(project)) smartSince = null else if (smartSince == null) smartSince = now
            smartSince?.let { now - it >= quietMs } == true
        }
    }

    private suspend fun awaitChanges() {
        withTimeoutOrNull(60_000) { suspendCancellableCoroutine { continuation -> ChangeListManager.getInstance(project).invokeAfterUpdate(false) { continuation.resume(Unit) } } }
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(100)
        }
        return condition()
    }

    private suspend fun openEditors(): Pair<List<String>, String?> = withContext(Dispatchers.EDT) {
        val editors = FileEditorManager.getInstance(project)
        editors.openFiles.map { it.path } to editors.selectedFiles.firstOrNull()?.path
    }

    private suspend fun caret(path: String): LogicalPosition? = withContext(Dispatchers.EDT) {
        val editors = FileEditorManager.getInstance(project)
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return@withContext null
        val selectedBefore = editors.selectedFiles.firstOrNull()
        editors.openFile(file, false)
        val position = (editors.getSelectedEditor(file) as? TextEditor)?.editor?.caretModel?.logicalPosition
        selectedBefore?.let { editors.openFile(it, false) }
        position
    }

    private suspend fun bookmarkUrls(): List<String> = withContext(Dispatchers.EDT) {
        BookmarksManager.getInstance(project)!!.bookmarks.mapNotNull { it.attributes["url"] }.sorted()
    }

    private suspend fun breakpointsAt(): List<String> = withContext(Dispatchers.EDT) {
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().map { "${it.fileUrl}:${it.line}" }.sorted()
    }

    private suspend fun changesIn(list: String): List<String> = withContext(Dispatchers.EDT) {
        ChangeListManager.getInstance(project).findChangeList(list)?.changes?.mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.path }.orEmpty()
    }

    private suspend fun activeChangelist(): String? = withContext(Dispatchers.EDT) { ChangeListManager.getInstance(project).defaultChangeList.name }

    private suspend fun changelists(): String = withContext(Dispatchers.EDT) {
        ChangeListManager.getInstance(project).changeLists.joinToString { "${it.name}${if (it.isDefault) "*" else ""}(${it.changes.size})" }
    }

    private suspend fun runConfiguration(): String = withContext(Dispatchers.EDT) {
        val settings = RunManagerImpl.getInstanceImpl(project).findConfigurationByName(RUN_CONFIGURATION) ?: return@withContext "missing"
        JDOMUtil.write(Element("configuration").also(settings.configuration::writeExternal))
    }

    private fun terminalFolder(): String? = TerminalProjectOptionsProvider.getInstance(project).defaultStartingDirectory?.replace('\\', '/')

    private fun file(path: String): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: error("No file $path")

    private fun url(path: String): String = VfsUtilCore.pathToUrl(path)

    private fun check(name: String, ok: Boolean, detail: String) {
        checks += Check(phase, name, ok, detail)
        LOG.info("e2e [${if (ok) "PASS" else "FAIL"}] $phase: $name ${if (ok) "" else "($detail)"}")
    }

    private fun write(report: Path) {
        val passed = checks.count { it.ok }
        val text = buildString {
            appendLine("# Worktree Tabs E2E\n")
            appendLine("${ApplicationInfo.getInstance().fullApplicationName} (${ApplicationInfo.getInstance().build.asString()}), ${LocalDateTime.now()}\n")
            appendLine("Main checkout `$main`, worktree `$worktree`\n")
            appendLine("**$passed / ${checks.size} checks passed**\n")
            timings.forEach { appendLine("- $it") }
            appendLine("\n| Phase | Check | Result | Detail |\n|---|---|---|---|")
            checks.forEach { appendLine("| ${it.phase} | ${it.name} | ${if (it.ok) "PASS" else "**FAIL**"} | ${it.detail.replace("|", "\\|")} |") }
        }
        Files.writeString(report, text)
        LOG.info("e2e: report written to $report ($passed/${checks.size} passed)")
    }

    private companion object {
        const val SMART_QUIET_MS = 3_000L
    }
}

private fun <P : XBreakpointProperties<*>> addLineBreakpoint(manager: XBreakpointManager, type: XLineBreakpointType<P>, file: VirtualFile, line: Int) {
    manager.addLineBreakpoint(type, file.url, line, type.createBreakpointProperties(file, line))
}
