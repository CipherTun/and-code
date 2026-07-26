# Claude Code on Android

Claude Code is an additional local runtime target. It reuses the installed Alpine Linux rootfs,
PRoot launcher, `/workspace` bind mount, command environment, logs, and workspace registration used
by the local OpenCode runtime. The APK does not contain or redistribute a Claude binary.

## Installation

From Workspaces, select `Claude Code (Android local)` and choose Install. The app adds Anthropic's
official signed Alpine repository key and stable repository inside the existing rootfs, then runs:

```sh
apk add --no-cache claude-code
claude --version
```

Updates use `apk update && apk upgrade claude-code`. The official Alpine package is used because
Anthropic documents Alpine 3.19+ and Linux ARM64 support. `USE_BUILTIN_RIPGREP=0` is set because the
shared rootfs already provides Alpine's ripgrep package.

## Execution

Prompts run in a long-lived interactive Claude process in the same PRoot environment and workspace mount as OpenCode. The process is attached to a pseudo-terminal provided by Alpine `util-linux`:

```text
script -qefc /usr/local/bin/claude /dev/null
```

Prompts and permission/question answers are written to the PTY. The subprocess output is converted
into the existing chat event model. The process is cancelled by destroying the managed process.
Session metadata and normalized messages are additionally persisted by the Android runtime.

Authentication remains Claude Code's responsibility. The runtime exposes the official
`claude auth status` command and does not create or handle OAuth tokens. The Workspaces screen invokes
the official `claude auth login` command from the Sign in button; any browser/device-code flow is
therefore owned by Claude Code rather than reimplemented in Android.

## History and Events

Session metadata and normalized Claude messages are stored in the app-private runtime directory.
Assistant text, reasoning, tool use, tool results, and final results are converted to the existing
chat event/part model, so the existing permission, tool, patch, and streaming UI can render them.
