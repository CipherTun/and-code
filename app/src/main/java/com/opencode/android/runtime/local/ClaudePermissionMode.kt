package com.opencode.android.runtime.local

import com.opencode.android.R

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
) {
    PLAN("plan", R.string.claude_permission_plan, R.string.claude_permission_plan_desc),
    ACCEPT_EDITS("acceptEdits", R.string.claude_permission_accept_edits, R.string.claude_permission_accept_edits_desc),
    FULL_ACCESS("bypassPermissions", R.string.claude_permission_full_access, R.string.claude_permission_full_access_desc),
    ;

    companion object {
        val DEFAULT = ACCEPT_EDITS

        fun fromCliValue(value: String?): ClaudePermissionMode = entries.firstOrNull { it.cliValue == value } ?: DEFAULT
    }
}
