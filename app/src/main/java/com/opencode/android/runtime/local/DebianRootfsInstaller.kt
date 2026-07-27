package com.opencode.android.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class DebianRootfsInstaller(
    private val runtimeDirectory: File,
    private val abi: String,
    private val downloader: VerifiedRuntimeDownloader,
    private val httpClient: OkHttpClient,
) {
    suspend fun installInto(
        destination: File,
        onProgress: (Float) -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            val asset = DebianRootfsManifest.assetFor(abi)
            val token = accessToken()
            val archive = File(runtimeDirectory, "cache/debian-bookworm-slim-$abi.tar.gz").apply { parentFile?.mkdirs() }
            if (archive.length() != asset.sizeBytes || runCatching { RuntimeArchive.verifySha256(archive, asset.sha256) }.isFailure) {
                downloader.download(
                    url = asset.blobUrl,
                    destination = archive,
                    expectedSha256 = asset.sha256,
                    expectedSizeBytes = asset.sizeBytes,
                    headers = mapOf("Authorization" to "Bearer $token"),
                    onProgress = { onProgress((it ?: 0f) * 0.75f) },
                )
            } else {
                onProgress(0.75f)
            }
            val extracted = File(destination.parentFile, "${destination.name}.new-${System.nanoTime()}").apply { mkdirs() }
            try {
                archive.inputStream().use { RuntimeArchive.extractTarGz(it, extracted) }
                configure(extracted)
                ensureGlibcLoader(extracted)
                installPtyUtility(extracted)
                destination.deleteRecursively()
                require(extracted.renameTo(destination)) { "Unable to activate Debian Antigravity rootfs" }
                onProgress(1f)
                archive.delete()
                destination
            } finally {
                extracted.deleteRecursively()
            }
        }

    private fun accessToken(): String {
        val request = Request.Builder().url(DebianRootfsManifest.tokenUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Debian registry token request failed with HTTP ${response.code}" }
            val body = requireNotNull(response.body).string()
            return Json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.content
                ?: error("Debian registry token response did not contain a token")
        }
    }

    private fun configure(rootfs: File) {
        listOf("root", "tmp", "workspace", "dev", "proc", "sys", "system").forEach { File(rootfs, it).mkdirs() }
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "root/.config/antigravity").mkdirs()
        File(rootfs, "root/.gemini").mkdirs()
        val settings = File(rootfs, "root/.gemini/antigravity-cli/settings.json")
        settings.parentFile?.mkdirs()
        settings.writeText(
            """
            {
              "altScreenMode": "never",
              "notifications": false,
              "enableTelemetry": false,
              "toolPermission": "request-review",
              "trustedWorkspaces": ["/workspace"]
            }
            """.trimIndent()
                +
                "\n",
        )
    }

    private suspend fun installPtyUtility(rootfs: File) {
        val asset = DebianRootfsManifest.bsdutilsFor(abi)
        val packageFile =
            File(runtimeDirectory, "cache/${asset.name}-$abi.deb").apply {
                parentFile?.mkdirs()
            }
        if (packageFile.length() != asset.sizeBytes || runCatching { RuntimeArchive.verifySha256(packageFile, asset.sha256) }.isFailure) {
            downloader.download(
                url = asset.url,
                destination = packageFile,
                expectedSha256 = asset.sha256,
                expectedSizeBytes = asset.sizeBytes,
            )
        }
        packageFile.inputStream().use { RuntimeArchive.extractDebianPackage(it, rootfs) }
        require(File(rootfs, "usr/bin/script").isFile) { "Debian PTY utility was not installed" }
        packageFile.delete()
    }

    private fun ensureGlibcLoader(rootfs: File) {
        val loaderName = if (abi == "arm64-v8a") "ld-linux-aarch64.so.1" else "ld-linux-x86-64.so.2"
        val source =
            listOf(
                File(rootfs, "lib/aarch64-linux-gnu/$loaderName"),
                File(rootfs, "lib/x86_64-linux-gnu/$loaderName"),
            ).firstOrNull { it.isFile }
                ?: error("Debian glibc loader is missing: $loaderName")
        val loader = File(rootfs, "lib/$loaderName")
        if (!loader.isFile) {
            loader.parentFile?.mkdirs()
            source.copyTo(loader, overwrite = true)
            loader.setExecutable(true, false)
        }
    }
}
