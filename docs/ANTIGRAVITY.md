# Antigravity local runtime

The Android app provisions the official Google Antigravity CLI release in the shared Alpine/PRoot environment. Release 1.1.7 is pinned in `AntigravityManifest` and every archive is downloaded through `VerifiedRuntimeDownloader` and SHA-256 checked before activation.

The Termux fork is reference material only. Its native-Termux wrapper and patched binaries are not bundled or used. The runtime uses Alpine `gcompat`, `util-linux`, and CA certificates; an ABI/loader failure is reported as unsupported instead of silently installing an unofficial binary.

OAuth is a remote PTY flow. The browser URL and one-time code are passed to the PTY, while credentials remain in `/root/.gemini` inside the Linux rootfs and are never copied into Android preferences. `AGY_CLI_DISABLE_AUTO_UPDATE=1` is set so updates remain verified and app-controlled.

Hook records use schema version 1 JSONL and contain only conversation/transcript paths, event type, tool metadata, step and stop reason. Tokens, prompts and environment variables must not be written to the hook bridge. Session records retain the app UUID, Antigravity conversation id, workspace and last step so a killed process can be resumed.

Device acceptance still requires an x86_64 emulator and arm64 device: install and digest verification, `agy --version`, `models`, browser OAuth, a smoke prompt, file/tool use, permission/question flows, abort, session switching, network recovery and task-kill relaunch. An APK build or version command alone is not completion evidence.
