package com.github.sanex3339.worktreetabs.model

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import org.jdom.filter2.Filters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EditorLayoutRewriteTest {
    @Test
    fun `moves editor entries to the other worktree and drops their file ids`() {
        val layout = JDOMUtil.load(
            """
            <splitters><leaf><file pinned="true" current-in-tab="true">
              <entry file="file:///repo/main/src/app.ts" file-id="42"><provider editor-type-id="text-editor" selected="true"><state relative-caret-position="10"><caret line="3" column="7" /></state></provider></entry>
              <tab><![CDATA[{}]]></tab>
            </file><file>
              <entry file="file:///elsewhere/notes.md" file-id="7" />
            </file></leaf></splitters>
            """.trimIndent(),
        )

        val moved = EditorLayoutRewrite.moveToWorktree(layout, "file:///repo/main", "file:///repo/wt-b")

        val entries = moved.entries()
        assertEquals("file:///repo/wt-b/src/app.ts", entries[0].getAttributeValue("file"))
        assertEquals(null, entries[0].getAttributeValue("file-id"))
        assertEquals("3", entries[0].getChild("provider").getChild("state").getChild("caret").getAttributeValue("line"))
        assertEquals("file:///elsewhere/notes.md", entries[1].getAttributeValue("file"))
        assertEquals("7", entries[1].getAttributeValue("file-id"))
        assertEquals("file:///repo/main/src/app.ts", layout.entries().first().getAttributeValue("file"))
    }

    private fun Element.entries(): List<Element> = buildList { for (entry in getDescendants(Filters.element("entry"))) add(entry) }
}
