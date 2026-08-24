package com.yugahashimoto.andcode.startup

import android.content.Context
import androidx.startup.Initializer
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.hasUsableRuntimeSetup
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.AdbConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Runs once per process, via [androidx.startup.AppInitializer], to bring the local runtime's
 * ancillary state in line with whatever is already running: selecting it once it is
 * [Ready][LocalRuntimeStatus.Ready] if nothing else was chosen, restoring the wireless-debugging
 * link, and warming the provider catalog.
 *
 * It used to also *start* the runtime itself, via [restoreIfConfigured] with
 * [RuntimeAutoStartTrigger.AppLaunch] - but this runs for every process the OS ever creates for the
 * app, including the UI-less one `BOOT_COMPLETED` spins up, which made
 * [RuntimeAutoStartTrigger.BootOrPackageReplaced] refusing that trigger in
 * [RuntimeAutoStartReceiver] pointless: this initializer restarted the runtime a moment later in the
 * very same process anyway. That responsibility now lives solely in
 * [com.yugahashimoto.andcode.AndCodeApplication]'s foreground observer, which only ever sees `true`
 * for an actual activity coming on screen - never for a broadcast-only process.
 */
class RuntimeAutoStartInitializer : Initializer<RuntimeAutoStartInitializer.Result> {
    class Result internal constructor(internal val warmupJob: Job?)

    override fun create(context: Context): Result {
        val app = context.applicationContext as AndCodeApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        var warmupJob: Job? = null
        scope.launch {
            app.localRuntimeManager.state.collect { status ->
                if (status is LocalRuntimeStatus.Ready) {
                    // Never override an explicit selection. The user can switch to a PC connection
                    // while the local runtime is still coming up, and Ready is re-emitted on every
                    // watchdog tick — selecting on each one would pull them back to the phone.
                    app.runtimeRegistry.selectIfUnset(LOCAL_RUNTIME_ID)
                    // The adb server is reborn with the Linux runtime, so restore the persisted
                    // wireless-debugging link as soon as it is reachable. Guarded by the current
                    // state because Ready re-emits on every watchdog tick and a redundant
                    // `adb connect` would spawn a proot process each time.
                    if (app.adbConnectionManager.state.value !is AdbConnectionState.Connected) {
                        scope.launch { app.adbConnectionManager.restoreAndReconnect() }
                    }
                    if (app.runtimeRegistry.selected.value?.id != LOCAL_RUNTIME_ID) return@collect
                    warmupJob?.cancel()
                    warmupJob =
                        scope.launch {
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

    companion object {
        /**
         * Reconciles [com.yugahashimoto.andcode.data.connection.SecureSettingsRepository.onboardingCompleted]
         * with whatever local runtime state is actually on disk, independent of whether
         * [restoreIfConfigured] goes on to restart anything. Covers a reinstalled APK (or one that
         * otherwise lost its settings) landing on a device where the runtime, or a remote
         * connection, was already configured - onboarding should not be shown again just because the
         * flag itself did not survive.
         */
        internal fun syncOnboardingCompleted(app: AndCodeApplication) {
            val setupConfigured =
                hasUsableRuntimeSetup(
                    localRuntimeStatus = app.localRuntimeManager.status(),
                    hasRemoteConnection = app.settings.connections().isNotEmpty(),
                )
            if (app.settings.onboardingCompleted != setupConfigured) {
                app.settings.onboardingCompleted = setupConfigured
            }
        }

        /**
         * Reuses the same gate from both the boot/package-replaced receiver
         * ([RuntimeAutoStartReceiver]) and the app's own foreground observer
         * ([com.yugahashimoto.andcode.AndCodeApplication.observeForegroundForRuntimeRestart]).
         * Package updates stop every process belonging to the old APK, so a foreground return alone
         * is not a reliable recovery hook either - both paths matter.
         */
        internal fun restoreIfConfigured(
            app: AndCodeApplication,
            trigger: RuntimeAutoStartTrigger,
        ): Boolean {
            syncOnboardingCompleted(app)

            if (
                !shouldAutoStartLocalRuntime(
                    onboardingCompleted = app.settings.onboardingCompleted,
                    localRuntimeStatus = app.localRuntimeManager.status(),
                    selectedRuntimeId = app.settings.selectedRuntimeId,
                    trigger = trigger,
                    localRuntimeIdleStopEnabled = app.settings.localRuntimeIdleStopEnabled,
                )
            ) {
                return false
            }

            app.localRuntimeController.start()
            return true
        }

        private const val CATALOG_WARMUP_ATTEMPTS = 4
        private const val CATALOG_WARMUP_DELAY_MS = 2500L
    }
}
