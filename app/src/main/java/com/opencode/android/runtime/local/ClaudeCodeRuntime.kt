package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.OpenCodeHealth
import com.opencode.android.core.api.OpenCodeMessage
import com.opencode.android.core.api.OpenCodePart
import com.opencode.android.core.api.OpenCodeSession
import com.opencode.android.core.api.OpenCodeTime
import com.opencode.android.core.api.PermissionRequest
import com.opencode.android.core.api.PromptRequest
import com.opencode.android.runtime.BackendKind
import com.opencode.android.runtime.OpenCodeBackend
import com.opencode.android.runtime.PermissionResponse
import com.opencode.android.runtime.RuntimeState
import com.opencode.android.runtime.RuntimeTarget
import com.opencode.android.runtime.RuntimeType
import com.opencode.android.runtime.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.util.UUID

/** Claude Code runs in the same Alpine/PRoot rootfs as OpenCode; no Claude binary is bundled. */
class ClaudeCodeRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var process: Process? = null
    private val startedSessions = mutableSetOf<String>()
    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var idleJob: Job? = null
    private var activeSessionId: String? = null
    private var activeMessageId: String? = null
    private var activeAssistantText = StringBuilder()
    private var lastInput: String? = null
    private var awaitingPermission: PermissionRequest? = null
    private val messageFile = File(runtimeDirectory, "claude-messages.json")
    private val messages = linkedMapOf<String, MutableList<OpenCodeMessage>>()

    init {
        runCatching {
            json.decodeFromString<Map<String, List<OpenCodeMessage>>>(messageFile.readText())
                .forEach { (id, value) -> messages[id] = value.toMutableList() }
        }
    }

    fun events(): Flow<OpenCodeEvent> = events

    fun isInstalled(): Boolean = runCommand("command -v claude && claude --version").exitCode == 0

    fun version(): String? = runCommand("claude --version").output.lineSequence().firstOrNull { it.isNotBlank() }

    fun authStatus(): String = runCommand("claude auth status --text", timeoutSeconds = 30).output

    fun authLogin(): LocalRuntimeCommandResult =
        // Authentication is interactive too; give the CLI a real terminal so its browser/device
        // code flow is not downgraded to a pipe.
        runCommand("script -qefc 'claude auth login' /dev/null", timeoutSeconds = 300)

    fun install(): LocalRuntimeCommandResult =
        runCommand(
            """
            wget -qO /etc/apk/keys/claude-code.rsa.pub https://downloads.claude.ai/keys/claude-code.rsa.pub &&
            grep -q 'downloads.claude.ai/claude-code/apk' /etc/apk/repositories ||
              echo 'https://downloads.claude.ai/claude-code/apk/stable' >> /etc/apk/repositories &&
            apk add --no-cache claude-code util-linux && claude --version
            """.trimIndent(),
            timeoutSeconds = 300,
        )

    fun update(): LocalRuntimeCommandResult = runCommand("apk update && apk upgrade claude-code", timeoutSeconds = 300)

    @Synchronized
    fun send(sessionId: String, directory: String, prompt: String): Result<Unit> = runCatching {
        val runtime = installedRuntimeProvider() ?: error("OpenCode Linux runtime is not installed")
        val hostWorkspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
        val cwd = directory.ifBlank { "/workspace" }
        if (process == null || !process!!.isAlive || activeSessionId != sessionId) {
            stop()
            activeSessionId = sessionId
            process = ProcessBuilder(
                runtime.commandSuite.proot.absolutePath, "--kill-on-exit", "--link2symlink", "-0",
                "-r", runtime.rootfs.absolutePath, "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${hostWorkspace.absolutePath}:/workspace", "-w", cwd,
                "/usr/bin/script", "-qefc", "/usr/local/bin/claude --ax-screen-reader", "/dev/null",
            ).directory(runtimeDirectory).redirectErrorStream(true).apply {
                environment().clear()
                environment().putAll(localRuntimeEnvironment(runtime.commandSuite.environment(), File(runtimeDirectory, "proot-tmp")))
            }.start()
            readerJob = scope.launch {
                process!!.inputStream.bufferedReader().useLines { lines -> lines.forEach { parseInteractiveOutput(sessionId, it) } }
                events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
            }
        }
        activeMessageId = "claude-${UUID.randomUUID()}"
        activeAssistantText = StringBuilder()
        appendMessage(sessionId, OpenCodeMessage(
            info = com.opencode.android.core.api.OpenCodeMessageInfo(
                id = "user-${UUID.randomUUID()}", sessionId = sessionId, role = "user",
                time = OpenCodeTime(System.currentTimeMillis(), System.currentTimeMillis()),
            ),
            parts = listOf(OpenCodePart(id = "user-text-${UUID.randomUUID()}", sessionId = sessionId, type = "text", text = prompt)),
        ))
        writeInput(prompt)
    }

    @Synchronized fun writeInput(input: String) {
        lastInput = input.trim()
        process?.outputStream?.let { stream ->
            stream.write((input.trimEnd() + "\n").toByteArray())
            stream.flush()
        }
    }

    @Synchronized fun stop() {
        idleJob?.cancel()
        readerJob?.cancel()
        process?.let { if (it.isAlive) it.destroyForcibly() }
        process = null
        activeSessionId = null
        activeMessageId = null
        activeAssistantText = StringBuilder()
        lastInput = null
        awaitingPermission = null
    }

    private fun parseInteractiveOutput(sessionId: String, rawLine: String) {
        val line = rawLine.replace(ANSI_ESCAPE, "").trim()
        if (line.isBlank() || line == ">" || line == "❯" || line == lastInput) return
        if (line.contains("permission", ignoreCase = true) || line.contains("allow", ignoreCase = true) || line.contains("approve", ignoreCase = true)) {
            val request = PermissionRequest(
                id = "claude-permission-${UUID.randomUUID()}", sessionId = sessionId,
                permission = line, patterns = emptyList(),
            )
            awaitingPermission = request
            events.tryEmit(OpenCodeEvent.PermissionAsked(request))
            return
        }
        if (line == "1" || line == "2" || line == "3") return
        val messageId = activeMessageId ?: "claude-${UUID.randomUUID()}".also { activeMessageId = it }
        val delta = line + "\n"
        activeAssistantText.append(delta)
        events.tryEmit(OpenCodeEvent.MessagePartDelta(sessionId, messageId, "claude-text", "text", delta))
        events.tryEmit(OpenCodeEvent.MessagePartUpdated(OpenCodePart("claude-text", sessionId, messageId, "text", text = activeAssistantText.toString())))
        appendMessage(sessionId, OpenCodeMessage(
            info = com.opencode.android.core.api.OpenCodeMessageInfo(messageId, sessionId, "assistant", OpenCodeTime(System.currentTimeMillis(), System.currentTimeMillis())),
            parts = listOf(OpenCodePart("claude-text", sessionId, messageId, "text", text = activeAssistantText.toString())),
        ))
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(1500)
            if (awaitingPermission == null) events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
        }
    }

    fun respondToPermission(response: PermissionResponse, remember: Boolean) {
        val answer = when (response) {
            PermissionResponse.ONCE -> "1"
            PermissionResponse.ALWAYS -> "2"
            PermissionResponse.REJECT -> "3"
        }
        awaitingPermission = null
        writeInput(answer)
    }

    fun answerQuestion(answer: String) {
        writeInput(answer)
    }

    private fun parseEvent(sessionId: String, line: String) {
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val type = root["type"]?.jsonPrimitive?.content ?: return
        val message = root["message"]?.jsonObject
        val messageId = runCatching { message?.get("id")?.jsonPrimitive?.content }
            .getOrNull() ?: sessionId
        val content = message?.get("content")
        if ((type == "assistant" || type == "user") && content is kotlinx.serialization.json.JsonArray) {
            val parts = content.mapNotNull { block -> parseContentBlock(sessionId, messageId, block.jsonObject) }
            if (parts.isNotEmpty()) appendMessage(sessionId, OpenCodeMessage(
                info = com.opencode.android.core.api.OpenCodeMessageInfo(messageId, sessionId, if (type == "user") "assistant" else "assistant", OpenCodeTime(System.currentTimeMillis(), System.currentTimeMillis())),
                parts = parts,
            ))
        }
        val delta = root["event"]?.jsonObject?.get("delta")?.jsonObject?.get("text")?.jsonPrimitive?.content
        if (delta != null) {
            events.tryEmit(OpenCodeEvent.MessagePartDelta(sessionId, messageId, "claude-text", "text", delta))
        }
        if (type == "result") {
            root["result"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { result ->
                val part = OpenCodePart(id = "claude-result-$messageId", sessionId = sessionId, messageId = messageId, type = "text", text = result)
                events.tryEmit(OpenCodeEvent.MessagePartUpdated(part))
                appendMessage(sessionId, OpenCodeMessage(
                    info = com.opencode.android.core.api.OpenCodeMessageInfo(messageId, sessionId, "assistant", OpenCodeTime(System.currentTimeMillis(), System.currentTimeMillis())),
                    parts = listOf(part),
                ))
            }
        }
    }

    private fun parseContentBlock(sessionId: String, messageId: String, block: JsonObject): OpenCodePart? {
        val blockType = block["type"]?.jsonPrimitive?.content ?: return null
        val partId = block["id"]?.jsonPrimitive?.content ?: "claude-${UUID.randomUUID()}"
        return when (blockType) {
            "text" -> {
                val text = block["text"]?.jsonPrimitive?.content.orEmpty()
                events.tryEmit(OpenCodeEvent.MessagePartUpdated(OpenCodePart(partId, sessionId, messageId, "text", text = text)))
                OpenCodePart(partId, sessionId, messageId, "text", text = text)
            }
            "thinking" -> OpenCodePart(partId, sessionId, messageId, "reasoning", text = block["thinking"]?.jsonPrimitive?.content.orEmpty())
            "tool_use" -> {
                val name = block["name"]?.jsonPrimitive?.content ?: "tool"
                val input = block["input"] ?: kotlinx.serialization.json.JsonObject(emptyMap())
                val part = OpenCodePart(partId, sessionId, messageId, "tool", tool = name, callID = partId, state = mapOf("status" to json.encodeToJsonElement("running"), "input" to input))
                events.tryEmit(OpenCodeEvent.MessagePartUpdated(part))
                part
            }
            "tool_result" -> {
                val output = block["content"]?.toString().orEmpty()
                val callId = block["tool_use_id"]?.jsonPrimitive?.content ?: partId
                val part = OpenCodePart(callId, sessionId, messageId, "tool", tool = "tool", callID = callId, state = mapOf("status" to json.encodeToJsonElement("completed"), "output" to json.encodeToJsonElement(output)))
                events.tryEmit(OpenCodeEvent.MessagePartUpdated(part))
                part
            }
            else -> null
        }
    }

    private fun appendMessage(sessionId: String, message: OpenCodeMessage) {
        val sessionMessages = messages.getOrPut(sessionId) { mutableListOf() }
        val existing = sessionMessages.indexOfFirst { it.info.id == message.info.id }
        if (existing >= 0) sessionMessages[existing] = message else sessionMessages += message
        messageFile.parentFile?.mkdirs()
        messageFile.writeText(json.encodeToString(messages.mapValues { it.value.toList() }))
    }

    private companion object {
        val ANSI_ESCAPE = Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]")
    }

    fun listMessages(sessionId: String): List<OpenCodeMessage> = messages[sessionId].orEmpty()

    fun deleteSessionData(sessionId: String) {
        messages.remove(sessionId)
        File(runtimeDirectory, "sessions/$sessionId.started").delete()
        messageFile.writeText(json.encodeToString(messages.mapValues { it.value.toList() }))
    }

    private fun runCommand(command: String, timeoutSeconds: Long = 30): LocalRuntimeCommandResult =
        accessCoordinator.read {
            val runtime = installedRuntimeProvider() ?: return@read LocalRuntimeCommandResult(127, "Runtime unavailable")
            val result = LocalRuntimeCommandRunner(runtimeDirectory, installedRuntimeProvider, accessCoordinator, timeoutSeconds).runShell(command)
            result
        }
}

