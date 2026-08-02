package com.yugahashimoto.andcode.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsReporterTest {
    @Test
    fun `runtime session completed event has a stable name and no user data`() {
        val event = AnalyticsEvents.runtimeSessionCompleted()

        assertEquals("runtime_session_completed", event.name)
        assertTrue(event.parameters.isEmpty())
    }

    @Test
    fun `runtime session error event has a stable name and no user data`() {
        val event = AnalyticsEvents.runtimeSessionError()

        assertEquals("runtime_session_error", event.name)
        assertTrue(event.parameters.isEmpty())
    }
}
