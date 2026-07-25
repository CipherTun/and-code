package com.opencode.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenCodeEventParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    fun parse(raw: String): OpenCodeEvent {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return OpenCodeEvent.Unknown("invalid", raw)
        val type = root["type"]?.jsonPrimitive?.content ?: return OpenCodeEvent.Unknown("missing-type", raw)
        val properties = root["properties"]?.jsonObject ?: JsonObject(emptyMap())

        return runCatching {
            when (type) {
                "server.connected" -> OpenCodeEvent.ServerConnected
                "message.part.updated" -> {
                    val part = json.decodeFromJsonElement(OpenCodePart.serializer(), properties["part"]!!.jsonObject)
                    OpenCodeEvent.MessagePartUpdated(part)
                }
                "message.part.delta" -> OpenCodeEvent.MessagePartDelta(
                    sessionId = properties["sessionID"]!!.jsonPrimitive.content,
                    messageId = properties["messageID"]!!.jsonPrimitive.content,
                    partId = properties["partID"]!!.jsonPrimitive.content,
                    field = properties["field"]!!.jsonPrimitive.content,
                    delta = properties["delta"]!!.jsonPrimitive.content
                )
                "permission.asked" -> OpenCodeEvent.PermissionAsked(
                    PermissionRequest(
                        id = properties["id"]!!.jsonPrimitive.content,
                        sessionId = properties["sessionID"]!!.jsonPrimitive.content,
                        permission = properties["permission"]!!.jsonPrimitive.content,
                        patterns = (properties["patterns"] as? JsonArray)
                            ?.mapNotNull { element -> (element as? JsonPrimitive)?.content }
                            .orEmpty(),
                        metadata = (properties["metadata"] as? JsonObject)
                            ?.entries
                            ?.associate { (key, value) -> key to value }
                            .orEmpty()
                    )
                )
                "question.asked" -> {
                    val questions = (properties["questions"] as? JsonArray)
                        ?.mapNotNull { element -> parseQuestionPrompt(element) }
                        .orEmpty()
                    require(questions.isNotEmpty()) { "question.asked requires at least one valid prompt" }

                    OpenCodeEvent.QuestionAsked(
                        QuestionRequest(
                            id = properties["id"]!!.jsonPrimitive.content,
                            sessionId = properties["sessionID"]!!.jsonPrimitive.content,
                            questions = questions,
                            multiple = (properties["multiple"] as? JsonPrimitive)?.boolean ?: false
                        )
                    )
                }
                "session.idle" -> OpenCodeEvent.SessionIdle(properties["sessionID"]!!.jsonPrimitive.content)
                "session.error" -> OpenCodeEvent.SessionError(
                    sessionId = (properties["sessionID"] as? JsonPrimitive)?.content,
                    message = properties["error"]?.toString()
                )
                else -> OpenCodeEvent.Unknown(type, raw)
            }
        }.getOrElse { OpenCodeEvent.Unknown(type, raw) }
    }

    private fun parseQuestionPrompt(element: JsonElement): QuestionPrompt? = when {
        element is JsonPrimitive && element.isString -> QuestionPrompt(question = element.content)
        element is JsonObject -> {
            val prompt = element
            val question = (prompt["question"] as? JsonPrimitive)?.content
            question?.let {
                QuestionPrompt(
                    question = it,
                    header = (prompt["header"] as? JsonPrimitive)?.content,
                    options = (prompt["options"] as? JsonArray)
                        ?.mapNotNull { option -> parseQuestionOption(option) }
                        .orEmpty(),
                    placeholder = (prompt["placeholder"] as? JsonPrimitive)?.content
                )
            }
        }
        else -> null
    }

    private fun parseQuestionOption(element: JsonElement): QuestionOption? = when {
        element is JsonPrimitive && element.isString -> QuestionOption(label = element.content)
        element is JsonObject -> {
            val option = element
            val label = (option["label"] as? JsonPrimitive)?.content
            label?.let {
                QuestionOption(
                    label = it,
                    description = (option["description"] as? JsonPrimitive)?.content
                )
            }
        }
        else -> null
    }
}
