package com.opencode.android.runtime.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class AntigravityAuthStart(val process: Process, val url: String? = null)

/**
 * Owns the official agy first-launch OAuth process. Authentication is not a subcommand: Google
 * documents launching `agy` with no arguments, then either opening a local browser or printing a
 * remote SSH URL and waiting for the returned code. The process and its token store remain inside
 * the Debian rootfs; only the URL and user-entered code cross the Android boundary.
 */
class AntigravityAuthCoordinator(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
) {
    sealed interface State {
        data object Idle : State

        data object Starting : State

        data class AwaitingBrowser(val url: String, val transcript: String) : State

        data object Verifying : State

        data class SignedIn(val detail: String = "Google") : State

        data class Failed(val message: String, val transcript: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var transcript = StringBuilder()

    /** Starts the no-argument agy TUI and returns immediately while its URL is discovered. */
    @Synchronized
    fun start(): AntigravityAuthStart {
        cancel()
        val runtime = installedRuntimeProvider() ?: error("Linux environment is not installed")
        val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
        val command = AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, emptyList(), pty = true)
        val started =
            ProcessBuilder(command)
                .directory(runtimeDirectory)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(
                        AntigravitySandboxLauncher.environment(
                            runtime,
                            File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                        ),
                    )
                    // Force the documented remote OAuth path. CI is intentionally removed: agy
                    // treats CI as a headless test mode and skips the browser handoff entirely.
                    environment().remove("CI")
                    environment()["SSH_CONNECTION"] = "127.0.0.1 22 127.0.0.1 22"
                    environment()["SSH_CLIENT"] = "127.0.0.1 22 22"
                    environment()["SSH_TTY"] = "/dev/pts/0"
                }
                .start()
        process = started
        transcript = StringBuilder()
        mutableState.value = State.Starting
        scope.launch {
            runCatching {
                started.inputStream.bufferedReader().forEachChunk(::onOutput)
            }
            val exit = runCatching { started.waitFor() }.getOrDefault(-1)
            onProcessExit(exit)
        }
        // A local-browser launch has no useful URL for Android and otherwise leaves onboarding
        // spinning forever. Surface the exact compatibility state while retaining the official
        // process transcript for diagnostics.
        scope.launch {
            kotlinx.coroutines.delay(AUTH_DISCOVERY_TIMEOUT_MS)
            if (started.isAlive && mutableState.value is State.Starting) {
                val clean = transcript.toString().replace(ANSI_ESCAPE, "")
                mutableState.value =
                    if (clean.contains("local chrome mode", ignoreCase = true)) {
                        State.Failed(
                            "Official agy selected local browser mode; no remote OAuth URL was emitted in the Android PRoot session",
                            clean.takeLast(VISIBLE_TRANSCRIPT),
                        )
                    } else {
                        State.Failed("Antigravity did not emit a Google OAuth URL", clean.takeLast(VISIBLE_TRANSCRIPT))
                    }
            }
        }
        return AntigravityAuthStart(started)
    }

    fun submitCode(
        start: AntigravityAuthStart,
        code: String,
    ) {
        submitCode(code, start.process)
    }

    @Synchronized
    fun submitCode(code: String) {
        submitCode(code, process ?: return)
    }

    private fun submitCode(
        code: String,
        target: Process,
    ) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        mutableState.value = State.Verifying
        runCatching {
            target.outputStream.write((trimmed + "\n").toByteArray())
            target.outputStream.flush()
        }.onFailure {
            mutableState.value = State.Failed(it.message ?: "Could not submit the Antigravity code", transcript.toString())
        }
    }

    @Synchronized
    fun cancel() {
        process?.takeIf(Process::isAlive)?.destroyForcibly()
        process = null
        if (mutableState.value !is State.SignedIn) mutableState.value = State.Idle
    }

    /** Sends the documented `/logout` command through a short-lived official TUI process. */
    fun logout() {
        val runtime = installedRuntimeProvider() ?: return
        runCatching {
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            val command = AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, emptyList(), pty = true)
            val target =
                ProcessBuilder(command)
                    .directory(runtimeDirectory)
                    .redirectErrorStream(true)
                    .apply {
                        environment().putAll(
                            AntigravitySandboxLauncher.environment(
                                runtime,
                                File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                            ),
                        )
                        environment().remove("CI")
                    }
                    .start()
            target.outputStream.bufferedWriter().use {
                it.write("/logout\n")
                it.flush()
            }
            target.waitFor()
        }
        mutableState.value = State.Idle
    }

    private fun onOutput(chunk: String) {
        synchronized(this) {
            transcript.append(chunk)
            if (transcript.length > MAX_TRANSCRIPT) transcript.delete(0, transcript.length - MAX_TRANSCRIPT)
        }
        val clean = transcript.toString().replace(ANSI_ESCAPE, "")
        val url = URL_PATTERN.find(clean)?.value
        if (url != null && mutableState.value !is State.Verifying) {
            mutableState.value = State.AwaitingBrowser(url, clean.takeLast(VISIBLE_TRANSCRIPT))
        } else if (mutableState.value is State.AwaitingBrowser) {
            mutableState.value = (mutableState.value as State.AwaitingBrowser).copy(transcript = clean.takeLast(VISIBLE_TRANSCRIPT))
        }
    }

    private fun onProcessExit(exitCode: Int) {
        synchronized(this) { process = null }
        val clean = transcript.toString().replace(ANSI_ESCAPE, "")
        val authenticated = runCatching { verifyModels() }.getOrDefault(false)
        mutableState.value =
            when {
                authenticated -> State.SignedIn()
                exitCode == 0 -> State.Failed("Antigravity sign-in did not complete", clean.takeLast(VISIBLE_TRANSCRIPT))
                else -> State.Failed("Antigravity sign-in stopped (exit code $exitCode)", clean.takeLast(VISIBLE_TRANSCRIPT))
            }
    }

    private fun verifyModels(): Boolean {
        val runtime = installedRuntimeProvider() ?: return false
        val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
        val command = AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, listOf("models"), pty = false)
        val target =
            ProcessBuilder(command)
                .directory(runtimeDirectory)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(
                        AntigravitySandboxLauncher.environment(runtime, File(runtimeDirectory, "proot-tmp").apply { mkdirs() }),
                    )
                }
                .start()
        val output = target.inputStream.bufferedReader().readText()
        target.waitFor()
        return target.exitValue() == 0 && output.isNotBlank() && !output.contains("Please sign in", ignoreCase = true)
    }

    private fun java.io.BufferedReader.forEachChunk(onChunk: (String) -> Unit) {
        val buffer = CharArray(1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            if (read > 0) onChunk(String(buffer, 0, read))
        }
    }

    private companion object {
        const val MAX_TRANSCRIPT = 8_000
        const val VISIBLE_TRANSCRIPT = 1_200
        const val AUTH_DISCOVERY_TIMEOUT_MS = 15_000L
        val ANSI_ESCAPE = Regex("\\u001B\\[[;?\\d]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*\\u0007")
        val URL_PATTERN = Regex("https://[^\\s\\\"'()\\[\\]]+")
    }
}
