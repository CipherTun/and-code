package com.opencode.android.runtime.local

import com.opencode.android.R

/**
 * How the official `agy` CLI handles tool permissions for a session.
 *
 * Mirrors [ClaudePermissionMode]: agy's own `default` mode expects an interactive TUI to answer
 * per-call prompts, which the one-shot `--print` bridge this app drives has no channel for, so it is
 * deliberately not offered here.
 */
enum class AntigravityPermissionMode(
    val cliArgs: List<String>,
    /**
     * How the mode is named in the chat composer's mode chip.
     *
     * The chip is the same control that offers OpenCode's `build`/`plan`, so Antigravity's modes
     * belong there rather than only in settings. These use the CLI's own vocabulary.
     */
    val agentId: String,
    val labelRes: Int,
    val descriptionRes: Int,
) {
    PLAN(listOf("--mode", "plan"), "plan", R.string.claude_permission_plan, R.string.antigravity_permission_plan_desc),
    ACCEPT_EDITS(
        listOf("--mode", "accept-edits"),
        "accept-edits",
        R.string.claude_permission_accept_edits,
        R.string.antigravity_permission_accept_edits_desc,
    ),
    FULL_ACCESS(
        listOf("--dangerously-skip-permissions"),
        "full-access",
        R.string.claude_permission_full_access,
        R.string.antigravity_permission_full_access_desc,
    ),
    ;

    /** The value persisted per session and round-tripped through [fromCliValue]. */
    val cliValue: String get() = name

    companion object {
        val DEFAULT = ACCEPT_EDITS

        fun fromCliValue(value: String?): AntigravityPermissionMode = entries.firstOrNull { it.cliValue == value } ?: DEFAULT

        /** Resolves the mode the composer's chip selected, or null when [agentId] is not one of ours. */
        fun fromAgentId(agentId: String?): AntigravityPermissionMode? = entries.firstOrNull { it.agentId == agentId }
    }
}
