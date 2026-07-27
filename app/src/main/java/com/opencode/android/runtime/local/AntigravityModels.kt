package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeModel
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.ProviderCatalog
import kotlinx.serialization.json.JsonPrimitive

/**
 * Models offered for Antigravity, read from `agy models`.
 *
 * Unlike Claude Code, the official CLI does enumerate what the signed-in account can actually use.
 * The pinned official release (1.1.7, [AntigravityManifest.VERSION]) prints one lowercase, hyphenated
 * slug per line - `gemini-3.1-pro-high`, `claude-opus-4-6-thinking`, `claude-sonnet-4-6` - confirmed
 * live against a signed-in install of that exact version. Earlier builds of this parser were tested
 * against a different locally-installed `agy` version (1.1.1) that prints `Title Case (Variant)`
 * labels instead; that format does not appear in the version this app actually ships, which is why
 * the model picker previously showed nothing useful.
 */
object AntigravityModels {
    const val PROVIDER_ID = "antigravity"

    /** Shown until `agy models` has run once for a signed-in account. */
    private const val FALLBACK_MODEL = "default"

    /** The only suffixes `agy 1.1.7` appends to a base model slug. */
    private val VARIANT_SUFFIXES = setOf("high", "medium", "low", "thinking")

    data class Entry(val base: String, val variant: String?)

    /**
     * Parses one model per non-blank line.
     *
     * A slug's last hyphen-separated segment is a variant only when it is one of the CLI's known
     * suffix words; `claude-sonnet-4-6`'s last segment is `6`, which is not a variant word, so that
     * slug is its own base model with no variant - splitting on every hyphen would otherwise cut
     * version numbers like `4-6` apart.
     */
    fun parse(output: String): List<Entry> =
        output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { slug ->
                val segments = slug.split('-')
                val last = segments.last()
                if (segments.size > 1 && last in VARIANT_SUFFIXES) {
                    Entry(segments.dropLast(1).joinToString("-"), last)
                } else {
                    Entry(slug, null)
                }
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
        entries.forEach { entry ->
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
     * CLI arguments for [model] (a base id from [catalog]) and [variant].
     *
     * The base and variant were produced by splitting the CLI's own slug on a hyphen, so rejoining
     * them the same way reconstructs the exact slug `agy models` printed - no separate `--effort`
     * flag is needed or used, since the variant is already encoded in the slug itself.
     */
    fun cliArgs(
        model: String?,
        variant: String?,
    ): List<String> {
        val base = model?.takeIf(String::isNotBlank) ?: return emptyList()
        if (base == FALLBACK_MODEL) return emptyList()
        val slug = if (!variant.isNullOrBlank()) "$base-$variant" else base
        return listOf("--model", slug)
    }
}
