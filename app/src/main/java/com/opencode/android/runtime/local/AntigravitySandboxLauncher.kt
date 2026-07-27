package com.opencode.android.runtime.local

import java.io.File

object AntigravitySandboxLauncher {
    fun command(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: String,
        arguments: List<String>,
        pty: Boolean,
    ): List<String> =
        buildList {
            val rootfs = runtime.antigravityRootfs ?: runtime.rootfs
            add(runtime.commandSuite.proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("/system")
            add("-b")
            add("$workspaceHostDir:/workspace")
            add("-w")
            add("/workspace")
            if (pty) {
                add("/usr/bin/script")
                add("-qefc")
                add((listOf("/usr/local/bin/agy") + arguments).joinToString(" "))
                add("/dev/null")
            } else {
                add("/usr/local/bin/agy")
                addAll(arguments)
            }
        }

    fun environment(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        tmp: File,
        githubToken: String? = null,
    ): Map<String, String> =
        localRuntimeEnvironment(runtime.commandSuite.environment(), tmp) +
            mapOf(
                // PR #98's Android tool integration bind-mounts /system into the guest. Keep the
                // guest PATH aligned so agy can find adb and other explicitly exposed host tools.
                "PATH" to "/usr/local/bin:/usr/bin:/bin:/system/bin:/system/xbin",
                "HOME" to "/root",
                "TERM" to "xterm-256color",
                "AGY_CLI_DISABLE_AUTO_UPDATE" to "1",
                "AGY_CLI_HIDE_ACCOUNT_INFO" to "1",
                "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
                "SSL_CERT_DIR" to "/etc/ssl/certs",
                "CI" to "1",
            ) + githubToken.orEmpty().takeIf { it.isNotBlank() }?.let { mapOf("GH_TOKEN" to it) }.orEmpty()
}
