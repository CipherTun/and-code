package com.opencode.android.runtime.local

import java.io.File

object AntigravitySandboxLauncher {
    fun command(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: String,
        arguments: List<String>,
        pty: Boolean,
    ): List<String> = buildList {
        add(runtime.commandSuite.proot.absolutePath); add("--kill-on-exit"); add("--link2symlink"); add("-0")
        add("-r"); add(runtime.rootfs.absolutePath); add("-b"); add("/dev"); add("-b"); add("/proc")
        add("-b"); add("/sys"); add("-b"); add("/system"); add("-b"); add("$workspaceHostDir:/workspace")
        add("-w"); add("/workspace")
        if (pty) { add("/usr/bin/script"); add("-qefc"); add((listOf("/usr/local/bin/agy") + arguments).joinToString(" ")); add("/dev/null") }
        else { add("/usr/local/bin/agy"); addAll(arguments) }
    }

    fun environment(runtime: LocalRuntimeInstaller.InstalledRuntime, tmp: File): Map<String, String> =
        localRuntimeEnvironment(runtime.commandSuite.environment(), tmp) + mapOf(
            "HOME" to "/root", "TERM" to "xterm-256color", "AGY_CLI_DISABLE_AUTO_UPDATE" to "1",
            "AGY_CLI_HIDE_ACCOUNT_INFO" to "1", "CI" to "1",
        )
}
