package com.opencode.android.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActivityGroupTest {
    private fun tool(
        id: String,
        name: String,
        status: ToolStatus = ToolStatus.COMPLETED,
        title: String? = null,
    ) = ChatPart.Tool(id = id, name = name, status = status, title = title)

    @Test
    fun `collapses a consecutive run of tools into one activity entry`() {
        val entries =
            groupAssistantTimeline(
                listOf(
                    tool("t1", "bash"),
                    ChatPart.Reasoning("r1", "thinking"),
                    tool("t2", "read"),
                ),
            )

        assertEquals(1, entries.size)
        val activity = entries.single() as TimelineEntry.Activity
        assertEquals(3, activity.parts.size)
    }

    @Test
    fun `body text splits activity into separate groups`() {
        val entries =
            groupAssistantTimeline(
                listOf(
                    tool("t1", "bash"),
                    ChatPart.Text("x1", "Exploring the codebase."),
                    tool("t2", "edit"),
                    tool("t3", "write"),
                    ChatPart.Text("x2", "Done."),
                ),
            )

        assertEquals(4, entries.size)
        assertEquals(listOf("t1"), (entries[0] as TimelineEntry.Activity).parts.map { it.id })
        assertEquals("Exploring the codebase.", (entries[1] as TimelineEntry.Body).part.text)
        assertEquals(listOf("t2", "t3"), (entries[2] as TimelineEntry.Activity).parts.map { it.id })
        assertEquals("Done.", (entries[3] as TimelineEntry.Body).part.text)
    }

    @Test
    fun `blank text parts do not split a run`() {
        val entries =
            groupAssistantTimeline(
                listOf(
                    tool("t1", "bash"),
                    ChatPart.Text("x1", ""),
                    tool("t2", "bash"),
                    ChatPart.Text("x2", "   "),
                    tool("t3", "bash"),
                ),
            )

        assertEquals(1, entries.size)
        assertEquals(listOf("t1", "t2", "t3"), (entries.single() as TimelineEntry.Activity).parts.map { it.id })
    }

    @Test
    fun `activity id is the id of its first part so compose keys stay stable`() {
        val parts = listOf(tool("first", "bash"), tool("second", "read"))

        assertEquals("first", groupAssistantTimeline(parts).single().id)
        // Appending to a growing run must not change the group identity.
        assertEquals("first", groupAssistantTimeline(parts + tool("third", "read")).single().id)
    }

    @Test
    fun `counts tools by category and treats patch parts as edits`() {
        val summary =
            summarizeActivity(
                listOf(
                    tool("t1", "bash"),
                    tool("t2", "Bash"),
                    tool("t3", "read"),
                    tool("t4", "grep"),
                    tool("t5", "glob"),
                    tool("t6", "task"),
                    tool("t7", "todowrite"),
                    ChatPart.Patch("p1", listOf("A.kt")),
                    ChatPart.Reasoning("r1", "thinking"),
                ),
            )

        assertEquals(2, summary.counts[ToolCategory.COMMAND])
        assertEquals(3, summary.counts[ToolCategory.READ])
        assertEquals(1, summary.counts[ToolCategory.EDIT])
        assertEquals(1, summary.counts[ToolCategory.SUBAGENT])
        assertEquals(1, summary.counts[ToolCategory.OTHER])
        assertEquals(1, summary.reasoningCount)
        assertNull(summary.running)
    }

    @Test
    fun `surfaces the first in-flight tool while the run is still executing`() {
        val summary =
            summarizeActivity(
                listOf(
                    tool("t1", "bash"),
                    tool("t2", "bash", status = ToolStatus.RUNNING, title = "gh run watch"),
                    tool("t3", "read", status = ToolStatus.PENDING),
                ),
            )

        assertEquals("t2", summary.running?.id)
        assertEquals("gh run watch", summary.running?.title)
    }

    @Test
    fun `flags a run that contains a failed tool`() {
        val summary = summarizeActivity(listOf(tool("t1", "bash"), tool("t2", "bash", status = ToolStatus.ERROR)))

        assertTrue(summary.hasError)
        assertNull(summary.running)
    }

    @Test
    fun `resolves an activity group by id across messages`() {
        val messages =
            listOf(
                ChatMessage(id = "m0", isUser = true, parts = listOf(ChatPart.Text("u1", "go"))),
                ChatMessage(
                    id = "m1",
                    isUser = false,
                    parts =
                        listOf(
                            tool("a1", "bash"),
                            ChatPart.Text("x1", "Now editing."),
                            tool("b1", "edit"),
                            tool("b2", "write"),
                        ),
                ),
            )

        assertEquals(listOf("b1", "b2"), findActivityParts(messages, "b1").map { it.id })
        assertEquals(listOf("a1"), findActivityParts(messages, "a1").map { it.id })
        assertTrue(findActivityParts(messages, "nope").isEmpty())
    }

    @Test
    fun `resolving a group picks up steps appended while the run is still going`() {
        val growing =
            ChatMessage(id = "m1", isUser = false, parts = listOf(tool("a1", "bash"), tool("a2", "read")))

        assertEquals(listOf("a1", "a2"), findActivityParts(listOf(growing), "a1").map { it.id })
    }

    @Test
    fun `reasoning-only run is not empty`() {
        val summary = summarizeActivity(listOf(ChatPart.Reasoning("r1", "thinking")))

        assertTrue(summary.counts.isEmpty())
        assertEquals(1, summary.reasoningCount)
        assertTrue(!summary.isEmpty)
    }
}
