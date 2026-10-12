package com.github.sanex3339.worktreetabs.model

/** Rewrites one worktree's root to another's inside serialized settings (run configurations and the like). */
object PathRewrite {
    /**
     * Replaces [from] with [to] wherever [from] appears as a whole path: not glued to a longer name before or after it
     * (`/repo/main-2`, `/other/repo/main`), so only the root itself and paths under it change. Both forward-slash and
     * backslash spellings of [from] are rewritten, each keeping its own separators.
     */
    fun replaceRoot(text: String, from: String, to: String): String {
        var result = text
        for ((source, target) in spellings(from, to)) {
            // Before: not part of a longer path, except after `file://`. After: the end of the path, or a separator.
            val pattern = Regex("""(?<=file://|^|[^\w./\\~-])""" + Regex.escape(source) + """(?=$|[/\\"'<>&;:,\s)\]])""")
            result = pattern.replace(result, Regex.escapeReplacement(target))
        }
        return result
    }

    private fun spellings(from: String, to: String): List<Pair<String, String>> {
        val forward = from.replace('\\', '/') to to.replace('\\', '/')
        val backward = from.replace('/', '\\') to to.replace('/', '\\')
        return if (forward == backward) listOf(forward) else listOf(forward, backward)
    }
}
