package com.opencode.android.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AntigravityInstaller(
    private val runtimeDirectory: File,
    private val abi: String,
    private val downloader: VerifiedRuntimeDownloader = VerifiedRuntimeDownloader(),
) {
    suspend fun install(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        onProgress: (Float) -> Unit = {},
    ): File = installInto(runtime.rootfs, onProgress)

    suspend fun installInto(
        rootfs: File,
        onProgress: (Float) -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            require(runtimeDirectory.usableSpace >= AntigravityManifest.MIN_FREE_BYTES) {
                "Antigravity needs at least 300 MB free space (available ${runtimeDirectory.usableSpace} bytes)"
            }
            val asset = AntigravityManifest.assetFor(abi)
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val archive = File(cache, asset.name)
            downloader.download(asset.url, archive, asset.sha256, asset.sizeBytes) { progress ->
                progress?.let { onProgress(it * 0.75f) }
            }
            val extraction = File(runtimeDirectory, "antigravity-extract-${System.nanoTime()}").apply { mkdirs() }
            try {
                archive.inputStream().use { RuntimeArchive.extractTarGz(it, extraction) }
                val source =
                    extraction.walkTopDown().firstOrNull { it.isFile && (it.name == AntigravityManifest.BINARY_NAME || it.name == "antigravity") }
                        ?: error("Official Antigravity archive did not contain an agy binary")
                val destination = File(rootfs, "usr/local/bin/agy")
                destination.parentFile?.mkdirs()
                val candidate = File(destination.parentFile, "agy.new-${System.nanoTime()}")
                val backup = File(destination.parentFile, "agy.rollback")
                runCatching {
                    source.copyTo(candidate, overwrite = true)
                    require(candidate.setExecutable(true, false) || candidate.canExecute()) { "Unable to mark agy executable" }
                    backup.delete()
                    if (destination.exists()) require(destination.renameTo(backup)) { "Unable to stage the previous agy binary" }
                    require(candidate.renameTo(destination)) { "Unable to activate the verified agy binary" }
                    backup.delete()
                }.onFailure { error ->
                    candidate.delete()
                    if (!destination.exists() && backup.exists()) backup.renameTo(destination)
                    throw error
                }
                onProgress(1f)
                archive.delete()
                destination
            } finally {
                extraction.deleteRecursively()
            }
        }
}
