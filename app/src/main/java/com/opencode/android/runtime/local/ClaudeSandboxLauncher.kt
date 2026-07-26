package com.opencode.android.runtime.local

import java.io.File

/**
 * Builds PRoot invocations for the Claude Code binary inside the shared Alpine sandbox.
 *
 * Centralised so the binary path and the bind mounts stay identical across the chat process, the
 * sign-in flow and version checks — a mismatch there is invisible until the process fails to start.
 */
object ClaudeSandboxLauncher {
    fun command(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: File,
        workingDirectory: String,
        arguments: List<String>,
        pty: Boolean,
    ): List<String> =
        buildList {
            add(runtime.commandSuite.proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(runtime.rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("/system")
            add("-b")
            add("${workspaceHostDir.absolutePath}:/workspace")
            add("-w")
            add(workingDirectory)
            if (pty) {
                // `claude auth login` only prints its pasteable authorization URL when it believes a
                // human is watching, so the sign-in flow needs a real terminal rather than a pipe.
                add("/usr/bin/script")
                add("-qefc")
                add((listOf(ClaudeCodeInstaller.CLAUDE_BINARY) + arguments).joinToString(" "))
                add("/dev/null")
            } else {
                add(ClaudeCodeInstaller.CLAUDE_BINARY)
                addAll(arguments)
            }
        }

    fun environment(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        prootTmp: File,
        githubToken: String? = null,
    ): Map<String, String> =
        localRuntimeEnvironment(runtime.commandSuite.environment(), prootTmp, githubToken) +
            mapOf(
                // The bundled ripgrep is a glibc build and cannot run on musl; the sandbox installs
                // Alpine's ripgrep instead.
                "USE_BUILTIN_RIPGREP" to "0",
                "CLAUDE_CODE_DISABLE_AUTOUPDATER" to "1",
                "TERM" to "xterm-256color",
                "CI" to "1",
            )
}
