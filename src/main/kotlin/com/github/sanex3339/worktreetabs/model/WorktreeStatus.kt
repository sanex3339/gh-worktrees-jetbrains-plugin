package com.github.sanex3339.worktreetabs.model

/** What a worktree's tab shows about it: uncommitted files and commits against its upstream (null without one). */
data class WorktreeStatus(val changes: Int, val ahead: Int?, val behind: Int?, val upstream: String?) {
    /** `↑2 ↓1`, `↑3`, `↓4`, or null when the branch is even with its upstream or has none. */
    val commitsBadge: String?
        get() = listOfNotNull(ahead?.takeIf { it > 0 }?.let { "↑$it" }, behind?.takeIf { it > 0 }?.let { "↓$it" })
            .joinToString(" ")
            .ifEmpty { null }

    companion object {
        /** Parses `git status --porcelain=v2 --branch`: `# branch.*` headers, then one line per changed or untracked file. */
        fun parse(lines: List<String>): WorktreeStatus {
            var upstream: String? = null
            var ahead: Int? = null
            var behind: Int? = null
            var changes = 0
            for (line in lines) {
                when {
                    line.startsWith("# branch.upstream ") -> upstream = line.removePrefix("# branch.upstream ")
                    line.startsWith("# branch.ab ") -> {
                        val (plus, minus) = line.removePrefix("# branch.ab ").split(' ')
                        ahead = plus.removePrefix("+").toIntOrNull()
                        behind = minus.removePrefix("-").toIntOrNull()
                    }
                    line.startsWith("1 ") || line.startsWith("2 ") || line.startsWith("u ") || line.startsWith("? ") -> changes++
                }
            }
            return WorktreeStatus(changes, ahead, behind, upstream)
        }
    }
}
