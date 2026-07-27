package com.opencode.android.runtime.local

import com.opencode.android.core.api.*
import com.opencode.android.runtime.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

class AntigravityTarget(internal val runtime: AntigravityRuntime) : RuntimeTarget {
    override val id = LocalAgent.ANTIGRAVITY.targetId
    override val displayName = "Antigravity · Local"
    override val agent = LocalAgent.ANTIGRAVITY
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL
    override val capabilities =
        // The parser and hook schema are deliberately kept behind this boundary until they are
        // wired to a long-lived agy PTY. Advertising a capability here makes the UI send replies
        // to a process that does not exist (the current bridge is one-shot --print), so keep the
        // state honest and let the UI fall back to plain text chat.
        RuntimeCapabilities()
    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()
    private val files = ClaudeWorkspaceFiles(File(runtime.runtimeDirectory, "workspace"))
    val auth get() = runtime.auth()

    private val modeFile = File(runtime.runtimeDirectory, "antigravity-permission-mode")
    private val mutableDefaultPermissionMode =
        MutableStateFlow(AntigravityPermissionMode.fromCliValue(runCatching { modeFile.readText().trim() }.getOrNull()))
    val defaultPermissionMode: StateFlow<AntigravityPermissionMode> = mutableDefaultPermissionMode.asStateFlow()

    /** Applies [mode] to new sessions, and to [sessionId] when one is given - same as Claude Code. */
    fun setPermissionMode(
        mode: AntigravityPermissionMode,
        sessionId: String? = null,
    ) {
        mutableDefaultPermissionMode.value = mode
        runCatching {
            modeFile.parentFile?.mkdirs()
            modeFile.writeText(mode.cliValue)
        }
        if (sessionId != null) runtime.setSessionMode(sessionId, mode)
    }

    override suspend fun connect(): Result<OpenCodeHealth> =
        runCatching {
            val version = runtime.version() ?: error("Antigravity is not installed or incompatible with this ABI")
            mutableState.value = RuntimeState.Connected(version)
            OpenCodeHealth(true, version)
        }.onFailure { mutableState.value = RuntimeState.Unavailable(it.message ?: "Antigravity unavailable") }

    override fun disconnect() {
        runtime.abortAll()
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health(): OpenCodeHealth = connect().getOrElse { OpenCodeHealth(false, "") }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        runtime.listSessions(directory).map {
            OpenCodeSession(
                it.appSessionId,
                directory = it.workspace,
                title = "Antigravity",
                time = OpenCodeTime(it.createdAt, it.updatedAt),
            )
        }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession {
        val id = UUID.randomUUID().toString()
        runtime.create(id, directory ?: "/workspace")
        return OpenCodeSession(
            id,
            directory = directory ?: "/workspace",
            title = title ?: "Antigravity",
            time = OpenCodeTime(System.currentTimeMillis(), System.currentTimeMillis()),
        )
    }

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = runtime.listMessages(sessionId)

    override suspend fun listProviders(): ProviderCatalog =
        withContext(kotlinx.coroutines.Dispatchers.IO) { AntigravityModels.catalog(runtime.models()) }

    override suspend fun listAgents(): List<OpenCodeAgent> = listOf(OpenCodeAgent("antigravity", "Antigravity", "primary", true))

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        val record = runtime.listSessions(null).firstOrNull { it.appSessionId == sessionId } ?: error("Antigravity session not found")
        val model = request.modelId ?: record.model
        val variant = request.variant ?: record.variant
        if (model != record.model || variant != record.variant) runtime.setSessionModel(sessionId, model, variant)
        val permissionMode = AntigravityPermissionMode.fromCliValue(record.permissionMode)
        runtime.send(sessionId, record.workspace, request.text, record.conversationId, model, variant, permissionMode).getOrThrow()
    }

    override suspend fun mcpServers(): List<McpServer> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rootfs = runtime.currentRootfs() ?: return@withContext emptyList()
            AntigravityMcp.read(rootfs)
        }

    override suspend fun addMcpServer(body: JsonObject): McpServer =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rootfs = runtime.currentRootfs() ?: error("Linux environment is not installed")
            AntigravityMcp.add(rootfs, body)
        }

    override suspend fun disconnectMcpServer(name: String): Boolean =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rootfs = runtime.currentRootfs() ?: return@withContext false
            AntigravityMcp.remove(rootfs, name)
        }

    override suspend fun abortSession(sessionId: String): Boolean {
        runtime.abort(sessionId)
        return true
    }

    override suspend fun deleteSession(sessionId: String): Boolean {
        runtime.remove(sessionId)
        return true
    }

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = runtime.respond(permissionId, response, remember)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = runtime.answer(requestId, answers)

    override fun events(): Flow<OpenCodeEvent> = runtime.events()

    // The merged Claude runtime added the local workspace explorer because a local agent has no
    // HTTP file API. Antigravity uses the same /workspace bind mount, so expose the same safe,
    // canonicalized read/search surface instead of falling back to OpenCodeBackend.unsupported().
    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = withContext(kotlinx.coroutines.Dispatchers.IO) { files.list(directory, path) }

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = withContext(kotlinx.coroutines.Dispatchers.IO) { files.read(directory, path) }

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = withContext(kotlinx.coroutines.Dispatchers.IO) { files.find(directory, query, includeDirectories, limit) }

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = withContext(kotlinx.coroutines.Dispatchers.IO) { files.search(directory, pattern) }

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        runtime.listSessions(null).map {
            WorkspaceRef(it.workspace, it.workspace.substringAfterLast('/').ifBlank { it.workspace }, it.workspace)
        }.distinctBy { it.id }
}
