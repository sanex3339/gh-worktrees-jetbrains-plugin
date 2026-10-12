package com.github.sanex3339.worktreetabs.model

import org.jdom.Element
import org.jdom.filter2.Filters

/** Moves a serialized editor layout (`EditorsSplitters.writeExternal`) from one worktree to another. */
object EditorLayoutRewrite {
    /**
     * A copy of [layout] whose editor entries under [fromUrl] point at the same files under [toUrl]. Their file ids are
     * dropped: they identify the old worktree's files, and without one the IDE resolves an entry by its URL.
     */
    fun moveToWorktree(layout: Element, fromUrl: String, toUrl: String): Element {
        val copy = layout.clone()
        for (entry in copy.getDescendants(Filters.element("entry"))) {
            val url = entry.getAttributeValue("file") ?: continue
            if (url != fromUrl && !url.startsWith("$fromUrl/")) continue
            entry.setAttribute("file", toUrl + url.removePrefix(fromUrl))
            entry.removeAttribute("file-id")
        }
        return copy
    }
}
