package com.github.sanex3339.worktreetabs.switching

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jdom.Element
import org.jdom.filter2.Filters

/**
 * Fallback when the IDE's own editor layout restore is unavailable: opens the files a layout lists, in order, and
 * selects the one that was selected. Splits and caret positions are lost.
 */
internal object EditorCarryOver {
    @RequiresEdt
    fun openFilesOf(project: Project, layout: Element) {
        val manager = FileEditorManager.getInstance(project)
        var selected: com.intellij.openapi.vfs.VirtualFile? = null
        for (fileElement in layout.getDescendants(Filters.element("file"))) {
            val url = fileElement.getChild("entry")?.getAttributeValue("file") ?: continue
            val file = VirtualFileManager.getInstance().findFileByUrl(url) ?: continue
            manager.openFile(file, false)
            if (fileElement.getAttributeValue("current-in-tab") == "true" && selected == null) selected = file
        }
        selected?.let { manager.openFile(it, true) }
    }
}
