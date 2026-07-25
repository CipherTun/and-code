package com.opencode.android.feature.chat

/**
 * A single row of the assistant timeline: either body text, or a collapsed run of
 * reasoning/tool/patch parts that the user can expand to inspect.
 */
sealed interface TimelineEntry {
    val id: String

    data class Body(override val id: String, val part: ChatPart.Text) : TimelineEntry

    data class Activity(override val id: String, val parts: List<ChatPart>) : TimelineEntry
}

/** Broad buckets used to summarise a run of tool calls in one line. */
enum class ToolCategory { COMMAND, READ, EDIT, SUBAGENT, OTHER }

/**
 * Counts per category plus the part that is currently in flight, if any.
 *
 * While a run is still executing we surface the running step by name instead of a count, so the
 * user can see what the agent is doing right now.
 */
data class ActivitySummary(
    val counts: Map<ToolCategory, Int>,
    val reasoningCount: Int,
    val running: ChatPart.Tool?,
    val hasError: Boolean,
) {
    val isEmpty: Boolean
        get() = counts.isEmpty() && reasoningCount == 0
}

/**
 * Collapses consecutive reasoning/tool/patch parts into a single [TimelineEntry.Activity], keeping
 * body text as its own entry so the narrative order of the answer is preserved.
 *
 * Blank text parts do not split a run: the stream emits an empty text part before the assistant
 * starts writing, and treating it as a separator would break every run into single-step groups.
 */
fun groupAssistantTimeline(parts: List<ChatPart>): List<TimelineEntry> {
    val entries = mutableListOf<TimelineEntry>()
    val pending = mutableListOf<ChatPart>()

    fun flush() {
        if (pending.isEmpty()) return
        entries += TimelineEntry.Activity(pending.first().id, pending.toList())
        pending.clear()
    }

    parts.forEach { part ->
        if (part is ChatPart.Text && part.text.isNotBlank()) {
            flush()
            entries += TimelineEntry.Body(part.id, part)
        } else if (part !is ChatPart.Text) {
            pending += part
        }
    }
    flush()
    return entries
}

/**
 * Re-resolves an activity group by id against the current messages.
 *
 * The detail sheet holds only the group id rather than a captured list, so a run that is still
 * executing keeps streaming new steps into the open sheet. Group ids are stable as a run grows —
 * see [groupAssistantTimeline].
 */
fun findActivityParts(
    messages: List<ChatMessage>,
    groupId: String,
): List<ChatPart> =
    messages
        .asSequence()
        .filterNot { it.isUser }
        .flatMap { groupAssistantTimeline(it.parts).asSequence() }
        .filterIsInstance<TimelineEntry.Activity>()
        .firstOrNull { it.id == groupId }
        ?.parts
        .orEmpty()

fun summarizeActivity(parts: List<ChatPart>): ActivitySummary {
    val counts = mutableMapOf<ToolCategory, Int>()
    var reasoning = 0
    var running: ChatPart.Tool? = null
    var hasError = false

    parts.forEach { part ->
        when (part) {
            is ChatPart.Reasoning -> reasoning++
            is ChatPart.Patch -> counts.increment(ToolCategory.EDIT)
            is ChatPart.Tool -> {
                counts.increment(part.name.toToolCategory())
                if (part.status == ToolStatus.ERROR) hasError = true
                if (running == null && (part.status == ToolStatus.RUNNING || part.status == ToolStatus.PENDING)) {
                    running = part
                }
            }
            is ChatPart.Text -> Unit
        }
    }
    return ActivitySummary(counts = counts, reasoningCount = reasoning, running = running, hasError = hasError)
}

fun String.toToolCategory(): ToolCategory =
    when (lowercase()) {
        "bash", "shell" -> ToolCategory.COMMAND
        "read", "glob", "grep", "list", "webfetch" -> ToolCategory.READ
        "edit", "write", "patch", "multiedit" -> ToolCategory.EDIT
        "task" -> ToolCategory.SUBAGENT
        else -> ToolCategory.OTHER
    }

private fun MutableMap<ToolCategory, Int>.increment(category: ToolCategory) {
    this[category] = (this[category] ?: 0) + 1
}
