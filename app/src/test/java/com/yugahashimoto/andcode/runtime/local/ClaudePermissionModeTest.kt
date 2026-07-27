package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudePermissionModeTest {
    @Test
    fun `accept edits pre-approves commands because nothing can answer a prompt`() {
        // Without this the CLI asks before every command and the answer never arrives: git and gh
        // stopped working while Claude explained it needed approval the transport cannot give.
        assertEquals(listOf("Bash"), ClaudePermissionMode.ACCEPT_EDITS.allowedTools)
    }

    @Test
    fun `plan approves nothing`() {
        // Plan is the mode that is meant to stop before acting, so silence is correct there.
        assertTrue(ClaudePermissionMode.PLAN.allowedTools.isEmpty())
    }

    @Test
    fun `full access needs no allow list`() {
        assertTrue(ClaudePermissionMode.FULL_ACCESS.allowedTools.isEmpty())
        assertEquals("bypassPermissions", ClaudePermissionMode.FULL_ACCESS.cliValue)
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        assertEquals(ClaudePermissionMode.DEFAULT, ClaudePermissionMode.fromCliValue("nonsense"))
        assertEquals(ClaudePermissionMode.DEFAULT, ClaudePermissionMode.fromCliValue(null))
        assertEquals(ClaudePermissionMode.PLAN, ClaudePermissionMode.fromCliValue("plan"))
    }
}
