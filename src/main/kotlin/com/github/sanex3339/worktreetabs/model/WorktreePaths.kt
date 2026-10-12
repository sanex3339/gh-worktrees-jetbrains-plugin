package com.github.sanex3339.worktreetabs.model

/** Path arithmetic for switching; every path is system-independent (forward slashes, no trailing slash). */
object WorktreePaths {
    fun isStrictlyUnder(path: String, root: String): Boolean = path.startsWith("$root/")

    /** [path] moved from under [from] to the same place under [to]; paths outside [from] are returned unchanged. */
    fun rebase(path: String, from: String, to: String): String = when {
        path == from -> to
        isStrictlyUnder(path, from) -> to + path.removePrefix(from)
        else -> path
    }

    /**
     * The excluded folders of the content root after switching it from [oldRoot] to [newRoot]: the old root's own
     * exclusions moved to the new root, minus the worktrees nested in the old root (excluded only so they are not
     * indexed twice), plus the worktrees nested in the new root.
     */
    fun planExcludes(oldRoot: String, newRoot: String, oldExcludes: List<String>, worktreePaths: Collection<String>): List<String> {
        val worktrees = worktreePaths.toSet()
        val carried = oldExcludes.filterNot { it in worktrees }.map { rebase(it, oldRoot, newRoot) }
        val nested = worktreePaths.filter { isStrictlyUnder(it, newRoot) }
        return (carried + nested).distinct()
    }
}
