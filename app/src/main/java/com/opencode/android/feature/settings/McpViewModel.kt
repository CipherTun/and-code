package com.opencode.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.core.api.McpServer
import com.opencode.android.runtime.LocalAgent
import com.opencode.android.runtime.RuntimeRegistry
import com.opencode.android.runtime.local.ClaudeCodeTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class McpUiState(
    val servers: List<McpServer> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val addName: String = "",
    val addCommand: String = "",
    val addUrl: String = "",
    val isAdding: Boolean = false,
    /**
     * Whether connecting and disconnecting a configured server means anything here.
     *
     * Claude Code and Antigravity both connect to every server they know about, so they offer
     * removal instead of a live toggle.
     */
    val supportsConnectToggle: Boolean = true,
)

/**
 * MCP servers for one agent.
 *
 * Each agent keeps its own server list, so this deliberately does not follow the chat's selected
 * runtime: the screen is reached from that agent's settings and must configure that agent.
 */
class McpViewModel(
    private val registry: RuntimeRegistry,
    private val agent: LocalAgent = LocalAgent.OPEN_CODE,
) : ViewModel() {
    private val _state =
        MutableStateFlow(McpUiState(supportsConnectToggle = agent !in setOf(LocalAgent.CLAUDE_CODE, LocalAgent.ANTIGRAVITY)))
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val backend = registry.targetFor(agent) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching { backend.mcpServers() }
                .onSuccess { servers ->
                    _state.update { it.copy(servers = servers, isLoading = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message, isLoading = false) }
                }
        }
    }

    fun connect(name: String) {
        val backend = registry.targetFor(agent) ?: return
        viewModelScope.launch {
            runCatching { backend.connectMcpServer(name) }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** Disconnects an OpenCode server, or deletes a Claude Code one — see [McpUiState.supportsConnectToggle]. */
    fun disconnect(name: String) {
        val backend = registry.targetFor(agent) ?: return
        viewModelScope.launch {
            runCatching {
                (backend as? ClaudeCodeTarget)?.removeMcpServer(name) ?: backend.disconnectMcpServer(name)
            }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun removeAuth(name: String) {
        val backend = registry.targetFor(agent) ?: return
        viewModelScope.launch {
            runCatching { backend.removeMcpAuth(name) }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun showAddDialog() {
        _state.update { it.copy(showAddDialog = true, addName = "", addCommand = "", addUrl = "") }
    }

    fun dismissAddDialog() {
        _state.update { it.copy(showAddDialog = false) }
    }

    fun updateAddName(value: String) {
        _state.update { it.copy(addName = value) }
    }

    fun updateAddCommand(value: String) {
        _state.update { it.copy(addCommand = value) }
    }

    fun updateAddUrl(value: String) {
        _state.update { it.copy(addUrl = value) }
    }

    fun addServer() {
        val backend = registry.targetFor(agent) ?: return
        val current = _state.value
        if (current.addName.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isAdding = true) }
            val body =
                buildJsonObject {
                    put("name", current.addName.trim())
                    if (current.addUrl.isNotBlank()) {
                        put("type", "remote")
                        put("url", current.addUrl.trim())
                    } else if (current.addCommand.isNotBlank()) {
                        put("type", "local")
                        put("command", current.addCommand.trim())
                    }
                }
            runCatching { backend.addMcpServer(body) }
                .onSuccess {
                    _state.update { it.copy(showAddDialog = false, isAdding = false) }
                    refresh()
                }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message, isAdding = false) }
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
