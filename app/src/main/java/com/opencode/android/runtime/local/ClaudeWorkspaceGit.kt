package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeFileChange
import com.opencode.android.core.api.OpenCodeVcsInfo

/**
 * Git information for the Claude Code runtime.
 *
 * OpenCode exposes this over HTTP; Claude Code has no server, so the sandbox's own `git` is asked
 * instead. Parsing lives here, apart from the process plumbing, so it can be tested without a
 * device.
 */
object ClaudeWorkspaceGit {
    /**
     * Current branch on the first line, the remote's default branch on the second.
     *
     * `git symbolic-ref` fails when no remote HEAD is configured, which is common in a workspace
     * cloned shallowly or created locally, so its failure is swallowed and the line left empty
     * rather than failing the whole command.
     */
    const val INFO_SCRIPT =
        "git rev-parse --abbrev-ref HEAD && " +
            "{ git symbolic-ref --quiet refs/remotes/origin/HEAD || echo; }"

    const val STATUS_SCRIPT = "git status --porcelain=v1"

    /**
     * Working-tree changes, staged and unstaged, as one patch.
     *
     * Nothing here writes to the repository: a viewer that quietly staged the user's files would be
     * changing work it was only asked to show. Untracked files are therefore absent from the patch,
     * and reach the UI through [parseStatus] instead.
     */
    fun diffScript(context: Int?): String {
        val lines = context ?: DEFAULT_DIFF_CONTEXT
        return "git --no-pager diff --no-color -U$lines HEAD"
    }

    private const val DEFAULT_DIFF_CONTEXT = 3

    /** `git rev-parse --abbrev-ref HEAD` plus the remote head, one per line. */
    fun parseInfo(output: String): OpenCodeVcsInfo {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val branch = lines.firstOrNull()?.takeIf { it != "HEAD" }
        // `origin/main` from `git symbolic-ref refs/remotes/origin/HEAD`, or nothing when unset.
        val defaultBranch = lines.getOrNull(1)?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        return OpenCodeVcsInfo(branch = branch, defaultBranch = defaultBranch)
    }

    /**
     * Parses `git status --porcelain=v1`.
     *
     * The two status characters are index and worktree state; renames carry `old -> new` and only
     * the new path is of interest to the UI.
     */
    fun parseStatus(output: String): List<OpenCodeFileChange> =
        output.lineSequence()
            .filter { it.length > 3 }
            .mapNotNull { line ->
                val code = line.take(2).trim().ifEmpty { return@mapNotNull null }
                val path = line.drop(3).trim().substringAfterLast(" -> ").trim('"')
                if (path.isEmpty()) return@mapNotNull null
                OpenCodeFileChange(file = path, path = path, status = statusName(code))
            }
            .toList()

    /**
     * Splits `git diff` output into one entry per file, keeping each file's patch.
     *
     * Counting +/- lines here rather than asking git for a second numstat keeps it to one command.
     */
    fun parseDiff(output: String): List<OpenCodeFileChange> {
        if (output.isBlank()) return emptyList()
        val changes = mutableListOf<OpenCodeFileChange>()
        val patch = StringBuilder()
        var path: String? = null

        fun flush() {
            val current = path ?: return
            val text = patch.toString()
            changes +=
                OpenCodeFileChange(
                    file = current,
                    path = current,
                    patch = text,
                    added = text.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") },
                    removed = text.lineSequence().count { it.startsWith("-") && !it.startsWith("---") },
                    status = "modified",
                )
            patch.setLength(0)
        }

        output.lineSequence().forEach { line ->
            if (line.startsWith("diff --git ")) {
                flush()
                // "diff --git a/x b/x" — the b-side is the current name.
                path = line.substringAfter(" b/", "").trim().ifEmpty { null }
            }
            if (path != null) patch.append(line).append('\n')
        }
        flush()
        return changes
    }

    private fun statusName(code: String): String =
        when {
            code.contains('?') -> "added"
            code.contains('A') -> "added"
            code.contains('D') -> "deleted"
            code.contains('R') -> "renamed"
            else -> "modified"
        }
}
