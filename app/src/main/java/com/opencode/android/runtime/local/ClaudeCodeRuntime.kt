package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.OpenCodeMessage
import com.opencode.android.core.api.OpenCodeMessageInfo
import com.opencode.android.core.api.OpenCodePart
import com.opencode.android.core.api.OpenCodeTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/**
 * Runs Claude Code inside the shared Alpine/PRoot sandbox.
 *
 * The CLI is driven in streaming-JSON mode (`--print --input-format stream-json --output-format
 * stream-json`), which keeps one process alive per chat session and exchanges structured messages
 * over stdin/stdout. Conversation state lives in Claude Code's own session store, so a process that
 * dies is relaunched with `--resume` and the history is preserved.
 */
class ClaudeCodeRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val messages: ClaudeMessages = ClaudeMessages,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 256)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageStore = ClaudeMessageStore(File(runtimeDirectory, "claude-messages.json"), json)

    /** One live CLI process, plus what is needed to decide whether it can be reused. */
    private class SessionProcess(
        val process: Process,
        val readerJob: Job,
        val permissionMode: ClaudePermissionMode,
        val directory: String,
        val model: String?,
    )

    private val sessions = linkedMapOf<String, SessionProcess>()

    /** Claude Code's own session ids, so a relaunched process resumes rather than starts over. */
    private val resumeIds = mutableMapOf<String, String>()

    val auth = ClaudeAuthCoordinator(runtimeDirectory, installedRuntimeProvider, accessCoordinator, messages)

    fun events(): Flow<OpenCodeEvent> = events

    fun isInstalled(): Boolean = installedRuntimeProvider()?.rootfs?.let(ClaudeCodeInstaller::isInstalledIn) == true

    fun version(): String? {
        if (!isInstalled()) return null
        val result = runCommand("${ClaudeCodeInstaller.CLAUDE_BINARY} --version", timeoutSeconds = 120)
        if (result.exitCode != 0) return null
        return result.output
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            ?.let { line -> VERSION.find(line)?.value ?: line }
    }

    /** Installs Claude Code into the already-provisioned sandbox. */
    fun install(onStep: (ClaudeCodeInstaller.Step) -> Unit = {}) {
        val runtime = installedRuntimeProvider() ?: error(messages.runtimeMissing)
        accessCoordinator.write {
            ClaudeCodeInstaller.installInto(runtime.rootfs, runtime.commandSuite, runtimeDirectory, onStep)
        }
    }

    fun update() {
        val runtime = installedRuntimeProvider() ?: error(messages.runtimeMissing)
        accessCoordinator.write {
            ClaudeCodeInstaller.updateIn(runtime.rootfs, runtime.commandSuite, runtimeDirectory)
        }
    }

    @Synchronized
    fun send(
        sessionId: String,
        directory: String,
        prompt: String,
        permissionMode: ClaudePermissionMode,
        model: String?,
    ): Result<Unit> =
        runCatching {
            val session = ensureProcess(sessionId, directory.ifBlank { "/workspace" }, permissionMode, model)
            recordUserMessage(sessionId, prompt)
            session.process.outputStream.apply {
                write((json.encodeToString(JsonObject.serializer(), userMessage(prompt)) + "\n").toByteArray())
                flush()
            }
            Unit
        }.onFailure { error ->
            events.tryEmit(OpenCodeEvent.SessionError(sessionId, error.message))
            events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
        }

    /** Stops the process backing [sessionId]; the next send resumes the same Claude conversation. */
    @Synchronized
    fun stop(sessionId: String) {
        sessions.remove(sessionId)?.let { session ->
            session.readerJob.cancel()
            if (session.process.isAlive) session.process.destroyForcibly()
        }
        messageStore.flush()
    }

    @Synchronized
    fun stopAll() {
        sessions.keys.toList().forEach(::stop)
        auth.cancel()
    }

    fun listMessages(sessionId: String): List<OpenCodeMessage> = messageStore.list(sessionId)

    @Synchronized
    fun deleteSessionData(sessionId: String) {
        stop(sessionId)
        resumeIds.remove(sessionId)
        messageStore.remove(sessionId)
    }

    private fun ensureProcess(
        sessionId: String,
        directory: String,
        permissionMode: ClaudePermissionMode,
        model: String?,
    ): SessionProcess {
        val existing = sessions[sessionId]
        // Permission mode, working directory and model are read once at startup, so a change to any
        // of them means the process has to be replaced rather than reused.
        if (existing != null &&
            existing.process.isAlive &&
            existing.permissionMode == permissionMode &&
            existing.directory == directory &&
            existing.model == model
        ) {
            return existing
        }
        if (existing != null) stop(sessionId)

        val runtime = installedRuntimeProvider() ?: error(messages.runtimeMissing)
        require(ClaudeCodeInstaller.isInstalledIn(runtime.rootfs)) { "Claude Code is not installed" }

        val stderrLog = File(runtimeDirectory, "logs/claude-stderr.log").also { it.parentFile?.mkdirs() }
        val process =
            ProcessBuilder(
                ClaudeSandboxLauncher.command(
                    runtime = runtime,
                    workspaceHostDir = File(runtimeDirectory, "workspace").apply { mkdirs() },
                    workingDirectory = directory,
                    arguments = processArguments(sessionId, permissionMode, model),
                    pty = false,
                ),
            ).directory(runtimeDirectory)
                .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
                .apply {
                    environment().clear()
                    environment().putAll(
                        ClaudeSandboxLauncher.environment(runtime, File(runtimeDirectory, "proot-tmp").apply { mkdirs() }),
                    )
                }
                .start()

        val parser = ClaudeStreamJsonParser(sessionId, json)
        val readerJob =
            scope.launch {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line -> handleLine(sessionId, parser, line) }
                }
                messageStore.flush()
                // A CLI that exits mid-turn would otherwise leave the chat spinning forever.
                events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
                synchronized(this@ClaudeCodeRuntime) {
                    if (sessions[sessionId]?.process === process) sessions.remove(sessionId)
                }
            }

        return SessionProcess(process, readerJob, permissionMode, directory, model).also { sessions[sessionId] = it }
    }

    private fun processArguments(
        sessionId: String,
        permissionMode: ClaudePermissionMode,
        model: String?,
    ): List<String> =
        buildList {
            add("--print")
            add("--input-format")
            add("stream-json")
            add("--output-format")
            add("stream-json")
            add("--verbose")
            add("--include-partial-messages")
            add("--permission-mode")
            add(permissionMode.cliValue)
            ClaudeModels.cliModel(model)?.let {
                add("--model")
                add(it)
            }
            val resumeId = resumeIds[sessionId]
            if (resumeId != null) {
                add("--resume")
                add(resumeId)
            } else {
                add("--session-id")
                add(sessionId)
            }
        }

    private fun userMessage(prompt: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("user"),
                "message" to
                    JsonObject(
                        mapOf(
                            "role" to JsonPrimitive("user"),
                            "content" to
                                JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("text"),
                                                "text" to JsonPrimitive(prompt),
                                            ),
                                        ),
                                    ),
                                ),
                        ),
                    ),
            ),
        )

    private fun handleLine(
        sessionId: String,
        parser: ClaudeStreamJsonParser,
        line: String,
    ) {
        if (line.isBlank()) return
        val parsed = parser.parse(line)
        parsed.claudeSessionId?.let { claudeSessionId ->
            synchronized(this) { resumeIds[sessionId] = claudeSessionId }
        }
        parsed.messages.forEach { message -> messageStore.upsert(sessionId, message) }
        parsed.events.forEach(events::tryEmit)
        if (parsed.turnFinished) messageStore.flush()
    }

    private fun recordUserMessage(
        sessionId: String,
        prompt: String,
    ) {
        val timestamp = System.currentTimeMillis()
        val messageId = "user-${UUID.randomUUID()}"
        messageStore.upsert(
            sessionId,
            OpenCodeMessage(
                info =
                    OpenCodeMessageInfo(
                        id = messageId,
                        sessionId = sessionId,
                        role = "user",
                        time = OpenCodeTime(timestamp, timestamp),
                    ),
                parts = listOf(OpenCodePart("$messageId-text", sessionId, messageId, "text", text = prompt)),
            ),
        )
    }

    private fun runCommand(
        command: String,
        timeoutSeconds: Long = 30,
    ): LocalRuntimeCommandResult =
        LocalRuntimeCommandRunner(
            runtimeDirectory = runtimeDirectory,
            installedRuntimeProvider = installedRuntimeProvider,
            accessCoordinator = accessCoordinator,
            timeoutSeconds = timeoutSeconds,
        ).runShell(command, timeoutSeconds)

    private companion object {
        val VERSION = Regex("\\d+\\.\\d+\\.\\d+")
    }
}
