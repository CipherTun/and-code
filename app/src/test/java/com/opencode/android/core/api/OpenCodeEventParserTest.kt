package com.opencode.android.core.api

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeEventParserTest {
    private val parser = OpenCodeEventParser()

    @Test
    fun `parses server connected event`() {
        val event = parser.parse("""{"type":"server.connected","properties":{}}""")
        assertTrue(event is OpenCodeEvent.ServerConnected)
    }

    @Test
    fun `parses streamed text part update`() {
        val event =
            parser.parse(
                """{"type":"message.part.updated","properties":{"part":{"id":"p1","sessionID":"s1","messageID":"m1","type":"text","text":"Hello"}}}""",
            ) as OpenCodeEvent.MessagePartUpdated

        assertEquals("s1", event.part.sessionId)
        assertEquals("Hello", event.part.text)
    }

    @Test
    fun `parses streamed text delta`() {
        val event =
            parser.parse(
                """{"type":"message.part.delta","properties":{"sessionID":"s1","messageID":"m1","partID":"p1","field":"text","delta":"Hello"}}""",
            ) as OpenCodeEvent.MessagePartDelta

        assertEquals("s1", event.sessionId)
        assertEquals("m1", event.messageId)
        assertEquals("p1", event.partId)
        assertEquals("text", event.field)
        assertEquals("Hello", event.delta)
    }

    @Test
    fun `parses tool part update preserving state map`() {
        val event =
            parser.parse(
                """{"type":"message.part.updated","properties":{"part":{"id":"p1","sessionID":"s1","messageID":"m1","type":"tool","tool":"bash","callID":"call-1","state":{"status":"running","input":{"command":"ls -la"}}}}}""",
            ) as OpenCodeEvent.MessagePartUpdated

        assertEquals("tool", event.part.type)
        assertEquals("bash", event.part.tool)
        assertEquals("call-1", event.part.callID)
        assertEquals(JsonPrimitive("running"), event.part.state?.get("status"))
        val input = event.part.state?.get("input")
        assertEquals(JsonPrimitive("ls -la"), (input as? kotlinx.serialization.json.JsonObject)?.get("command"))
    }

    @Test
    fun `parses permission request`() {
        val event =
            parser.parse(
                """{"type":"permission.asked","properties":{"id":"perm1","sessionID":"s1","permission":"bash","patterns":["git status"]}}""",
            ) as OpenCodeEvent.PermissionAsked

        assertEquals("perm1", event.request.id)
        assertEquals("bash", event.request.permission)
        assertEquals(listOf("git status"), event.request.patterns)
    }

    @Test
    fun `parses session idle event`() {
        val event =
            parser.parse(
                """{"type":"session.idle","properties":{"sessionID":"s1"}}""",
            ) as OpenCodeEvent.SessionIdle

        assertEquals("s1", event.sessionId)
    }

    @Test
    fun `keeps unknown event without crashing`() {
        val event = parser.parse("""{"type":"future.event","properties":{"value":1}}""")
        assertTrue(event is OpenCodeEvent.Unknown)
        assertEquals("future.event", (event as OpenCodeEvent.Unknown).type)
    }

    @Test
    fun `parses question asked with options`() {
        val event =
            parser.parse(
                """{"type":"question.asked","properties":{"id":"q-1","sessionID":"s1","multiple":true,"questions":[{"question":"Pick a folder","header":"Folder","options":[{"label":"src","description":"Source code"},{"label":"docs"}],"placeholder":"Type a path"}]}}""",
            ) as OpenCodeEvent.QuestionAsked

        val request = event.request
        assertEquals("q-1", request.id)
        assertEquals("s1", request.sessionId)
        assertTrue(request.multiple)
        assertEquals("Pick a folder", request.questions.single().question)
        assertEquals("Folder", request.questions.single().header)
        assertEquals("Type a path", request.questions.single().placeholder)
        assertEquals(listOf("src", "docs"), request.questions.single().options.map { it.label })
        assertEquals("Source code", request.questions.single().options.first().description)
    }

    @Test
    fun `parses question asked with primitive string prompts`() {
        val event =
            parser.parse(
                """{"type":"question.asked","properties":{"id":"q-2","sessionID":"s1","questions":["Continue?"]}}""",
            ) as OpenCodeEvent.QuestionAsked

        assertEquals("Continue?", event.request.questions.single().question)
        assertTrue(event.request.questions.single().options.isEmpty())
        assertTrue(!event.request.multiple)
    }

    @Test
    fun `malformed question event becomes unknown instead of throwing`() {
        val event = parser.parse("""{"type":"question.asked","properties":{}}""")
        assertTrue(event is OpenCodeEvent.Unknown)
        assertEquals("question.asked", (event as OpenCodeEvent.Unknown).type)
    }

    @Test
    fun `question event with no valid nested prompts becomes unknown`() {
        val event =
            parser.parse(
                """{"type":"question.asked","properties":{"id":"q-3","sessionID":"s1","questions":[{},{"options":[{"description":"Missing label"}]},42]}}""",
            )

        assertTrue(event is OpenCodeEvent.Unknown)
        assertEquals("question.asked", (event as OpenCodeEvent.Unknown).type)
    }

    @Test
    fun `unwraps the global event stream envelope`() {
        val event =
            parser.parse(
                """{"directory":"/workspace/project","project":"prj","payload":{"id":"evt_1","type":"message.part.delta","properties":{"sessionID":"s1","messageID":"m1","partID":"p1","field":"text","delta":"Hi"}}}""",
            ) as OpenCodeEvent.MessagePartDelta

        assertEquals("s1", event.sessionId)
        assertEquals("Hi", event.delta)
    }

    @Test
    fun `unwraps a permission request from the global event stream`() {
        val event =
            parser.parse(
                """{"directory":"/workspace/project","payload":{"id":"evt_2","type":"permission.asked","properties":{"id":"perm1","sessionID":"s1","permission":"bash","patterns":["git status"],"metadata":{}}}}""",
            ) as OpenCodeEvent.PermissionAsked

        assertEquals("perm1", event.request.id)
        assertEquals("s1", event.request.sessionId)
    }

    @Test
    fun `parses message updated event`() {
        val event =
            parser.parse(
                """{"type":"message.updated","properties":{"sessionID":"s1","info":{"id":"m1","sessionID":"s1","role":"user","time":{"created":1}}}}""",
            ) as OpenCodeEvent.MessageUpdated

        assertEquals("m1", event.info.id)
        assertEquals("user", event.info.role)
    }

    @Test
    fun `parses permission replied event`() {
        val event =
            parser.parse(
                """{"type":"permission.replied","properties":{"sessionID":"s1","requestID":"perm1","reply":"once"}}""",
            ) as OpenCodeEvent.PermissionReplied

        assertEquals("s1", event.sessionId)
        assertEquals("perm1", event.requestId)
    }

    @Test
    fun `parses session status event`() {
        val event =
            parser.parse(
                """{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}""",
            ) as OpenCodeEvent.SessionStatusChanged

        assertEquals("s1", event.sessionId)
        assertEquals("busy", event.status)
    }

    @Test
    fun `session error reports the readable message instead of raw json`() {
        val event =
            parser.parse(
                """{"type":"session.error","properties":{"sessionID":"s1","error":{"name":"ProviderAuthError","data":{"message":"missing api key"}}}}""",
            ) as OpenCodeEvent.SessionError

        assertEquals("s1", event.sessionId)
        assertEquals("ProviderAuthError: missing api key", event.message)
    }
}
