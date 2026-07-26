package com.opencode.android.runtime.local

import java.io.File
import java.util.regex.Pattern

data class AntigravityAuthStart(val process: Process, val url: String?)

/** Remote OAuth is intentionally kept in the PTY; credentials never cross into Android storage. */
class AntigravityAuthCoordinator(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
) {
    fun start(): AntigravityAuthStart {
        val runtime = installedRuntimeProvider() ?: error("Linux environment is not installed")
        val command = AntigravitySandboxLauncher.command(runtime, "/workspace", listOf("auth", "login"), pty = true)
        val process = ProcessBuilder(command).directory(runtimeDirectory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLine().orEmpty()
        return AntigravityAuthStart(process, URL_PATTERN.matcher(output).takeIf { it.find() }?.group())
    }

    fun submitCode(start: AntigravityAuthStart, code: String) {
        start.process.outputStream.bufferedWriter().use { it.write(code.trim() + "\n"); it.flush() }
    }

    fun logout() {
        val runtime = installedRuntimeProvider() ?: return
        val command = AntigravitySandboxLauncher.command(runtime, "/workspace", listOf("logout"), pty = false)
        ProcessBuilder(command).directory(runtimeDirectory).start().waitFor()
    }

    companion object {
        private val URL_PATTERN = Pattern.compile("https://[^\\s]+")
    }
}
