package com.opencode.android.runtime.local

import com.opencode.android.core.api.*
import com.opencode.android.runtime.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AntigravityTarget(private val runtime: AntigravityRuntime) : RuntimeTarget {
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
    val auth get() = runtime.auth()

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
        ProviderCatalog(
            all =
                listOf(
                    OpenCodeProvider(
                        "antigravity",
                        "Antigravity",
                        mapOf("default" to OpenCodeModel("default", "antigravity", "Account default")),
                    ),
                ),
            default = mapOf("antigravity" to "default"),
            connected = listOf("antigravity"),
        )

    override suspend fun listAgents(): List<OpenCodeAgent> = listOf(OpenCodeAgent("antigravity", "Antigravity", "primary", true))

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        val record = runtime.listSessions(null).firstOrNull { it.appSessionId == sessionId } ?: error("Antigravity session not found")
        runtime.send(sessionId, record.workspace, request.text, record.conversationId).getOrThrow()
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

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        runtime.listSessions(null).map {
            WorkspaceRef(it.workspace, it.workspace.substringAfterLast('/').ifBlank { it.workspace }, it.workspace)
        }.distinctBy { it.id }
}
