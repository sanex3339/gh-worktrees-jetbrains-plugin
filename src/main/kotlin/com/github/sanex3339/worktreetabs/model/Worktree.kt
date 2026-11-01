package com.github.sanex3339.worktreetabs.model

/** One entry of `git worktree list --porcelain`. [path] is system-independent (forward slashes). */
data class Worktree(
    val path: String,
    val head: String?,
    /** Short branch name; null when the worktree is detached (or bare). */
    val branch: String?,
    val isMain: Boolean,
    val isBare: Boolean = false,
    val isLocked: Boolean = false,
    val lockReason: String? = null,
    /** The worktree's folder is gone; `git worktree prune` would remove the entry. */
    val isPrunable: Boolean = false,
    /** When the worktree was created (epoch millis), when known; tabs follow creation order. */
    val createdAt: Long? = null,
) {
    val folderName: String get() = path.trimEnd('/').substringAfterLast('/')

    /** The branch name, or the folder name and short hash when detached. */
    val label: String get() = branch ?: listOfNotNull(folderName, head?.take(7)).joinToString(" @ ")
}

object WorktreeOrder {
    /**
     * The main checkout first, then the other worktrees in the order they were created (like browser tabs, a new one
     * goes last and nothing moves on its own); worktrees without a known creation time after them, by label. Bare and
     * prunable entries are not tabs.
     */
    fun forTabs(worktrees: List<Worktree>): List<Worktree> =
        worktrees.filterNot { it.isBare || it.isPrunable }
            .sortedWith(
                compareByDescending<Worktree> { it.isMain }
                    .thenBy { it.createdAt == null }
                    .thenBy { it.createdAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            )
}

object WorktreeListParser {
    /** Parses the output of `git worktree list --porcelain`: blank-line separated records, the main worktree first. */
    fun parse(lines: List<String>): List<Worktree> {
        val worktrees = mutableListOf<Worktree>()
        var current: Worktree? = null
        for (line in lines) {
            val key = line.substringBefore(' ')
            val value = line.substringAfter(' ', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
            when (key) {
                "worktree" -> {
                    current?.let(worktrees::add)
                    current = Worktree(value.orEmpty().replace('\\', '/'), head = null, branch = null, isMain = worktrees.isEmpty())
                }
                "HEAD" -> current = current?.copy(head = value)
                "branch" -> current = current?.copy(branch = value?.removePrefix("refs/heads/"))
                "bare" -> current = current?.copy(isBare = true)
                "locked" -> current = current?.copy(isLocked = true, lockReason = value)
                "prunable" -> current = current?.copy(isPrunable = true)
            }
        }
        current?.let(worktrees::add)
        return worktrees
    }
}
