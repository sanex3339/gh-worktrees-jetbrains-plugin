package com.github.sanex3339.worktreetabs.model

import java.io.ByteArrayOutputStream

/**
 * What a worktree's tab shows about it: how many files are uncommitted (the first [MAX_PATHS] of their paths, relative
 * to the worktree), commits against its upstream (null without one), and when the newest of those files was last
 * modified (filled in from the disk).
 */
data class WorktreeStatus(
    val changes: Int,
    val ahead: Int?,
    val behind: Int?,
    val upstream: String?,
    val changedPaths: List<String> = emptyList(),
    val lastEdited: Long? = null,
) {
    /** `↑2 ↓1`, `↑3`, `↓4`, or null when the branch is even with its upstream or has none. */
    val commitsBadge: String?
        get() = listOfNotNull(ahead?.takeIf { it > 0 }?.let { "↑$it" }, behind?.takeIf { it > 0 }?.let { "↓$it" })
            .joinToString(" ")
            .ifEmpty { null }

    companion object {
        /** Paths kept per worktree: enough to find the newest edit, without stat-ing thousands of files per refresh. */
        const val MAX_PATHS = 500

        /** Parses `git status --porcelain=v2 --branch`: `# branch.*` headers, then one line per changed or untracked file. */
        fun parse(lines: List<String>): WorktreeStatus {
            var upstream: String? = null
            var ahead: Int? = null
            var behind: Int? = null
            val paths = mutableListOf<String>()
            var changes = 0
            for (line in lines) {
                val entry = line.startsWith("1 ") || line.startsWith("2 ") || line.startsWith("u ") || line.startsWith("? ")
                if (entry) changes++
                if (entry && paths.size >= MAX_PATHS) continue
                when {
                    line.startsWith("# branch.upstream ") -> upstream = line.removePrefix("# branch.upstream ")
                    line.startsWith("# branch.ab ") -> {
                        val (plus, minus) = line.removePrefix("# branch.ab ").split(' ')
                        ahead = plus.removePrefix("+").toIntOrNull()
                        behind = minus.removePrefix("-").toIntOrNull()
                    }
                    // Ordinary, renamed ("new<TAB>old") and unmerged entries carry 8, 9 and 10 fields before the path.
                    line.startsWith("1 ") -> paths += unquote(line.split(' ', limit = 9)[8])
                    line.startsWith("2 ") -> paths += unquote(line.split(' ', limit = 10)[9].substringBefore('\t'))
                    line.startsWith("u ") -> paths += unquote(line.split(' ', limit = 11)[10])
                    line.startsWith("? ") -> paths += unquote(line.removePrefix("? "))
                }
            }
            return WorktreeStatus(changes, ahead, behind, upstream, paths)
        }

        /** Undoes git's C-style quoting of unusual paths: `"caf\303\251.ts"` is `café.ts` (octal escapes are UTF-8 bytes). */
        internal fun unquote(path: String): String {
            if (path.length < 2 || !path.startsWith('"') || !path.endsWith('"')) return path
            val bytes = ByteArrayOutputStream()
            val body = path.substring(1, path.length - 1)
            var index = 0
            while (index < body.length) {
                val char = body[index]
                if (char != '\\' || index + 1 >= body.length) {
                    bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                    index++
                    continue
                }
                val next = body[index + 1]
                if (index + 3 < body.length && body.substring(index + 1, index + 4).all { it in '0'..'7' }) {
                    bytes.write(body.substring(index + 1, index + 4).toInt(8))
                    index += 4
                    continue
                }
                bytes.write(
                    when (next) {
                        'n' -> '\n'.code
                        't' -> '\t'.code
                        'r' -> '\r'.code
                        'a' -> 7
                        'b' -> '\b'.code
                        'f' -> 12
                        'v' -> 11
                        else -> next.code
                    },
                )
                index += 2
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }
}
