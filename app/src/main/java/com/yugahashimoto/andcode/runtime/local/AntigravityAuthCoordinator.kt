package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

data class AntigravityAuthStart(val process: Process, val url: String? = null)

/**
 * Owns the official agy first-launch OAuth process. Authentication is not a subcommand: Google
 * documents launching `agy` with no arguments, choosing "Google OAuth" in the sign-in chooser, then
 * opening the printed URL and pasting the code back. The process and its token store remain inside
 * the Debian rootfs; only the URL and the user-entered code cross the Android boundary.
 */
class AntigravityAuthCoordinator(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val githubToken: () -> String? = { null },
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

    /**
     * Frozen once the code field is live. Everything the TUI paints from that point on can contain
     * the authorization code the user typed, so it must never reach the UI or a log.
     */
    @Volatile private var diagnostics: String = ""

    @Volatile private var codeFieldLive = false

    @Volatile private var menuAnswered = false

    /**
     * Starts the no-argument agy TUI and returns once it is past its own vulnerable startup window.
     *
     * Blocks for up to [STARTUP_GRACE_MS] - callers must run this from a background thread, never
     * from the UI thread. See [AntigravityProcessGate] for why the wait exists.
     *
     * The already-signed-in check happens *before* the TUI is launched, never alongside it: two agy
     * processes running at once deadlock (see [AntigravityProcessGate]), so a poll that raced the
     * live TUI made the sign-in button hang itself with no visible progress at all.
     */
    @Synchronized
    fun start(): AntigravityAuthStart {
        cancel()
        val runtime = installedRuntimeProvider() ?: error("Linux environment is not installed")
        AntigravityGuestSettings.repair(runtime)
        if (verifyModels()) {
            mutableState.value = State.SignedIn()
            return AntigravityAuthStart(NoOpProcess)
        }
        val started =
            AntigravityProcessGate.acquireThenRelease(STARTUP_GRACE_MS) { launchTui(runtime) }
                ?: error("Antigravity is busy with another operation; try again in a moment")
        process = started
        transcript = StringBuilder()
        diagnostics = ""
        codeFieldLive = false
        menuAnswered = false
        mutableState.value = State.Starting
        scope.launch { driveLoginChooser(started) }
        scope.launch {
            runCatching { started.inputStream.bufferedReader().forEachChunk(::onOutput) }
            onProcessExit(runCatching { started.waitFor() }.getOrDefault(-1))
        }
        scope.launch { watchForDiscoveryTimeout(started) }
        return AntigravityAuthStart(started)
    }

    /**
     * "1. Google OAuth" is preselected, so a single Enter starts the flow. The chooser only appears
     * once the bundled language server is up, which is why this waits for the chooser to be painted
     * instead of pressing Enter on a fixed delay.
     */
    private suspend fun driveLoginChooser(started: Process) {
        val deadline = System.currentTimeMillis() + MENU_TIMEOUT_MS
        while (started.isAlive && System.currentTimeMillis() < deadline) {
            if (AntigravityAuthParser.isLoginMenuVisible(cleanTranscript())) {
                menuAnswered = true
                // Bubble Tea reads Enter as CR because it puts the PTY in raw mode.
                runCatching {
                    started.outputStream.write('\r'.code)
                    started.outputStream.flush()
                }
                return
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun watchForDiscoveryTimeout(started: Process) {
        delay(AUTH_DISCOVERY_TIMEOUT_MS)
        if (!started.isAlive || mutableState.value !is State.Starting) return
        val clean = cleanTranscript()
        mutableState.value =
            State.Failed(
                when {
                    AntigravityAuthParser.isLocalBrowserMode(clean) ->
                        "Antigravity selected local browser mode and did not print a sign-in URL"
                    !menuAnswered -> "Antigravity did not show its sign-in chooser"
                    else -> "Antigravity did not print a Google sign-in URL"
                },
                visibleDiagnostics(clean),
            )
        terminate(started)
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
            target.outputStream.write((trimmed + "\r").toByteArray())
            target.outputStream.flush()
        }.onFailure {
            mutableState.value = State.Failed(it.message ?: "Could not submit the Antigravity code", diagnostics)
            return
        }
        scope.launch { awaitVerification(target) }
    }

    /**
     * The official CLI stays running after a successful exchange, so completion is confirmed out of
     * band with `agy models` rather than by waiting for the process to exit.
     */
    private suspend fun awaitVerification(target: Process) {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (mutableState.value !is State.Verifying) return
            val clean = cleanTranscript()
            if (AntigravityAuthParser.isFailure(clean)) {
                mutableState.value = State.Failed("Antigravity rejected the authorization code", diagnostics)
                return
            }
            if (AntigravityAuthParser.isSignedIn(clean) || verifyModels()) {
                terminate(target)
                synchronized(this) { process = null }
                mutableState.value = State.SignedIn()
                return
            }
            delay(VERIFY_POLL_MS)
        }
        if (mutableState.value is State.Verifying) {
            mutableState.value = State.Failed("Antigravity sign-in did not complete in time", diagnostics)
        }
    }

    @Synchronized
    fun cancel() {
        process?.let(::terminateAsync)
        process = null
        codeFieldLive = false
        if (mutableState.value !is State.SignedIn) mutableState.value = State.Idle
    }

    /**
     * Signs out by clearing the guest token store, then verifying the CLI agrees.
     *
     * The documented `/logout` is a slash command typed into the interactive TUI, and driving it
     * through a PTY proved unreliable here for the same reasons the sign-in TUI did. Deleting the
     * token file the CLI itself writes under the guest `$HOME` is deterministic and stays inside the
     * app's own sandbox - it removes local credentials rather than reading or exporting them, and
     * nothing is copied to the Android side. Without a working sign-out there is no way to
     * re-authenticate at all once a token exists, which is exactly the state this got stuck in.
     */
    fun logout() {
        val runtime = installedRuntimeProvider() ?: return
        cancel()
        val rootfs = runtime.antigravityRootfs ?: runtime.rootfs
        runCatching { File(rootfs, GUEST_TOKEN_PATH).delete() }
        runtime.let { AntigravityGuestSettings.repair(it) }
        mutableState.value = State.Idle
    }

    /** True when the guest token store already satisfies the official CLI. */
    fun isSignedIn(): Boolean = verifyModels()

    /** Restores the signed-in state discovered from the guest token store after an app restart. */
    fun markSignedIn() {
        if (mutableState.value is State.Idle) mutableState.value = State.SignedIn()
    }

    private fun launchTui(runtime: LocalRuntimeInstaller.InstalledRuntime): Process {
        val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
        val command = AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, emptyList(), pty = true)
        return ProcessBuilder(command)
            .directory(runtimeDirectory)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(
                    AntigravitySandboxLauncher.environment(
                        runtime,
                        File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                        githubToken(),
                    ),
                )
                // agy treats CI as a headless test mode and skips the browser handoff entirely.
                environment().remove("CI")
            }
            .start()
    }

    private fun cleanTranscript(): String = AntigravityAuthParser.stripAnsi(synchronized(this) { transcript.toString() })

    private fun visibleDiagnostics(clean: String): String = AntigravityAuthParser.redact(clean).takeLast(VISIBLE_TRANSCRIPT)

    private fun onOutput(chunk: String) {
        synchronized(this) {
            transcript.append(chunk)
            if (transcript.length > MAX_TRANSCRIPT) transcript.delete(0, transcript.length - MAX_TRANSCRIPT)
        }
        val clean = cleanTranscript()
        if (!codeFieldLive) {
            diagnostics = visibleDiagnostics(clean)
            codeFieldLive = AntigravityAuthParser.isAwaitingCode(clean)
        }
        if (mutableState.value is State.Verifying || mutableState.value is State.SignedIn) return
        val url = AntigravityAuthParser.findOAuthUrl(clean)
        if (url != null) mutableState.value = State.AwaitingBrowser(url, diagnostics)
    }

    private fun onProcessExit(exitCode: Int) {
        synchronized(this) { process = null }
        when (mutableState.value) {
            is State.SignedIn -> return
            // Verification owns the terminal state once a code has been submitted.
            is State.Verifying -> return
            else -> Unit
        }
        mutableState.value =
            if (verifyModels()) {
                State.SignedIn()
            } else {
                State.Failed("Antigravity sign-in stopped (exit code $exitCode)", diagnostics)
            }
    }

    private fun verifyModels(): Boolean =
        runCatching {
            val runtime = installedRuntimeProvider() ?: return false
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            AntigravityProcessGate.exclusive {
                val command = AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, listOf("models"), pty = false)
                val target =
                    with(AntigravityProcessGate) {
                        ProcessBuilder(command)
                            .directory(runtimeDirectory)
                            .redirectErrorStream(true)
                            .withoutStdin()
                            .apply {
                                environment().putAll(
                                    AntigravitySandboxLauncher.environment(
                                        runtime,
                                        File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                        githubToken(),
                                    ),
                                )
                            }
                            .start()
                    }
                val output = AntigravityProcessGate.readWithTimeout(target, MODELS_TIMEOUT_SECONDS * 1000)
                if (output == null || !target.waitFor(5, TimeUnit.SECONDS)) {
                    terminate(target)
                    false
                } else {
                    target.exitValue() == 0 && output.isNotBlank() && !output.contains(NOT_LOGGED_IN, ignoreCase = true)
                }
            } ?: false
        }.getOrDefault(false)

    /**
     * Kills the whole guest process tree, blocking - only call this from a background coroutine.
     *
     * See [killAntigravityProcessTree] for why a plain `destroy()`/`destroyForcibly()` is not enough.
     */
    private fun terminate(target: Process) {
        killAntigravityProcessTree(target)
        if (target.isAlive) target.waitFor(GRACEFUL_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Same as [terminate], but never blocks the caller - for call sites that may run on the UI thread. */
    private fun terminateAsync(target: Process) {
        if (!target.isAlive) return
        scope.launch { terminate(target) }
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
        const val MAX_TRANSCRIPT = 16_000
        const val VISIBLE_TRANSCRIPT = 1_200
        const val POLL_INTERVAL_MS = 400L
        const val MENU_TIMEOUT_MS = 90_000L
        const val AUTH_DISCOVERY_TIMEOUT_MS = 120_000L
        const val VERIFY_POLL_MS = 2_000L
        const val VERIFY_TIMEOUT_MS = 120_000L
        const val MODELS_TIMEOUT_SECONDS = 90L
        const val GRACEFUL_KILL_TIMEOUT_MS = 3_000L

        /** How long [start] holds [AntigravityProcessGate] before letting the TUI run unattended. */
        const val STARTUP_GRACE_MS = 2_500L
        const val NOT_LOGGED_IN = "not logged into Antigravity"

        /** Where the official CLI keeps its token inside the guest `$HOME`. */
        const val GUEST_TOKEN_PATH = "root/.gemini/antigravity-cli/antigravity-oauth-token"
    }
}

/**
 * Stand-in for the sign-in TUI that [AntigravityAuthCoordinator.start] never had to launch because
 * the guest was already signed in. Callers only ever hold this to submit a code or cancel, and there
 * is nothing to do in either case.
 */
private object NoOpProcess : Process() {
    // `OutputStream.nullOutputStream()`/`InputStream.nullInputStream()` are API 33; minSdk is 26.
    override fun getOutputStream(): java.io.OutputStream =
        object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
        }

    override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false
}
