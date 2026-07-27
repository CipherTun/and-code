package com.opencode.android.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.core.api.OpenCodeApiClient
import com.opencode.android.core.api.OpenCodeHealth
import com.opencode.android.data.connection.ConnectionProfile
import com.opencode.android.data.connection.SecureSettingsRepository
import com.opencode.android.data.repository.RuntimeCatalogRepository
import com.opencode.android.runtime.LocalAgent
import com.opencode.android.runtime.LocalRuntimeStatus
import com.opencode.android.runtime.RuntimeRegistry
import com.opencode.android.runtime.RuntimeState
import com.opencode.android.runtime.RuntimeTarget
import com.opencode.android.runtime.RuntimeType
import com.opencode.android.runtime.WorkspaceRef
import com.opencode.android.runtime.local.ClaudeCodeController
import com.opencode.android.runtime.local.ClaudeCodeUiState
import com.opencode.android.runtime.local.ClaudePermissionMode
import com.opencode.android.runtime.local.LocalRuntimeManager
import com.opencode.android.runtime.local.LocalRuntimeServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class RuntimeSummary(
    val id: String,
    val name: String,
    val type: RuntimeType,
    val state: RuntimeState,
    val selected: Boolean,
    /** Which local agent this target runs, or null for remote connections. */
    val agent: LocalAgent?,
)

data class WorkspaceUiState(
    val targets: List<RuntimeSummary> = emptyList(),
    val connections: List<ConnectionProfile> = emptyList(),
    /** Saved connections whose endpoint can no longer be used, so they have no runtime target. */
    val unusableConnections: List<ConnectionProfile> = emptyList(),
    val selectedRuntimeId: String? = null,
    val workspaces: List<WorkspaceRef> = emptyList(),
    val localStatus: LocalRuntimeStatus = LocalRuntimeStatus.NotInstalled,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val claude: ClaudeCodeUiState = ClaudeCodeUiState(),
)

