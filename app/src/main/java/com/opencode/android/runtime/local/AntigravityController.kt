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
)

/** Single owner for install/update/auth state; UI can observe this without owning a process. */
class AntigravityController(
    private val installer: LocalRuntimeInstaller,
    private val target: AntigravityTarget,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(AntigravityControllerState())
    val state: StateFlow<AntigravityControllerState> = mutableState.asStateFlow()

    fun install() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        scope.launch {
            runCatching { installer.install(setOf(LocalAgent.ANTIGRAVITY)) }
                .onSuccess {
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
}
