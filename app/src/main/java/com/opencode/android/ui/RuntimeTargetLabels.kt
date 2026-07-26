package com.opencode.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opencode.android.R
import com.opencode.android.runtime.RuntimeTarget

/**
 * The name shown for a runtime everywhere it is listed.
 *
 * Android-local targets used to be labelled by their device ("this Android"), which made OpenCode
 * and Claude Code indistinguishable in every picker. Local targets are now named by their agent and
 * marked as local; remote connections keep the name the user gave them.
 */
@Composable
fun runtimeTargetLabel(target: RuntimeTarget): String {
    val agent = target.agent ?: return target.displayName
    return stringResource(R.string.local_agent_on_device, stringResource(agent.displayNameRes))
}
