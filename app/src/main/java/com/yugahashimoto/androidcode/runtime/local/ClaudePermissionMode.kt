package com.yugahashimoto.androidcode.runtime.local

import com.yugahashimoto.androidcode.R

/**
 * How Claude Code handles tool permissions for a session.
 *
 * Claude Code's streaming-JSON mode has no channel for answering a per-call permission prompt
 * without hosting an MCP permission tool, so the decision is made once per session instead. The
 * CLI's own `default` mode is deliberately not offered: with no prompt channel it can only deny,
 * which looks like a hang to the user.
 */
enum class ClaudePermissionMode(
    val cliValue: String,
    val labelRes: Int,
    val descriptionRes: Int,
    /**
     * Tools pre-approved for the session, passed as `--allowedTools`.
     *
     * Without this, `acceptEdits` asks before every command and the answer never arrives: git, gh
     * and everything else simply stop, with Claude explaining that it needs approval nobody can
     * give. Naming the tools up front is how a transport with no prompt channel says yes.
     */
    val allowedTools: List<String> = emptyList(),
) {
    PLAN("plan", R.string.claude_permission_plan, R.string.claude_permission_plan_desc),
    ACCEPT_EDITS(
        "acceptEdits",
        R.string.claude_permission_accept_edits,
        R.string.claude_permission_accept_edits_desc,
        // Commands, but still inside Claude Code's own permission system: unlike full access it
        // keeps the checks that stop it writing outside the directories it was given.
        allowedTools = listOf("Bash"),
    ),
    FULL_ACCESS("bypassPermissions", R.string.claude_permission_full_access, R.string.claude_permission_full_access_desc),
    ;

    companion object {
        val DEFAULT = ACCEPT_EDITS

        fun fromCliValue(value: String?): ClaudePermissionMode = entries.firstOrNull { it.cliValue == value } ?: DEFAULT
    }
}
