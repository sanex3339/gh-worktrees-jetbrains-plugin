package com.github.sanex3339.worktreetabs.model

/** Defaults and checks for the New Worktree dialog. */
object NewWorktree {
    /** `<parent>/<repo>.worktrees/<branch>` next to the main checkout, with `/` in the branch name turned into `-`. */
    fun defaultLocation(mainCheckout: String, branch: String): String {
        val main = mainCheckout.trimEnd('/')
        return "$main.worktrees/${branch.trim().replace('/', '-')}"
    }

    /** Why git would refuse [name] as a branch name (`git check-ref-format --branch`), or null when it is fine. */
    fun branchNameProblem(name: String): String? = when {
        name.isBlank() -> "Enter a branch name"
        name.any { it.isWhitespace() || it.code < 32 } -> "A branch name can't contain spaces"
        name.startsWith("-") -> "A branch name can't start with '-'"
        name == "@" -> "'@' isn't a valid branch name"
        name.contains("..") || name.contains("@{") || name.contains("//") -> "A branch name can't contain '..', '@{' or '//'"
        name.any { it in FORBIDDEN } -> "A branch name can't contain any of ~ ^ : ? * [ \\"
        name.endsWith("/") || name.endsWith(".") || name.endsWith(".lock") -> "A branch name can't end with '/', '.' or '.lock'"
        name.split('/').any { it.startsWith(".") } -> "No part of a branch name can start with '.'"
        else -> null
    }

    private const val FORBIDDEN = "~^:?*[\\"
}
