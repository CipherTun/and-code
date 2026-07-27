package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeModel
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.ProviderCatalog
import kotlinx.serialization.json.JsonPrimitive

/**
 * Models offered for Antigravity, read from `agy models`.
 *
 * Unlike Claude Code, the official CLI does enumerate what the signed-in account can actually use.
 * Its output is one `<base name> (<variant>)` label per line, for example `Gemini 3.1 Pro (High)` or
 * `Claude Sonnet 4.6 (Thinking)` - the same base-model-plus-variant shape [ClaudeModels] already
 * exposes through [OpenCodeModel.variants], so the existing model picker needs no changes.
 */
object AntigravityModels {
    const val PROVIDER_ID = "antigravity"

    /** Shown until `agy models` has run once for a signed-in account. */
    private const val FALLBACK_MODEL = "default"
    private val EFFORT_WORDS = setOf("low", "medium", "high")

    data class Entry(val base: String, val variant: String?)

    private val LABEL_PATTERN = Regex("^(.+?)\\s*\\(([^()]+)\\)$")

    /** Parses one model per non-blank line; a line without a `(variant)` suffix has no variant. */
    fun parse(output: String): List<Entry> =
        output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val match = LABEL_PATTERN.matchEntire(line)
                if (match != null) Entry(match.groupValues[1].trim(), match.groupValues[2].trim()) else Entry(line, null)
            }
            .toList()

    /**
     * Groups parsed entries by base model name, in the order `agy models` printed them.
     *
     * An empty [entries] list (not signed in yet, or `agy models` failed) falls back to the single
     * placeholder model the picker showed before this existed, rather than an empty picker.
     */
    fun catalog(entries: List<Entry>): ProviderCatalog {
        if (entries.isEmpty()) {
            return ProviderCatalog(
                all =
                    listOf(
                        OpenCodeProvider(
                            PROVIDER_ID,
                            "Antigravity",
                            mapOf(FALLBACK_MODEL to OpenCodeModel(FALLBACK_MODEL, PROVIDER_ID, "Account default")),
                        ),
                    ),
                default = mapOf(PROVIDER_ID to FALLBACK_MODEL),
                connected = listOf(PROVIDER_ID),
            )
        }
        val grouped = linkedMapOf<String, MutableList<String>>()
        entries.forEach {
                entry ->
            grouped.getOrPut(entry.base) { mutableListOf() }.also { if (entry.variant != null) it.add(entry.variant) }
        }
        val models =
            grouped.entries.associate { (base, variants) ->
                base to
                    OpenCodeModel(
                        id = base,
                        providerId = PROVIDER_ID,
                        name = base,
                        variants = variants.associateWith { JsonPrimitive(it) },
                    )
            }
        return ProviderCatalog(
            all = listOf(OpenCodeProvider(PROVIDER_ID, "Antigravity", models)),
            default = mapOf(PROVIDER_ID to grouped.keys.first()),
            connected = listOf(PROVIDER_ID),
        )
    }

    /**
     * CLI arguments for [model] and [variant].
     *
     * `--effort low|medium|high` is documented separately from `--model`, and it matches exactly the
     * variant words Gemini models print (`(High)`, `(Medium)`, `(Low)`), so those are sent through the
     * dedicated flag. A variant that is not one of those three words - `(Thinking)` on the Claude and
     * GPT-OSS entries - has no equivalent flag, so the full label is sent as `--model` instead and no
     * `--effort` is added.
     */
    fun cliArgs(
        model: String?,
        variant: String?,
    ): List<String> {
        val base = model?.takeIf(String::isNotBlank) ?: return emptyList()
        if (base == FALLBACK_MODEL) return emptyList()
        val effort = variant?.trim()?.takeIf { it.lowercase() in EFFORT_WORDS }
        return if (effort != null) {
            listOf("--model", base, "--effort", effort.lowercase())
        } else if (!variant.isNullOrBlank()) {
            listOf("--model", "$base ($variant)")
        } else {
            listOf("--model", base)
        }
    }
}
