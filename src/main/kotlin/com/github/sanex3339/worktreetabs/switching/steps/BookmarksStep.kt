package com.github.sanex3339.worktreetabs.switching.steps

import com.github.sanex3339.worktreetabs.model.WorktreePaths
import com.github.sanex3339.worktreetabs.switching.SwitchContext
import com.github.sanex3339.worktreetabs.switching.SwitchStep
import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkProvider
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element

/**
 * Each worktree keeps its own bookmarks: the ones in the old worktree's files leave the Bookmarks view with it and come
 * back when it is active again; a first visit gets copies of the old worktree's bookmarks.
 */
class BookmarksStep : SwitchStep {
    override val title = "bookmarks"
    override val order = 110
    override val snapshotKey = "bookmarks"

    override suspend fun capture(context: SwitchContext): Element? = withContext(Dispatchers.EDT) {
        val manager = BookmarksManager.getInstance(context.project) ?: return@withContext null
        val mine = manager.bookmarks.filter { bookmark -> bookmark.url?.let { it == context.fromUrl || WorktreePaths.isStrictlyUnder(it, context.fromUrl) } == true }
        val snapshot = Element(ROOT)
        for (bookmark in mine) {
            val element = Element("bookmark").setAttribute("provider", bookmark.provider.javaClass.name)
            manager.getType(bookmark)?.let { element.setAttribute("type", it.name) }
            bookmark.attributes.forEach { (name, value) -> element.addContent(Element("attribute").setAttribute("name", name).setAttribute("value", value)) }
            for (group in manager.getGroups(bookmark)) {
                val groupElement = Element("group").setAttribute("name", group.name).setAttribute("default", group.isDefault.toString())
                group.getDescription(bookmark)?.let { groupElement.setAttribute("description", it) }
                element.addContent(groupElement)
            }
            snapshot.addContent(element)
        }
        mine.forEach(manager::remove)
        snapshot
    }

    override suspend fun restore(context: SwitchContext, saved: Element?, carried: Element?) = withContext(Dispatchers.EDT) {
        val snapshot = saved ?: carried?.let { movedTo(it, context) } ?: return@withContext
        val manager = BookmarksManager.getInstance(context.project) ?: return@withContext
        val providers = BookmarkProvider.EP.getExtensions(context.project)
        for (element in snapshot.getChildren("bookmark")) {
            val provider = providers.firstOrNull { it.javaClass.name == element.getAttributeValue("provider") } ?: continue
            val attributes = element.getChildren("attribute").associate { it.getAttributeValue("name") to it.getAttributeValue("value") }
            val bookmark = provider.createBookmark(attributes) ?: continue
            val type = element.getAttributeValue("type")?.let { runCatching { BookmarkType.valueOf(it) }.getOrNull() } ?: BookmarkType.DEFAULT
            val groups = element.getChildren("group")
            if (groups.isEmpty()) {
                manager.add(bookmark, type)
                continue
            }
            for (groupElement in groups) {
                val name = groupElement.getAttributeValue("name") ?: continue
                val group = manager.getGroup(name) ?: manager.addGroup(name, groupElement.getAttributeValue("default") == "true") ?: continue
                group.add(bookmark, type, groupElement.getAttributeValue("description"))
            }
        }
    }

    private fun movedTo(snapshot: Element, context: SwitchContext): Element = snapshot.clone().apply {
        for (attribute in getChildren("bookmark").flatMap { it.getChildren("attribute") }) {
            val value = attribute.getAttributeValue("value") ?: continue
            attribute.setAttribute("value", WorktreePaths.rebase(value, context.fromUrl, context.toUrl))
        }
    }

    private val Bookmark.url: String? get() = attributes["url"]

    private companion object {
        const val ROOT = "bookmarks"
    }
}
