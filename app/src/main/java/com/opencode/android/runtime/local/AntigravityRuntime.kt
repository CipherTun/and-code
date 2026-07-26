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
import java.util.UUID

@Serializable
data class AntigravitySessionRecord(
    val appSessionId: String,
    val conversationId: String? = null,
    val workspace: String,
    val lastStep: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

class AntigravityRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
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
    private val adapter = AntigravityTranscriptAdapter(json)

    init {
        load()
    }

    fun events(): Flow<OpenCodeEvent> = events

    fun auth() = AntigravityAuthCoordinator(runtimeDirectory, installedRuntimeProvider)

    fun isInstalled(): Boolean = installedRuntimeProvider()?.rootfs?.resolve("usr/local/bin/agy")?.canExecute() == true

    fun version(): String? =
        runCatching {
            val runtime = installedRuntimeProvider() ?: return null
            val result =
                ProcessBuilder(AntigravitySandboxLauncher.command(runtime, "/workspace", listOf("--version"), false))
                    .redirectErrorStream(true).start().apply { waitFor() }
            result.inputStream.bufferedReader().readText().trim().takeIf { result.exitValue() == 0 && it.isNotBlank() }
        }.getOrNull()

    suspend fun send(
        sessionId: String,
        workspace: String,
        prompt: String,
        conversationId: String?,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val runtime = installedRuntimeProvider() ?: error("Linux environment is not installed")
                require(isInstalled()) { "Antigravity is not installed" }
                processes.remove(sessionId)?.destroyForcibly()
                val args =
                    buildList {
                        add("--print")
                        if (conversationId != null) {
                            add("--conversation")
                            add(conversationId)
                        }
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
                                AntigravitySandboxLauncher.environment(runtime, File(runtimeDirectory, "proot-tmp").apply { mkdirs() }),
                            )
                        }.start()
                processes[sessionId] = process
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                processes.remove(sessionId)
                require(process.exitValue() == 0) { output.ifBlank { "agy exited with ${process.exitValue()}" } }
                val record = records[sessionId] ?: AntigravitySessionRecord(sessionId, UUID.randomUUID().toString(), workspace)
                records[sessionId] = record.copy(updatedAt = System.currentTimeMillis(), lastStep = record.lastStep + 1)
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
        records[sessionId] = AntigravitySessionRecord(sessionId, UUID.randomUUID().toString(), workspace)
        persist()
    }

    fun abortAll() {
        processes.keys.toList().forEach(::abort)
    }

    fun respond(
        permissionId: String,
        response: com.opencode.android.runtime.PermissionResponse,
        remember: Boolean,
    ): Boolean = true

    fun answer(
        requestId: String,
        answers: List<List<String>>,
    ): Boolean = true

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
