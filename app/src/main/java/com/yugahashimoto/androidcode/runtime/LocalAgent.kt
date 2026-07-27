package com.yugahashimoto.androidcode.runtime

import com.yugahashimoto.androidcode.R

/**
 * A coding agent that can be installed into the shared Android-local Linux sandbox.
 *
 * Both agents run inside the same Alpine/PRoot rootfs; they differ in how they are provisioned
 * (OpenCode ships as a downloaded binary, Claude Code as an apk package) and in how the app talks
 * to them (a local HTTP server versus a streaming JSON process).
 */
enum class LocalAgent(
    val id: String,
    val displayNameRes: Int,
    val targetId: String,
) {
    OPEN_CODE("opencode", R.string.agent_opencode_name, "local-android"),
    CLAUDE_CODE("claude-code", R.string.agent_claude_code_name, "claude-code-local"),
    ;

    companion object {
        fun fromId(id: String): LocalAgent? = entries.firstOrNull { it.id == id }
    }
}
