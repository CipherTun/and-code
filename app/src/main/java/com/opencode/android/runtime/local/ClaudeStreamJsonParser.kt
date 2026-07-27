package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.OpenCodeMessage
import com.opencode.android.core.api.OpenCodeMessageInfo
import com.opencode.android.core.api.OpenCodePart
import com.opencode.android.core.api.OpenCodeTime
import com.opencode.android.core.api.OpenCodeTodo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Translates one line of Claude Code's `--output-format stream-json` into app events.
 *
 * The CLI emits a documented, newline-delimited JSON protocol, which is why the app talks to it in
 * print mode rather than scraping the interactive terminal UI: the TUI is a full-screen renderer
 * whose output has no stable line structure to parse.
 */
class ClaudeStreamJsonParser(
    private val sessionId: String,
    private val json: Json,
) {
    /** What the runtime should do with a parsed line, beyond emitting [events]. */
    data class Parsed(
        val events: List<OpenCodeEvent> = emptyList(),
        val messages: List<OpenCodeMessage> = emptyList(),
        val claudeSessionId: String? = null,
        /** Model id Claude reported for this run, e.g. "claude-sonnet-5". */
        val resolvedModel: String? = null,
        /** Latest TodoWrite contents, when this line carried one. */
        val todos: List<OpenCodeTodo>? = null,
        /** Slash commands and skills the CLI reports at startup. */
        val slashCommands: List<String>? = null,
        val skills: List<String>? = null,
        val turnFinished: Boolean = false,
        val errorMessage: String? = null,
    )

    private var currentMessageId: String? = null

    fun parse(line: String): Parsed {
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return Parsed()
        return when (root.string("type")) {
            "system" ->
                Parsed(
                    claudeSessionId = root.string("session_id"),
                    resolvedModel = root.string("model"),
                    slashCommands = root.stringList("slash_commands"),
                    skills = root.stringList("skills"),
                )
            "assistant" -> parseModelMessage(root, role = "assistant")
            "user" -> parseModelMessage(root, role = "user")
            "stream_event" -> parsePartialDelta(root)
            "result" -> parseResult(root)
            else -> Parsed()
        }
    }

    private fun parseModelMessage(
        root: JsonObject,
        role: String,
    ): Parsed {
        val message = root["message"]?.jsonObject ?: return Parsed()
        val messageId = message.string("id") ?: newMessageId()
        currentMessageId = messageId
        val content = message["content"]
        // A plain string content block is valid in the protocol for simple user turns.
        val blocks: List<JsonObject> =
            when (content) {
                is JsonArray -> content.mapNotNull { it as? JsonObject }
                is JsonPrimitive -> listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to content)))
                else -> emptyList()
            }
        val parts = blocks.mapNotNull { block -> parseContentBlock(messageId, block) }
        if (parts.isEmpty()) return Parsed()
        // Tool results arrive on a "user" message; surfacing them as assistant activity keeps the
        // transcript readable instead of interleaving fake user turns.
        val effectiveRole = if (role == "user") "assistant" else role
        return Parsed(
            events = parts.map { OpenCodeEvent.MessagePartUpdated(it) },
            messages =
                listOf(
                    OpenCodeMessage(
                        info =
                            OpenCodeMessageInfo(
                                id = messageId,
                                sessionId = sessionId,
                                role = effectiveRole,
                                time = now(),
                                agent = "claude",
                            ),
                        parts = parts,
                    ),
                ),
            claudeSessionId = root.string("session_id"),
            resolvedModel = message.string("model"),
            todos = blocks.firstNotNullOfOrNull(::parseTodos),
        )
    }

    private fun parsePartialDelta(root: JsonObject): Parsed {
        val event = root["event"]?.jsonObject ?: return Parsed()
        val delta = event["delta"]?.jsonObject ?: return Parsed()
        val text = delta.string("text") ?: delta.string("thinking") ?: return Parsed()
        val messageId = currentMessageId ?: newMessageId().also { currentMessageId = it }
        val field = if (delta.string("type") == "thinking_delta") "reasoning" else "text"
        return Parsed(
            events = listOf(OpenCodeEvent.MessagePartDelta(sessionId, messageId, "$messageId-$field", field, text)),
        )
    }

    private fun parseResult(root: JsonObject): Parsed {
        val claudeSessionId = root.string("session_id")
        val isError = root["is_error"]?.jsonPrimitive?.contentOrNull == "true"
        val subtype = root.string("subtype")
        if (isError || (subtype != null && subtype != "success")) {
            val message = root.string("result") ?: subtype ?: "Claude Code reported an error"
            return Parsed(
                events = listOf(OpenCodeEvent.SessionError(sessionId, message), OpenCodeEvent.SessionIdle(sessionId)),
                claudeSessionId = claudeSessionId,
                turnFinished = true,
                errorMessage = message,
            )
        }
        currentMessageId = null
        return Parsed(
            events = listOf(OpenCodeEvent.SessionIdle(sessionId)),
            claudeSessionId = claudeSessionId,
            turnFinished = true,
        )
    }

    private fun parseContentBlock(
        messageId: String,
        block: JsonObject,
    ): OpenCodePart? {
        val blockType = block.string("type") ?: return null
        val partId = block.string("id") ?: "$messageId-${block.string("tool_use_id") ?: UUID.randomUUID()}"
        return when (blockType) {
            "text" ->
                OpenCodePart(partId, sessionId, messageId, "text", text = block.string("text").orEmpty())
            "thinking" ->
                OpenCodePart(partId, sessionId, messageId, "reasoning", text = block.string("thinking").orEmpty())
            "tool_use" ->
                OpenCodePart(
                    id = partId,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = block.string("name") ?: "tool",
                    callID = partId,
                    state =
                        mapOf(
                            "status" to JsonPrimitive("running"),
                            "input" to (block["input"] ?: JsonObject(emptyMap())),
                        ),
                )
            "tool_result" -> {
                val callId = block.string("tool_use_id") ?: partId
                val failed = block["is_error"]?.jsonPrimitive?.contentOrNull == "true"
                OpenCodePart(
                    id = callId,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = "tool",
                    callID = callId,
                    state =
                        mapOf(
                            "status" to JsonPrimitive(if (failed) "error" else "completed"),
                            "output" to JsonPrimitive(block.contentText()),
                        ),
                )
            }
            else -> null
        }
    }

    /** TodoWrite carries the whole list each time, so the newest block is the current state. */
    private fun parseTodos(block: JsonObject): List<OpenCodeTodo>? {
        if (block.string("name") != "TodoWrite") return null
        val todos = (block["input"] as? JsonObject)?.get("todos") as? JsonArray ?: return null
        return todos.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val content = item.string("content") ?: item.string("activeForm") ?: return@mapNotNull null
            OpenCodeTodo(
                content = content,
                status = item.string("status") ?: "pending",
                priority = item.string("priority") ?: "medium",
            )
        }
    }

    private fun newMessageId(): String = "claude-${UUID.randomUUID()}"

    private fun now(): OpenCodeTime {
        val timestamp = System.currentTimeMillis()
        return OpenCodeTime(timestamp, timestamp)
    }

    private companion object {
        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.stringList(key: String): List<String>? =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        /** Tool result payloads are either a string or a list of content blocks. */
        fun JsonObject.contentText(): String =
            when (val content = this["content"]) {
                null -> ""
                is JsonPrimitive -> content.contentOrNull.orEmpty()
                is JsonArray ->
                    content.jsonArray.joinToString("\n") { element ->
                        (element as? JsonObject)?.let { it["text"] as? JsonPrimitive }?.contentOrNull
                            ?: element.toString()
                    }
                else -> content.toString()
            }
    }
}
