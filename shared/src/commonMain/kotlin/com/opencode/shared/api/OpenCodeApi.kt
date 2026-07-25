package com.opencode.shared.api

import kotlinx.coroutines.flow.Flow

interface OpenCodeApi {
    suspend fun health(): OpenCodeHealth

    suspend fun listSessions(): List<OpenCodeSession>

    suspend fun createSession(directory: String? = null): OpenCodeSession

    suspend fun deleteSession(sessionId: String)

    suspend fun listMessages(sessionId: String): List<OpenCodeMessage>

    suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    )

    suspend fun abort(sessionId: String)

    suspend fun listProviders(): ProviderCatalog

    suspend fun listAgents(): List<OpenCodeAgent>

    suspend fun listModels(): List<OpenCodeModel>

    suspend fun listMcpServers(): List<McpServer>

    suspend fun listCommands(): List<OpenCodeCommand>

    suspend fun listSkills(): List<OpenCodeSkill>

    suspend fun getConfig(): OpenCodeConfig

    suspend fun getProject(): OpenCodeProject

    suspend fun getPaths(): OpenCodePathInfo

    suspend fun listFiles(path: String): List<OpenCodeFileNode>

    suspend fun readFile(
        path: String,
        sessionId: String? = null,
    ): OpenCodeFileContent

    suspend fun searchFiles(
        sessionId: String,
        pattern: String,
    ): List<OpenCodeSearchMatch>

    suspend fun listFileChanges(sessionId: String): List<OpenCodeFileChange>

    suspend fun revertFileChange(
        sessionId: String,
        path: String,
    )

    suspend fun respondPermission(
        id: String,
        approved: Boolean,
    )

    suspend fun respondQuestion(
        id: String,
        answers: List<String>,
    )

    suspend fun listTodos(sessionId: String): List<OpenCodeTodo>

    suspend fun getVcsInfo(): OpenCodeVcsInfo

    fun subscribeEvents(): Flow<OpenCodeEvent>
}
