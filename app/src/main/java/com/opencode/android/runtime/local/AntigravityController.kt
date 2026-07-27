package com.opencode.android.runtime.local

import com.opencode.android.runtime.LocalAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AntigravityControllerState(
    val installed: Boolean = false,
    val version: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val auth: AntigravityAuthCoordinator.State = AntigravityAuthCoordinator.State.Idle,
)

/** Single owner for install/update/auth state; UI can observe this without owning a process. */
class AntigravityController(
    private val installer: LocalRuntimeInstaller,
    private val target: AntigravityTarget,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(AntigravityControllerState())
    val state: StateFlow<AntigravityControllerState> = mutableState.asStateFlow()

    init {
        scope.launch {
            // Rehydrate after process death/restart. The controller is UI state, while the
            // metadata and rootfs on disk are the source of truth for an already downloaded CLI.
            val installed = installer.installedRuntime()
            val rootfs = installed?.antigravityRootfs
            val binaryInstalled = rootfs?.resolve("usr/local/bin/agy")?.let { it.isFile && it.canExecute() } == true
            if (binaryInstalled) {
                mutableState.value = mutableState.value.copy(installed = true, busy = false)
                // The token lives in the guest rootfs, so a restarted app is still signed in even
                // though the in-memory coordinator starts at Idle. Ask the official CLI instead of
                // showing a misleading "Signed out".
                if (target.auth.isSignedIn()) target.auth.markSignedIn()
            }
        }
        scope.launch {
            target.auth.state.collect { auth ->
                mutableState.value = mutableState.value.copy(auth = auth)
            }
        }
    }

    fun install() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        scope.launch {
            runCatching { installer.install(setOf(LocalAgent.ANTIGRAVITY)) }
                .onSuccess {
                    target.runtime.invalidateVersion()
                    target.connect()
                    mutableState.value =
                        AntigravityControllerState(
                            true,
                            target.state.value.let {
                                (it as? com.opencode.android.runtime.RuntimeState.Connected)?.version
                            },
                            false,
                        )
                }
                .onFailure { mutableState.value = AntigravityControllerState(error = it.message, busy = false) }
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
}
