package com.github.sanex3339.worktreetabs.model

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * A linked worktree's admin folder, `<main>/.git/worktrees/<name>`: git creates it together with the worktree, so its
 * creation time is the worktree's.
 */
object WorktreeAdminDir {
    /** The admin folder named by a worktree's `.git` file (`gitdir: …`, absolute or relative to the worktree). */
    fun fromGitFile(content: String, worktreePath: String): String? {
        val target = content.lineSequence().firstOrNull { it.startsWith("gitdir:") }?.removePrefix("gitdir:")?.trim() ?: return null
        return WorktreePaths.normalized(Path.of(worktreePath).resolve(target).toString())
    }

    /** When the linked worktree at [worktreePath] was created, or null for the main checkout or when unreadable. */
    fun createdAt(worktreePath: String): Long? = runCatching {
        val gitFile = Path.of(worktreePath, ".git")
        if (!Files.isRegularFile(gitFile)) return null
        val adminDir = fromGitFile(Files.readString(gitFile), worktreePath) ?: return null
        Files.readAttributes(Path.of(adminDir), BasicFileAttributes::class.java).creationTime().toMillis()
    }.getOrNull()
}
