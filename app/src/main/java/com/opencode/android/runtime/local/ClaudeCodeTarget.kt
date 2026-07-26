package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeAgent
import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.OpenCodeHealth
import com.opencode.android.core.api.OpenCodeMessage
import com.opencode.android.core.api.OpenCodeSession
import com.opencode.android.core.api.OpenCodeTime
import com.opencode.android.core.api.PromptRequest
import com.opencode.android.runtime.BackendKind
import com.opencode.android.runtime.LocalAgent
import com.opencode.android.runtime.PermissionResponse
import com.opencode.android.runtime.RuntimeState
import com.opencode.android.runtime.RuntimeTarget
import com.opencode.android.runtime.RuntimeType
import com.opencode.android.runtime.WorkspaceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Persisted alongside the session so a reopened chat keeps the permissions the user chose. */
@Serializable
private data class ClaudeSessionRecord(
    @SerialName("session") val session: OpenCodeSession,
    @SerialName("permissionMode") val permissionMode: String = ClaudePermissionMode.DEFAULT.cliValue,
    @SerialName("model") val model: String = ClaudeModels.DEFAULT_MODEL,
    @SerialName("effort") val effort: String? = null,
)

/** Exposes the Android-local Claude Code agent as a selectable runtime. */
class ClaudeCodeTarget(
    private val runtime: ClaudeCodeRuntime,
    private val messages: ClaudeMessages = ClaudeMessages,
) : RuntimeTarget {
    override val id = LocalAgent.CLAUDE_CODE.targetId
    override val displayName = "Claude Code"
    override val agent = LocalAgent.CLAUDE_CODE
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    private companion object {
        const val DEFAULT_TITLE = "Claude Code"
        const val TITLE_LENGTH = 40
    }

    private val sessionsFile = File(runtime.runtimeDirectory, "claude-sessions.json")
    private val modeFile = File(runtime.runtimeDirectory, "claude-permission-mode")
    private val records =
        linkedMapOf<String, ClaudeSessionRecord>().apply {
            runCatching {
                json.decodeFromString<List<ClaudeSessionRecord>>(sessionsFile.readText())
                    .forEach { put(it.session.id, it) }
            }
        }

    /**
     * Mode applied to sessions created from now on.
     *
     * Existing sessions keep the mode they were created with, because changing it mid-conversation
     * would silently widen what Claude may do to work the user already approved.
     */
    private val mutableDefaultPermissionMode =
        MutableStateFlow(
            ClaudePermissionMode.fromCliValue(runCatching { modeFile.readText().trim() }.getOrNull()),
        )
    val defaultPermissionMode: StateFlow<ClaudePermissionMode> = mutableDefaultPermissionMode.asStateFlow()

    /**
     * Applies [mode] to new sessions, and to [sessionId] when one is given.
     *
     * Picking a mode from an open chat is an explicit choice about that conversation, so it takes
     * effect there on the next message rather than only on the next session.
     */
    fun setPermissionMode(
        mode: ClaudePermissionMode,
        sessionId: String? = null,
    ) {
        mutableDefaultPermissionMode.value = mode
        runCatching {
            modeFile.parentFile?.mkdirs()
            modeFile.writeText(mode.cliValue)
        }
        val record = sessionId?.let(records::get) ?: return
        records[sessionId] = record.copy(permissionMode = mode.cliValue)
        persist()
    }

    private val titleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val auth: ClaudeAuthCoordinator get() = runtime.auth

    fun isInstalled(): Boolean = runtime.isInstalled()

    fun version(): String? = runtime.version()

    /** Installs the package and moves the target into whichever state the result implies. */
    fun install(onStep: (ClaudeCodeInstaller.Step) -> Unit = {}): Result<String> {
        mutableState.value = RuntimeState.Connecting
        return runCatching {
            runtime.install(onStep)
            runtime.version() ?: error("Claude Code did not report a version after installation")
        }.onSuccess { version ->
            mutableState.value = RuntimeState.Connected(version)
        }.onFailure { error ->
            mutableState.value = RuntimeState.Failed(error.message?.takeLast(500) ?: messages.installFailed)
        }
    }

    fun update(): Result<String> =
        runCatching {
            runtime.update()
            runtime.version() ?: error("Claude Code did not report a version after the update")
        }.onSuccess { version -> mutableState.value = RuntimeState.Connected(version) }

    override suspend fun connect(): Result<OpenCodeHealth> =
        runCatching {
            val version = runtime.version() ?: error("Claude Code is not installed")
            mutableState.value = RuntimeState.Connected(version)
            OpenCodeHealth(true, version)
        }.onFailure { error ->
            mutableState.value = RuntimeState.Unavailable(error.message ?: "Claude Code is unavailable")
        }

    override fun disconnect() {
        runtime.stopAll()
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health(): OpenCodeHealth =
        withContext(Dispatchers.IO) {
            val version = runtime.version()
            mutableState.value =
                if (version.isNullOrBlank()) {
                    RuntimeState.Unavailable("Claude Code is not installed")
                } else {
                    RuntimeState.Connected(version)
                }
            OpenCodeHealth(!version.isNullOrBlank(), version.orEmpty())
        }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        records.values
            .map(ClaudeSessionRecord::session)
            .filter { directory == null || it.directory == directory }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession {
        val now = System.currentTimeMillis()
        // Claude Code's --session-id only accepts a UUID, and reusing the app's session id keeps the
        // two session stores aligned.
        val session =
            OpenCodeSession(
                id = UUID.randomUUID().toString(),
                directory = directory ?: "/workspace",
                title = title ?: DEFAULT_TITLE,
                time = OpenCodeTime(now, now),
            )
        records[session.id] = ClaudeSessionRecord(session, mutableDefaultPermissionMode.value.cliValue)
        persist()
        return session
    }

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = runtime.listMessages(sessionId)

    override suspend fun listProviders() = ClaudeModels.catalog(runtime.resolvedModels().value)

    override suspend fun listAgents() = listOf(OpenCodeAgent("claude", "Claude Code", "primary", true))

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        val record = records[sessionId] ?: error("Claude Code session not found")
        // Claude Code does not name sessions, so every chat would sit in the drawer as
        // "Claude Code". The first prompt stands in, the way OpenCode summarises its own.
        if (record.session.title == DEFAULT_TITLE) {
            // The prompt stands in immediately so the drawer is never left saying "Claude Code";
            // the summarised name replaces it once Claude answers.
            titleFromPrompt(request.text)?.let { renameSession(sessionId, it) }
            titleScope.launch {
                val summary = withContext(Dispatchers.IO) { runtime.summarizeTitle(request.text) }
                if (summary != null && records[sessionId] != null) renameSession(sessionId, summary)
            }
        }
        val model = request.modelId ?: record.model
        val effort = request.variant ?: record.effort
        // Remembered so reopening the chat keeps what the user last picked.
        if (model != record.model || effort != record.effort) {
            records[sessionId] = record.copy(model = model, effort = effort)
            persist()
        }
        withContext(Dispatchers.IO) {
            runtime.send(
                sessionId = sessionId,
                directory = record.session.directory ?: "/workspace",
                prompt = request.text,
                permissionMode = ClaudePermissionMode.fromCliValue(record.permissionMode),
                model = model,
                effort = effort,
            ).getOrThrow()
        }
    }

    override suspend fun abortSession(sessionId: String): Boolean {
        withContext(Dispatchers.IO) { runtime.stop(sessionId) }
        return true
    }

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession {
        val record = records[sessionId] ?: error("Claude Code session not found")
        val renamed =
            record.session.copy(
                title = title,
                time = record.session.time.copy(updated = System.currentTimeMillis()),
            )
        records[sessionId] = record.copy(session = renamed)
        persist()
        return renamed
    }

    override suspend fun deleteSession(sessionId: String): Boolean {
        if (records.remove(sessionId) == null) return false
        persist()
        withContext(Dispatchers.IO) { runtime.deleteSessionData(sessionId) }
        return true
    }

    /**
     * Permission responses are not part of this runtime's contract.
     *
     * Streaming-JSON mode has no channel for answering an individual tool prompt, so permissions are
     * decided per session through [ClaudePermissionMode] instead. Reporting false keeps the chat
     * layer from believing a prompt was answered.
     */
    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = false

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = false

    override fun events(): Flow<OpenCodeEvent> = runtime.events()

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        records.values
            .mapNotNull { it.session.directory }
            .distinct()
            .map { path ->
                WorkspaceRef(
                    id = path,
                    name = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
                    path = path,
                )
            }

    private fun titleFromPrompt(prompt: String): String? {
        val firstLine = prompt.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) ?: return null
        return if (firstLine.length <= TITLE_LENGTH) firstLine else firstLine.take(TITLE_LENGTH).trimEnd() + "…"
    }

    private fun persist() {
        runCatching {
            sessionsFile.parentFile?.mkdirs()
            sessionsFile.writeText(json.encodeToString(records.values.toList()))
        }
    }
}
