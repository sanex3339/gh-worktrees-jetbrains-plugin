package com.github.sanex3339.worktreetabs.switching

import com.github.sanex3339.worktreetabs.model.WorktreePaths
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.annotations.RequiresEdt

/** An open editor tab, relative to its worktree. */
data class OpenEditor(val relativePath: String, val line: Int, val column: Int, val selected: Boolean)

/**
 * Milestone 1: the open tabs (order, selected tab, caret) move to the same files in the new worktree. Milestone 2
 * replaces this with per-worktree layouts.
 */
internal object EditorCarryOver {
    @RequiresEdt
    fun capture(project: Project, root: String): List<OpenEditor> {
        val manager = FileEditorManager.getInstance(project)
        val selected = manager.selectedFiles.firstOrNull()
        return manager.openFiles.mapNotNull { file ->
            if (!WorktreePaths.isStrictlyUnder(file.path, root)) return@mapNotNull null
            val caret = (manager.getSelectedEditor(file) as? TextEditor)?.editor?.caretModel?.logicalPosition
            OpenEditor(file.path.removePrefix("$root/"), caret?.line ?: 0, caret?.column ?: 0, file == selected)
        }
    }

    @RequiresEdt
    fun restore(project: Project, root: String, editors: List<OpenEditor>) {
        val manager = FileEditorManager.getInstance(project)
        val opened = editors.mapNotNull { editor ->
            val file = LocalFileSystem.getInstance().findFileByPath("$root/${editor.relativePath}") ?: return@mapNotNull null
            manager.openFile(file, false)
            file to editor
        }
        for ((file, editor) in opened) {
            val text = manager.getSelectedEditor(file) as? TextEditor ?: continue
            text.editor.caretModel.moveToLogicalPosition(LogicalPosition(editor.line, editor.column))
        }
        opened.firstOrNull { it.second.selected }?.let { (file, _) ->
            manager.openFile(file, true)
            (manager.getSelectedEditor(file) as? TextEditor)?.editor?.scrollingModel?.scrollToCaret(ScrollType.CENTER)
        }
    }
}
