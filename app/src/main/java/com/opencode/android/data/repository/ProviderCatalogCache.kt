package com.opencode.android.data.repository

import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.ProviderCatalog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk cache for a runtime's provider catalogue.
 *
 * OpenCode's `/provider` response is around 4 MB — 172 providers and 5,700 models — and fetching
 * and parsing it is what made the model picker open late. Only connected providers ever have their
 * models read; the rest are listed by name so the user can connect them. Dropping the models of
 * everything not connected takes the stored copy to roughly 14 KB, which is cheap enough to load
 * before the first frame.
 *
 * The cache is keyed on the runtime and its version, so an OpenCode upgrade discards it. A change
 * to the connected set does too: connecting a provider is exactly when its models start mattering.
 */
class ProviderCatalogCache(
    private val directory: File,
    private val json: Json,
) {
    fun read(
        runtimeId: String,
        version: String,
    ): ProviderCatalog? {
        val stored = runCatching { json.decodeFromString<Entry>(file(runtimeId).readText()) }.getOrNull() ?: return null
        return stored.catalog.takeIf { stored.version == version }
    }

    fun write(
        runtimeId: String,
        version: String,
        catalog: ProviderCatalog,
    ) {
        runCatching {
            directory.mkdirs()
            file(runtimeId).writeText(json.encodeToString(Entry(version, trim(catalog))))
        }
    }

    /** True when [catalog] describes a different set of connected providers than the cache holds. */
    fun isStale(
        runtimeId: String,
        version: String,
        catalog: ProviderCatalog,
    ): Boolean = read(runtimeId, version)?.connected?.toSet() != catalog.connected.toSet()

    private fun file(runtimeId: String) = File(directory, "providers-${runtimeId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private fun trim(catalog: ProviderCatalog): ProviderCatalog {
        val connected = catalog.connected.toSet()
        return catalog.copy(
            all =
                catalog.all.map { provider ->
                    if (provider.id in connected) provider else OpenCodeProvider(provider.id, provider.name)
                },
        )
    }

    @kotlinx.serialization.Serializable
    private data class Entry(
        val version: String,
        val catalog: ProviderCatalog,
    )
}
