package com.opencode.android.startup

import android.content.Context
import androidx.startup.Initializer
import com.opencode.android.OpenCodeApplication
import com.opencode.android.hasUsableRuntimeSetup
import com.opencode.android.runtime.LocalRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RuntimeAutoStartInitializer : Initializer<RuntimeAutoStartInitializer.Result> {

    class Result internal constructor(internal val warmupJob: Job?)

    override fun create(context: Context): Result {
        val app = context.applicationContext as OpenCodeApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val runtimeStatus = app.localRuntimeManager.status()
        val setupConfigured = hasUsableRuntimeSetup(
            localRuntimeStatus = runtimeStatus,
            hasRemoteConnection = app.settings.connections().isNotEmpty()
        )
        if (app.settings.onboardingCompleted != setupConfigured) {
            app.settings.onboardingCompleted = setupConfigured
        }

        if (!app.settings.onboardingCompleted) return Result(null)
        if (runtimeStatus is LocalRuntimeStatus.NotInstalled) return Result(null)
        val selectedId = app.settings.selectedRuntimeId
        if (selectedId != null && selectedId != LOCAL_RUNTIME_ID) return Result(null)

        app.localRuntimeController.start()

        var warmupJob: Job? = null
        scope.launch {
            app.localRuntimeManager.state.collect { status ->
                if (status is LocalRuntimeStatus.Ready) {
                    if (app.runtimeRegistry.selected.value?.id != LOCAL_RUNTIME_ID) {
                        app.runtimeRegistry.select(LOCAL_RUNTIME_ID)
                    }
                    warmupJob?.cancel()
                    warmupJob = scope.launch {
                        repeat(CATALOG_WARMUP_ATTEMPTS) {
                            app.catalogRepository.refresh()
                            delay(CATALOG_WARMUP_DELAY_MS)
                            if (app.catalogRepository.state.value.providers.all.isNotEmpty()) return@launch
                        }
                    }
                }
            }
        }

        return Result(warmupJob)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private companion object {
        const val LOCAL_RUNTIME_ID = "local-android"
        const val CATALOG_WARMUP_ATTEMPTS = 4
        const val CATALOG_WARMUP_DELAY_MS = 2500L
    }
}
