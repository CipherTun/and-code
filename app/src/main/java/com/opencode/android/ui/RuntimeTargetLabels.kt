package com.opencode.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.opencode.android.R
import com.opencode.android.runtime.LocalAgent
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

/**
 * Icon standing in for a runtime.
 *
 * Every local runtime used to share the Android robot, which made the agents indistinguishable at a
 * glance. These are generic marks rather than the products' own logos: those are trademarks, and
 * this app has no licensed copy of them to ship.
 */
fun runtimeAgentIcon(agent: LocalAgent?): ImageVector =
    when (agent) {
        LocalAgent.CLAUDE_CODE -> Icons.Default.AutoAwesome
        LocalAgent.OPEN_CODE -> Icons.Default.Terminal
        null -> Icons.Default.Computer
    }
