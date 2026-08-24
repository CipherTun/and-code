package com.yugahashimoto.andcode.startup

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus

internal const val LOCAL_RUNTIME_ID = "local-android"

/** Which path is asking the runtime to come back; see [shouldAutoStartLocalRuntime]. */
internal enum class RuntimeAutoStartTrigger {
    /** The app process starting. Always allowed to start the runtime when it is otherwise eligible. */
    AppLaunch,

    /**
     * `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`. With idle-stop on, starting the runtime here only
     * for the watchdog to stop it again minutes later burns battery for nothing the user asked for
     * - the schedule path and app launch both already start it on demand.
     */
    BootOrPackageReplaced,
}

/**
 * Keeps the automatic recovery path scoped to a configured local runtime. An explicit remote
 * selection must never be replaced just because the app was updated or the device rebooted.
 *
 * [trigger] further restricts the reboot/package-replace path: when [localRuntimeIdleStopEnabled]
 * is on, that path is refused outright, since app launch and scheduled runs already cover starting
 * the runtime on demand. It only matters for that trigger - app launch always starts regardless of
 * the setting, because a user who opens the app plainly wants the runtime up right now.
 */
internal fun shouldAutoStartLocalRuntime(
    onboardingCompleted: Boolean,
    localRuntimeStatus: LocalRuntimeStatus,
    selectedRuntimeId: String?,
    trigger: RuntimeAutoStartTrigger,
    localRuntimeIdleStopEnabled: Boolean,
): Boolean =
    onboardingCompleted &&
        localRuntimeStatus !is LocalRuntimeStatus.NotInstalled &&
        (selectedRuntimeId == null || selectedRuntimeId == LOCAL_RUNTIME_ID) &&
        (trigger == RuntimeAutoStartTrigger.AppLaunch || !localRuntimeIdleStopEnabled)
