package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.OpenCodeMessage
import com.opencode.android.core.api.OpenCodeMessageInfo
import com.opencode.android.core.api.OpenCodePart
import com.opencode.android.core.api.OpenCodeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AntigravitySessionRecord(
    val appSessionId: String,
    val conversationId: String? = null,
    val workspace: String,
    val lastStep: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    /** Null until the user picks one; a null model omits `--model`/`--effort` and lets agy decide. */
    val model: String? = null,
    val variant: String? = null,
    val permissionMode: String = AntigravityPermissionMode.DEFAULT.cliValue,
)

class AntigravityRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val githubToken: () -> String? = { null },
) {
    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 128)
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    private val recordsFile = File(runtimeDirectory, "antigravity-sessions.json")
    private val messagesFile = File(runtimeDirectory, "antigravity-messages.json")
    private val records = linkedMapOf<String, AntigravitySessionRecord>()
    private val messages = linkedMapOf<String, MutableList<OpenCodeMessage>>()
    private val processes = linkedMapOf<String, Process>()

    @Volatile private var cachedVersion: String? = null

    @Volatile private var cachedModels: List<AntigravityModels.Entry>? = null
    private val adapter = AntigravityTranscriptAdapter(json)
    private val authCoordinator = AntigravityAuthCoordinator(runtimeDirectory, installedRuntimeProvider, githubToken)

    init {
        load()
    }

    fun events(): Flow<OpenCodeEvent> = events

    fun auth() = authCoordinator

    fun isInstalled(): Boolean =
        installedRuntimeProvider()?.let { runtime ->
            (runtime.antigravityRootfs ?: runtime.rootfs).resolve("usr/local/bin/agy").canExecute()
        } == true

    /** The rootfs `mcp_config.json` and other guest-side files live in, or null when not installed. */
    fun currentRootfs(): File? = installedRuntimeProvider()?.let { it.antigravityRootfs ?: it.rootfs }

    fun version(): String? =
        runCatching {
            cachedVersion?.let { return@runCatching it }
            val runtime = installedRuntimeProvider() ?: return null
            AntigravityGuestSettings.repair(runtime)
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            val result =
                ProcessBuilder(AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, listOf("--version"), false))
                    .redirectErrorStream(true)
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
            val completed = result.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                result.destroyForcibly()
                return@runCatching null
            }
            val output = result.inputStream.bufferedReader().readText().trim()
            // Some official builds initialize the auth provider before returning the version and
            // may exit non-zero when no account is present. The semantic version is still a valid
            // install signal; auth is verified separately with `agy models`.
            (VERSION_PATTERN.find(output)?.value ?: output.takeIf { result.exitValue() == 0 && it.isNotBlank() })
                .also { cachedVersion = it }
        }.getOrNull()

    /** Clears the in-memory health cache after an install or runtime update. */
    fun invalidateVersion() {
        cachedVersion = null
    }

    /**
     * The models the signed-in account can use, from `agy models` (cached until [invalidateModels]).
     *
     * Runs a one-shot `agy models` the first time this is called after sign-in; a failure (not
     * signed in yet, network error) returns an empty list rather than throwing, which
     * [AntigravityModels.catalog] turns into the same single placeholder model shown before this
     * existed.
     */
    fun models(): List<AntigravityModels.Entry> =
        cachedModels ?: runCatching {
            val runtime = installedRuntimeProvider() ?: return@runCatching emptyList()
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            val result =
                ProcessBuilder(AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, listOf("models"), false))
                    .redirectErrorStream(true)
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
            val output = result.inputStream.bufferedReader().readText()
            if (!result.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
                result.destroyForcibly()
                return@runCatching emptyList()
            }
            if (result.exitValue() != 0) emptyList() else AntigravityModels.parse(output)
        }.getOrDefault(emptyList()).also { cachedModels = it }

    /** Clears the cached model list; called after sign-in and sign-out so a switched account is picked up. */
    fun invalidateModels() {
        cachedModels = null
    }

    suspend fun send(
        sessionId: String,
        workspace: String,
        prompt: String,
        conversationId: String?,
        model: String? = null,
        variant: String? = null,
        permissionMode: AntigravityPermissionMode = AntigravityPermissionMode.DEFAULT,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val runtime = installedRuntimeProvider() ?: error("Linux environment is not installed")
                require(isInstalled()) { "Antigravity is not installed" }
                AntigravityGuestSettings.repair(runtime)
                processes.remove(sessionId)?.destroyForcibly()
                val args =
                    buildList {
                        add("--print")
                        if (conversationId != null) {
                            add("--conversation")
                            add(conversationId)
                        }
                        addAll(AntigravityModels.cliArgs(model, variant))
                        addAll(permissionMode.cliArgs)
                        add(prompt)
                    }
                val process =
                    ProcessBuilder(
                        AntigravitySandboxLauncher.command(
                            runtime,
                            File(runtimeDirectory, "workspace").apply {
                                mkdirs()
                            }.absolutePath,
                            args,
                            false,
                        ),
                    )
                        .directory(runtimeDirectory).redirectErrorStream(true).apply {
                            environment().putAll(
                                AntigravitySandboxLauncher.environment(
                                    runtime,
                                    File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                    githubToken(),
                                ),
                            )
                        }.start()
                processes[sessionId] = process
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                processes.remove(sessionId)
                require(process.exitValue() == 0) { output.ifBlank { "agy exited with ${process.exitValue()}" } }
                val record = records[sessionId] ?: AntigravitySessionRecord(sessionId, null, workspace)
                val discoveredConversationId = CONVERSATION_ID_PATTERN.find(output)?.groupValues?.getOrNull(1)
                records[sessionId] =
                    record.copy(
                        // Never invent a conversation id: --conversation must only be used with
                        // an id emitted by the official CLI, otherwise a cold resume can attach
                        // to an unrelated conversation.
                        conversationId = discoveredConversationId ?: record.conversationId,
                        updatedAt = System.currentTimeMillis(),
                        lastStep = record.lastStep + 1,
                    )
                val now = System.currentTimeMillis()
                val userId = "$sessionId-user-${record.lastStep}"
                val assistantId = "$sessionId-assistant-${record.lastStep}"
                messages.getOrPut(sessionId) { mutableListOf() }.apply {
                    add(
                        OpenCodeMessage(
                            OpenCodeMessageInfo(userId, sessionId, "user", OpenCodeTime(now, now)),
                            listOf(OpenCodePart(type = "text", text = prompt)),
                        ),
                    )
                    add(
                        OpenCodeMessage(
                            OpenCodeMessageInfo(assistantId, sessionId, "assistant", OpenCodeTime(now, now), agent = "antigravity"),
                            listOf(OpenCodePart(type = "text", text = output)),
                        ),
                    )
                }
                persist()
                events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
                output
            }.onFailure {
                events.tryEmit(OpenCodeEvent.SessionError(sessionId, it.message))
                events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
            }
        }

    fun abort(sessionId: String) {
        processes[sessionId]?.let { process ->
            runCatching {
                process.outputStream.write(27)
                process.outputStream.flush()
            }
            Thread.sleep(2000)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun listSessions(directory: String?): List<AntigravitySessionRecord> =
        records.values.filter {
            directory == null || it.workspace == directory
        }

    fun listMessages(sessionId: String): List<OpenCodeMessage> = messages[sessionId].orEmpty().toList()

    fun remove(sessionId: String) {
        abort(sessionId)
        records.remove(sessionId)
        messages.remove(sessionId)
        persist()
    }

    fun create(
        sessionId: String,
        workspace: String,
    ) {
        records[sessionId] = AntigravitySessionRecord(sessionId, null, workspace)
        persist()
    }

    /** Remembers the model/variant a session's next message should use. */
    fun setSessionModel(
        sessionId: String,
        model: String?,
        variant: String?,
    ) {
        val record = records[sessionId] ?: return
        records[sessionId] = record.copy(model = model, variant = variant)
        persist()
    }

    /** Remembers the permission mode a session's next message should use. */
    fun setSessionMode(
        sessionId: String,
        mode: AntigravityPermissionMode,
    ) {
        val record = records[sessionId] ?: return
        records[sessionId] = record.copy(permissionMode = mode.cliValue)
        persist()
    }

    fun abortAll() {
        processes.keys.toList().forEach(::abort)
    }

    fun respond(
        permissionId: String,
        response: com.opencode.android.runtime.PermissionResponse,
        remember: Boolean,
    ): Boolean = false

    fun answer(
        requestId: String,
        answers: List<List<String>>,
    ): Boolean = false

    private companion object {
        // Keep this deliberately narrow. A UUID-shaped value in arbitrary model text is not
        // enough to claim resume support, but this lets a future official --print transcript
        // bridge persist an explicitly emitted conversation id without fabricating one.
        val CONVERSATION_ID_PATTERN = Regex("(?:conversation(?:Id|_id)|conversation)\\s*[:=]\\s*[\\\"']?([0-9a-fA-F-]{16,})")
        val VERSION_PATTERN = Regex("\\b\\d+\\.\\d+\\.\\d+\\b")
    }

    private fun load() {
        runCatching {
            json.decodeFromString<List<AntigravitySessionRecord>>(
                recordsFile.readText(),
            ).forEach { records[it.appSessionId] = it }
        }
        runCatching {
            json.decodeFromString<Map<String, List<OpenCodeMessage>>>(
                messagesFile.readText(),
            ).forEach { messages[it.key] = it.value.toMutableList() }
        }
    }

    private fun persist() {
        runCatching {
            recordsFile.parentFile?.mkdirs()
            recordsFile.writeText(json.encodeToString(records.values.toList()))
            messagesFile.writeText(json.encodeToString(messages.mapValues { it.value.toList() }))
        }
    }
}
