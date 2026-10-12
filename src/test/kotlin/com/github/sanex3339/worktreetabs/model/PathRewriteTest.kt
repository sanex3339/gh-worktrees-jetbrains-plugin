package com.github.sanex3339.worktreetabs.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PathRewriteTest {
    private fun rewrite(text: String) = PathRewrite.replaceRoot(text, "/repo/main", "/repo/wt-b")

    @Test
    fun `rewrites the root and paths under it in attribute values`() {
        assertEquals(
            """<configuration><package-json value="/repo/wt-b/package.json" /><working-dir value="/repo/wt-b" /></configuration>""",
            rewrite("""<configuration><package-json value="/repo/main/package.json" /><working-dir value="/repo/main" /></configuration>"""),
        )
    }

    @Test
    fun `leaves paths that only share a prefix or contain the root further in`() {
        val text = """<a value="/repo/main-2/x" /><b value="/other/repo/main/x" /><c value="/repo/mainline" />"""
        assertEquals(text, rewrite(text))
    }

    @Test
    fun `rewrites file urls, path lists and paths inside arguments`() {
        assertEquals(
            """<a value="file:///repo/wt-b/src" /><env value="/repo/wt-b/bin:/usr/bin" /><args value="--config=/repo/wt-b/jest.config.ts --ci" />""",
            rewrite("""<a value="file:///repo/main/src" /><env value="/repo/main/bin:/usr/bin" /><args value="--config=/repo/main/jest.config.ts --ci" />"""),
        )
    }

    @Test
    fun `rewrites windows paths too`() {
        assertEquals(
            """<a value="C:\work\wt-b\package.json" />""",
            PathRewrite.replaceRoot("""<a value="C:\work\main\package.json" />""", "C:/work/main", "C:/work/wt-b"),
        )
    }
}
