package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeModel
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.ProviderCatalog

/**
 * Models the Claude Code CLI accepts for `--model`.
 *
 * The CLI takes either an alias ("opus", "sonnet") or a full model name, and resolves the alias to
 * whatever the newest matching model is. Aliases are published rather than pinned ids so the list
 * does not go stale every time a new model ships.
 */
object ClaudeModels {
    const val PROVIDER_ID = "claude-code"

    const val DEFAULT_ALIAS = "default"

    /**
     * Alias to display name, in the order the picker should show them.
     *
     * Model names are proper nouns and stay untranslated; only the default entry is a label.
     */
    private val MODEL_ALIASES = linkedMapOf("fable" to "Fable", "opus" to "Opus", "sonnet" to "Sonnet", "haiku" to "Haiku")

    private fun aliases(accountDefaultName: String) = linkedMapOf(DEFAULT_ALIAS to accountDefaultName) + MODEL_ALIASES

    fun catalog(accountDefaultName: String): ProviderCatalog =
        ProviderCatalog(
            all =
                listOf(
                    OpenCodeProvider(
                        id = PROVIDER_ID,
                        name = "Claude Code",
                        models =
                            aliases(accountDefaultName).mapValues { (id, name) ->
                                OpenCodeModel(id = id, providerId = PROVIDER_ID, name = name)
                            },
                    ),
                ),
            default = mapOf(PROVIDER_ID to DEFAULT_ALIAS),
            connected = listOf(PROVIDER_ID),
        )

    /** CLI argument for [alias], or null when the account default should stand. */
    fun cliModel(alias: String?): String? = alias?.takeIf { it in MODEL_ALIASES }
}