class WorkspaceViewModel(
    private val registry: RuntimeRegistry,
    private val catalog: RuntimeCatalogRepository,
    private val localRuntimeManager: LocalRuntimeManager,
    private val localRuntimeController: LocalRuntimeServiceController,
    private val settings: SecureSettingsRepository,
    private val workspaceHostDir: File,
    private val claudeCode: ClaudeCodeController? = null,
) : ViewModel() {
    private val registeredTick = MutableStateFlow(0)
    private val claudeState: StateFlow<ClaudeCodeUiState> =
        claudeCode?.state ?: MutableStateFlow(ClaudeCodeUiState())

    val state: StateFlow<WorkspaceUiState> =
        combine(
            registry.targets,
            registry.selected,
            catalog.state,
            localRuntimeManager.state,
            combine(registeredTick, claudeState) { _, claude -> claude },
        ) { targets, selected, runtime, localStatus, claude ->
            val profiles = registry.remoteProfiles()
            // Read imperatively: the registry recomputes this set while building the target list, so
            // it is already up to date by the time `targets` emits.
            val unusableIds = registry.unusableProfileIds.value
            WorkspaceUiState(
                targets =
                    targets.map { target ->
                        RuntimeSummary(
                            id = target.id,
                            name = target.displayName,
                            type = target.type,
                            state = target.state.value,
                            selected = target.id == selected?.id,
                            agent = target.agent,
                        )
                    },
                connections = profiles,
                unusableConnections = profiles.filter { it.id in unusableIds },
                selectedRuntimeId = selected?.id,
                workspaces = mergeWorkspaces(runtime.workspaces, registeredProjects(selected)),
                localStatus = localStatus,
                isRefreshing = runtime.isRefreshing,
                error = runtime.error,
                claude = claude,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, WorkspaceUiState())

    init {
        viewModelScope.launch {
            localRuntimeManager.state.collect { status ->
                if (status is LocalRuntimeStatus.Ready) {
                    // Only fill an empty selection. The local runtime reports Ready again on every
                    // watchdog tick, and selecting it unconditionally used to drag the app back off
                    // a PC connection the user had just picked.
                    if (registry.selectIfUnset(LOCAL_RUNTIME_ID)) catalog.refresh()
                }
            }
        }
    }

    /**
     * Folders registered on this device. They are paths inside the Android runtime's filesystem, so
     * they are only meaningful while the Android-local runtime is selected — listing them for a PC
     * connection would offer working folders that do not exist on that machine.
     */
    private fun registeredProjects(selected: RuntimeTarget?): List<WorkspaceRef> {
        if (selected != null && selected.type != RuntimeType.LOCAL) return emptyList()
        return settings.projectPaths.map { path ->
            WorkspaceRef(id = path, name = displayName(path), path = path)
        }
    }

    private fun mergeWorkspaces(
        server: List<WorkspaceRef>,
        registered: List<WorkspaceRef>,
    ): List<WorkspaceRef> {
        val byPath = linkedMapOf<String, WorkspaceRef>()
        registered.forEach { byPath[it.path] = it }
        server.forEach { byPath.putIfAbsent(it.path, it) }
        return byPath.values.toList()
    }

    private fun displayName(serverPath: String): String = serverPath.trimEnd('/').substringAfterLast('/').ifBlank { serverPath }

    fun addProject(serverPath: String) {
        val current = settings.projectPaths.toMutableList()
        if (serverPath !in current) {
            current += serverPath
            settings.projectPaths = current
            registeredTick.update { it + 1 }
        }
    }

    fun removeProject(serverPath: String) {
        settings.projectPaths = settings.projectPaths.filter { it != serverPath }
        registeredTick.update { it + 1 }
    }

    fun deleteProjectFiles(serverPath: String) {
        // Only folders this device registered live under the Android runtime. A folder listed by a
        // PC connection just happens to share a basename with one of ours, and deleting on that
        // resemblance would wipe unrelated local files.
        if (serverPath !in settings.projectPaths) {
            removeProject(serverPath)
            refresh()
            return
        }
        val hostDir = File(workspaceHostDir, displayName(serverPath))
        if (hostDir.exists()) hostDir.deleteRecursively()
        removeProject(serverPath)
        refresh()
    }

    fun selectRuntime(id: String) {
        registry.select(id)
    }

    /**
     * Saves a PC connection. [activate] makes it the running target, which is what the connection
     * screen wants: the user pressed "connect", so the app has to move to that machine even when a
     * local runtime is already set up and selected.
     */
    fun saveConnection(
        form: ConnectionFormState,
        activate: Boolean = true,
    ) {
        if (!form.canSave) return
        registry.upsertRemote(form.toProfile(), select = activate)
    }

    fun deleteConnection(id: String) {
        registry.deleteRemote(id)
    }

    suspend fun testConnection(form: ConnectionFormState): Result<OpenCodeHealth> {
        if (!form.canSave) {
            return Result.failure(IllegalArgumentException("接続情報が不足しています"))
        }
        return runCatching { OpenCodeApiClient(form.toProfile()).health() }
    }

    fun setupLocalRuntime() = localRuntimeController.installAndStart()

    fun startLocalRuntime() = localRuntimeController.start()

    fun stopLocalRuntime() = localRuntimeController.stop()

    fun reinstallLocalRuntime() = localRuntimeController.reinstall()

    fun installClaudeCode() = claudeCode?.install() ?: Unit

    fun updateClaudeCode() = claudeCode?.update() ?: Unit

    fun setClaudePermissionMode(
        mode: ClaudePermissionMode,
        sessionId: String? = null,
    ) = claudeCode?.setPermissionMode(mode, sessionId) ?: Unit

    fun beginClaudeSignIn() = claudeCode?.beginSignIn() ?: Unit

    fun submitClaudeSignInCode(code: String) = claudeCode?.submitSignInCode(code) ?: Unit

    fun cancelClaudeSignIn() = claudeCode?.cancelSignIn() ?: Unit

    fun signOutClaude() = claudeCode?.signOut() ?: Unit

    fun refresh() {
        registry.refresh()
        catalog.refresh()
        claudeCode?.refresh()
    }

    private companion object {
        const val LOCAL_RUNTIME_ID = "local-android"
    }
}
