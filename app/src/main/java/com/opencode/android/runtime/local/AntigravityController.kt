package com.opencode.android.runtime.local

import com.opencode.android.runtime.LocalAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where an Antigravity install has got to, so the UI can show progress instead of a dead spinner. */
sealed interface AntigravityInstallStatus {
    data object Idle : AntigravityInstallStatus

    /** [step] is already localized: [LocalRuntimeInstaller] resolves its own string resources. */
    data class Installing(val progress: Float?, val step: String) : AntigravityInstallStatus

    data class Ready(val version: String) : AntigravityInstallStatus

    data class Failed(val message: String) : AntigravityInstallStatus
}

data class AntigravityControllerState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: AntigravityInstallStatus = AntigravityInstallStatus.Idle,
    val auth: AntigravityAuthCoordinator.State = AntigravityAuthCoordinator.State.Idle,
    val permissionMode: AntigravityPermissionMode = AntigravityPermissionMode.DEFAULT,
) {
    /** Kept for call sites that only care whether an install is in flight. */
    val busy: Boolean get() = install is AntigravityInstallStatus.Installing

    /** Kept for call sites that only care about the last failure message. */
    val error: String? get() = (install as? AntigravityInstallStatus.Failed)?.message
}

/** Single owner for install/update/auth state; UI can observe this without owning a process. */
class AntigravityController(
    private val installer: LocalRuntimeInstaller,
    private val target: AntigravityTarget,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(AntigravityControllerState())

    val state: StateFlow<AntigravityControllerState> =
        combine(mutableState, target.auth.state, target.defaultPermissionMode) { base, auth, mode ->
            base.copy(auth = auth, permissionMode = mode)
        }.stateIn(scope, SharingStarted.Eagerly, AntigravityControllerState())

    init {
        scope.launch {
            // Rehydrate after process death/restart. The controller is UI state, while the
            // metadata and rootfs on disk are the source of truth for an already downloaded CLI.
            val installed = installer.installedRuntime()
            val rootfs = installed?.antigravityRootfs
            val binaryInstalled = rootfs?.resolve("usr/local/bin/agy")?.let { it.isFile && it.canExecute() } == true
            if (binaryInstalled) {
                val version = target.runtime.version()
                mutableState.value =
                    mutableState.value.copy(
                        installed = true,
                        version = version,
                        install = version?.let(AntigravityInstallStatus::Ready) ?: AntigravityInstallStatus.Idle,
                    )
                // The token lives in the guest rootfs, so a restarted app is still signed in even
                // though the in-memory coordinator starts at Idle. Ask the official CLI instead of
                // showing a misleading "Signed out".
                if (target.auth.isSignedIn()) {
                    target.auth.markSignedIn()
                    target.runtime.invalidateModels()
                }
            }
        }
        scope.launch {
            target.auth.state.collect { auth ->
                // A model list fetched while signed out is just the placeholder; refresh once
                // sign-in actually completes so the picker shows the real catalog.
                if (auth is AntigravityAuthCoordinator.State.SignedIn) target.runtime.invalidateModels()
            }
        }
    }

    fun install() {
        if (mutableState.value.install is AntigravityInstallStatus.Installing) return
        mutableState.value = mutableState.value.copy(install = AntigravityInstallStatus.Installing(0f, ""))
        scope.launch {
            runCatching {
                installer.install(setOf(LocalAgent.ANTIGRAVITY)) { progress, step ->
                    mutableState.value = mutableState.value.copy(install = AntigravityInstallStatus.Installing(progress, step))
                }
            }
                .onSuccess {
                    target.runtime.invalidateVersion()
                    target.connect()
                    val version =
                        target.state.value.let { (it as? com.opencode.android.runtime.RuntimeState.Connected)?.version }
                    mutableState.value =
                        AntigravityControllerState(
                            installed = true,
                            version = version,
                            install = version?.let(AntigravityInstallStatus::Ready) ?: AntigravityInstallStatus.Idle,
                        )
                }
                .onFailure { error ->
                    mutableState.value =
                        AntigravityControllerState(install = AntigravityInstallStatus.Failed(error.message ?: "Install failed"))
                }
        }
    }

    fun logout() {
        target.auth.logout()
    }

    fun beginAuth() {
        runCatching { target.auth.start() }
            .onFailure {
                mutableState.value =
                    mutableState.value.copy(
                        auth = AntigravityAuthCoordinator.State.Failed(it.message ?: "Unable to start sign-in", ""),
                    )
            }
    }

    fun submitAuthCode(code: String) = target.auth.submitCode(code)

    fun cancelAuth() = target.auth.cancel()

    fun setPermissionMode(mode: AntigravityPermissionMode) = target.setPermissionMode(mode)
}
