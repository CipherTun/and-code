package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeSkill

enum class SlashAction { NEW_CHAT, CLEAR, MODEL, AGENT, ATTACH, HELP }

data class SlashCommand(
    val name: String,
    val description: String,
    val action: SlashAction,
)

/**
 * One entry in the composer's slash-command popup: either an app-level command or a command/skill
 * the connected backend advertises. All of them end up inserting `/<name> ` into the input; the
 * send path then routes backend commands and skills through the runtime's command handling.
 */
sealed interface SlashSuggestion {
    val name: String
    val description: String

    data class App(val command: SlashCommand) : SlashSuggestion {
        override val name: String = command.name
        override val description: String = command.description
    }

    data class Backend(
        override val name: String,
        override val description: String,
        val isSkill: Boolean = false,
    ) : SlashSuggestion
}

object SlashCommandRegistry {
    val commands: List<SlashCommand> =
        listOf(
            SlashCommand("/new", "Start a new session", SlashAction.NEW_CHAT),
            SlashCommand("/clear", "Clear current conversation", SlashAction.CLEAR),
            SlashCommand("/model", "Switch model", SlashAction.MODEL),
            SlashCommand("/agent", "Switch agent", SlashAction.AGENT),
            SlashCommand("/attach", "Attach a file", SlashAction.ATTACH),
            SlashCommand("/help", "Show help", SlashAction.HELP),
        )

    /**
     * Builds the popup list: app commands first, then the backend's commands and skills, filtering
     * everything by what the user has typed so far.
     */
    fun suggestions(
        query: String,
        backendCommands: List<OpenCodeCommand>,
        backendSkills: List<OpenCodeSkill>,
    ): List<SlashSuggestion> {
        val trimmed = query.trim()

        fun matches(name: String): Boolean = trimmed.isEmpty() || name.startsWith(trimmed, ignoreCase = true)

        val app = commands.filter { matches(it.name) }.map(SlashSuggestion::App)
        val backend =
            backendCommands
                .filter { matches("/${it.name}") }
                .map { SlashSuggestion.Backend("/${it.name}", it.description.orEmpty()) } +
                backendSkills
                    .filter { matches("/${it.name}") }
                    .map { SlashSuggestion.Backend("/${it.name}", it.description.orEmpty(), isSkill = true) }
        return app + backend.sortedBy { it.name }
    }
}
