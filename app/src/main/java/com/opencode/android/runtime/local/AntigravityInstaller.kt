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
                source.copyTo(destination, overwrite = true)
                require(destination.setExecutable(true, false) || destination.canExecute()) { "Unable to mark agy executable" }
                onProgress(1f)
                archive.delete()
                destination
            } finally {
                extraction.deleteRecursively()
            }
        }
}
