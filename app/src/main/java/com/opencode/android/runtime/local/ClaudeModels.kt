package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeModel
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.ProviderCatalog

/**
 * Models offered for Claude Code's `--model`.
 *
 * The CLI has no command that enumerates models, so the picker offers the aliases the CLI documents
 * as input — those are a stable contract, unlike model versions, and always resolve to the newest
 * matching model. No version is hardcoded here.
 *
 * The name shown for each alias is the model id Claude itself reports (`system/init` and every
 * assistant message carry the resolved id), remembered per alias once a session has used it. Until
 * then the alias stands in.
 */
object ClaudeModels {
    const val PROVIDER_ID = "claude-code"

    /** Used until the user picks one; the mid-range alias is the safest assumption. */
    const val DEFAULT_MODEL = "sonnet"

    /** Aliases the CLI accepts, in the order the picker should show them. */
    private val ALIASES = listOf("fable", "opus", DEFAULT_MODEL, "haiku")

    /**
     * @param resolved alias to the model id Claude reported for it, as learned from run output.
     */
    fun catalog(resolved: Map<String, String>): ProviderCatalog =
        ProviderCatalog(
            all =
                listOf(
                    OpenCodeProvider(
                        id = PROVIDER_ID,
                        name = "Claude Code",
                        models =
                            ALIASES.associateWith { alias ->
                                OpenCodeModel(
                                    id = alias,
                                    providerId = PROVIDER_ID,
                                    name = resolved[alias] ?: alias.replaceFirstChar(Char::titlecase),
                                )
                            },
                    ),
                ),
            default = mapOf(PROVIDER_ID to DEFAULT_MODEL),
            connected = listOf(PROVIDER_ID),
        )

    /**
     * CLI argument for [modelId].
     *
     * An id this build does not know about is still passed through: the CLI validates it, and
     * refusing it here would strand a session that remembers a newer alias or a full model name.
     */
    fun cliModel(modelId: String?): String? = modelId?.takeIf(String::isNotBlank)
}
