package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeMessageError
import com.yugahashimoto.andcode.core.api.OpenCodePart
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatErrorPartTest {
    @Test
    fun `maps a retry part carrying a provider error to an error part`() {
        val part =
            OpenCodePart(
                id = "p1",
                type = "retry",
                error =
                    OpenCodeMessageError(
                        name = "APIError",
                        data = mapOf("message" to JsonPrimitive("Rate limit exceeded (HTTP 429)")),
                    ),
            )

        val chat = part.toChatPart() as ChatPart.Error
        assertEquals("p1", chat.id)
        assertEquals("Rate limit exceeded (HTTP 429)", chat.message)
    }

    @Test
    fun `drops a retry part without an error message`() {
        val part = OpenCodePart(id = "p2", type = "retry", error = OpenCodeMessageError(name = "APIError"))

        assertNull(part.toChatPart())
    }

    @Test
    fun `drops an error part whose message is only whitespace`() {
        val part =
            OpenCodePart(
                id = "p3",
                type = "retry",
                error = OpenCodeMessageError(name = "UnknownError", data = mapOf("message" to JsonPrimitive("   "))),
            )

        assertNull(part.toChatPart())
    }

    @Test
    fun `surfaces a message level error when the turn has no retry part`() {
        val error =
            OpenCodeMessageError(
                name = "APIError",
                data = mapOf("message" to JsonPrimitive("Rate limit exceeded (HTTP 429)")),
            )

        val parts = emptyList<ChatPart>().withMessageError("m1", error)

        val errorPart = parts.single() as ChatPart.Error
        assertEquals("m1-error", errorPart.id)
        assertEquals("Rate limit exceeded (HTTP 429)", errorPart.message)
    }

    @Test
    fun `keeps a failed assistant message with only an error visible in the timeline`() {
        val error =
            OpenCodeMessageError(
                name = "APIError",
                data = mapOf("message" to JsonPrimitive("invalid api key")),
            )
        val message = ChatMessage(id = "m2", isUser = false, parts = emptyList<ChatPart>().withMessageError("m2", error))

        val entries = groupConversationTimeline(listOf(message))

        assertEquals(1, entries.size)
        assertEquals("error:m2-error", entries.single().id)
        assertTrue(entries.single() is TimelineEntry.Error)
    }

    @Test
    fun `surfaces a message level error alongside streamed parts`() {
        val error =
            OpenCodeMessageError(
                name = "APIError",
                data =
                    mapOf(
                        "message" to JsonPrimitive("upstream provider exploded"),
                        "statusCode" to JsonPrimitive(500),
                    ),
            )
        val message =
            ChatMessage(
                id = "m3",
                isUser = false,
                parts =
                    listOf(ChatPart.Text(id = "t1", text = "partial answer"))
                        .withMessageError("m3", error),
            )

        val timeline = groupConversationTimeline(listOf(message))

        assertTrue(timeline.filterIsInstance<TimelineEntry.Error>().isNotEmpty())
        assertTrue(timeline.filterIsInstance<TimelineEntry.Body>().isNotEmpty())
    }
}