class ClaudeCodeTarget(private val runtime: ClaudeCodeRuntime) : RuntimeTarget {
    override val id = "claude-code-local"
    override val displayName = "Claude Code (Android local)"
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL
    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sessionsFile = File(runtime.runtimeDirectory, "claude-sessions.json")
    private val sessions = linkedMapOf<String, OpenCodeSession>().apply {
        runCatching {
            json.decodeFromString<List<OpenCodeSession>>(sessionsFile.readText()).forEach { put(it.id, it) }
        }
    }
    fun install(): LocalRuntimeCommandResult = runtime.install().also { result ->
        if (result.exitCode == 0) mutableState.value = RuntimeState.Connected(runtime.version().orEmpty())
        else mutableState.value = RuntimeState.Failed(result.output.takeLast(500))
    }
    fun update(): LocalRuntimeCommandResult = runtime.update().also { result ->
        if (result.exitCode == 0) mutableState.value = RuntimeState.Connected(runtime.version().orEmpty())
    }
    fun version(): String? = runtime.version()
    fun authStatus(): String = runtime.authStatus()
    fun authLogin(): LocalRuntimeCommandResult = runtime.authLogin()

    override suspend fun connect(): Result<OpenCodeHealth> = runCatching {
        val version = runtime.version() ?: error("Claude Code is not installed")
        mutableState.value = RuntimeState.Connected(version)
        OpenCodeHealth(true, version)
    }.onFailure { mutableState.value = RuntimeState.Failed(it.message ?: "Claude Code unavailable") }
    override fun disconnect() { runtime.stop(); mutableState.value = RuntimeState.Disconnected }
    override suspend fun health(): OpenCodeHealth {
        val version = runtime.version().orEmpty()
        val health = OpenCodeHealth(version.isNotBlank(), version)
        mutableState.value = if (health.healthy) RuntimeState.Connected(version) else RuntimeState.Unavailable("Claude Code is not installed")
        return health
    }
    override suspend fun listSessions(directory: String?) = sessions.values.filter { directory == null || it.directory == directory }
    override suspend fun createSession(title: String?, directory: String?): OpenCodeSession {
        val now = System.currentTimeMillis(); val id = UUID.randomUUID().toString()
        return OpenCodeSession(id = id, directory = directory ?: "/workspace", title = title ?: "Claude Code", time = OpenCodeTime(now, now)).also {
            sessions[id] = it
            persistSessions()
        }
    }
    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = runtime.listMessages(sessionId)
    override suspend fun listProviders() = com.opencode.android.core.api.ProviderCatalog()
    override suspend fun listAgents() = listOf(com.opencode.android.core.api.OpenCodeAgent("claude", "Claude Code", "primary", true))
    override suspend fun sendMessage(sessionId: String, request: PromptRequest) {
        val session = sessions[sessionId] ?: error("Claude session not found")
        withContext(Dispatchers.IO) {
            runtime.send(sessionId, session.directory ?: "/workspace", request.text).getOrThrow()
        }
    }
    override suspend fun abortSession(sessionId: String): Boolean { runtime.stop(); return true }
    override suspend fun renameSession(sessionId: String, title: String): OpenCodeSession {
        val session = sessions[sessionId] ?: error("Claude session not found")
        return session.copy(title = title, time = session.time.copy(updated = System.currentTimeMillis())).also {
            sessions[sessionId] = it
            persistSessions()
        }
    }
    override suspend fun deleteSession(sessionId: String): Boolean {
        val removed = sessions.remove(sessionId) != null
        if (removed) {
            persistSessions()
            runtime.deleteSessionData(sessionId)
        }
        return removed
    }
    override suspend fun respondToPermission(sessionId: String, permissionId: String, response: PermissionResponse, remember: Boolean): Boolean {
        runtime.respondToPermission(response, remember)
        return true
    }
    override suspend fun answerQuestion(sessionId: String, requestId: String, answers: List<List<String>>): Boolean {
        runtime.answerQuestion(answers.flatten().joinToString(", "))
        return true
    }
    override fun events(): Flow<OpenCodeEvent> = runtime.events()
    override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

    private fun persistSessions() {
        sessionsFile.parentFile?.mkdirs()
        sessionsFile.writeText(json.encodeToString(sessions.values.toList()))
    }

}
