# Antigravity agent parity with Claude Code / OpenCode

**Date:** 2026-07-27
**Status:** Approved, implementing

## Problem

The official Antigravity runtime (`agy 1.1.7`, wired up in
[2026-07-27-antigravity-oauth-fix]) works end to end, but the surrounding app
experience is far behind Claude Code and OpenCode:

1. Install progress shows a bare indeterminate spinner, no matter what stage
   the install is actually in.
2. The model picker offers a single hardcoded `"default"` model instead of
   the models `agy` actually has access to.
3. `AntigravityAgentSettingsScreen` is a single row ("Local Runtime"); there
   is no inline install/sign-in/model/permission card the way
   `ClaudeCodeAgentSettingsScreen` has one.
4. There is no MCP server management for Antigravity at all — the row does
   not exist, and the backing `RuntimeTarget` methods fall through to
   `unsupported()`.

## Architecture

No new architecture: this mirrors the per-agent split Claude Code already
uses — `Runtime` (process/data) → `Controller` (single observable state
owner) → `Card` (Compose UI, shared between the setup wizard and the agent
settings screen) → settings screen embedding the Card. New files:

- `AntigravityModels.kt` — parses `agy models` output into a `ProviderCatalog`
- `AntigravityMcp.kt` — reads/writes the guest `mcp_config.json`
- `AntigravityPermissionMode.kt` — enum for `--mode` / `--dangerously-skip-permissions`
- `AntigravityCard.kt` — Compose card, replaces the inline
  `AntigravitySignInCard` in `AndroidSetupScreen.kt` and is reused in
  `AntigravityAgentSettingsScreen`

Changed files:

- `AntigravityController.kt` — wires the install `onProgress` callback that
  already exists but was discarded; carries an `AntigravityInstallStatus`
  mirroring `ClaudeInstallStatus`
- `AntigravityRuntime.kt` — loads/caches the model catalog, applies the
  permission mode when building `agy` arguments
- `AntigravityTarget.kt` — implements `mcpServers()` / `addMcpServer()` /
  `disconnectMcpServer()`, replaces the hardcoded single-model
  `listProviders()`
- `AntigravityAgentSettingsScreen.kt`, `AndroidSetupScreen.kt` — use the new
  `AntigravityCard`
- `SettingsNavGraph.kt` — adds `ROUTE_SETTINGS_MCP_ANTIGRAVITY`
- `strings.xml` (+ 7 locales) — the two Antigravity-only install-step strings
  that are currently hardcoded English literals

## 1. Install progress visibility

`LocalRuntimeInstaller.install()` and `AntigravityInstaller.install()`
already compute a percentage and a step string for every stage of an
Antigravity install (Debian Bookworm download/extract, agy binary
download/verify/activate) via an `onProgress(Float?, String)` callback.
`AntigravityController.install()` calls `installer.install(...)` without
passing that callback, so every bit of that detail is thrown away and the UI
falls back to `antigravity.busy -> LinearProgressIndicator()` with no text.

Fix:

- `AntigravityInstallStatus` sealed interface: `Idle`, `Installing(progress:
  Float?, step: Int)`, `Ready(version: String)`, `Failed(message: String)` —
  same shape as `ClaudeInstallStatus`.
- `AntigravityController.install()` passes an `onProgress` lambda that maps
  the emitted strings to string resources and updates `AntigravityControllerState.install`.
- The two Antigravity-only literals in `LocalRuntimeInstaller.kt`
  (`"Preparing Debian Bookworm for Antigravity"`, `"Downloading and
  verifying official Antigravity"`, `"Installing Antigravity"`) move into
  `strings.xml` under an `install_step_antigravity_*` family, translated into
  the same 7 locales the other `install_step_*` strings already have.
- UI: both `RuntimeDownloadStep` (wizard) and `AntigravityCard` render the
  determinate `LinearProgressIndicator(progress = ...)` + percentage text
  pattern `OpenCodeRuntimeProgress` already uses, instead of the bare
  indeterminate spinner.

## 2. Model list

`agy models` returns real, plain-text output — one model per line, captured
live from a signed-in local install:

```
Gemini 3.6 Flash (High)
Gemini 3.6 Flash (Medium)
Gemini 3.6 Flash (Low)
Gemini 3.5 Flash (High)
Gemini 3.5 Flash (Medium)
Gemini 3.5 Flash (Low)
Gemini 3.1 Pro (High)
Gemini 3.1 Pro (Low)
Claude Sonnet 4.6 (Thinking)
Claude Opus 4.6 (Thinking)
GPT-OSS 120B (Medium)
```

Each line is `<base name> (<variant>)`. This is the same shape as
`ClaudeModels`' alias + effort-variant design, so `AntigravityModels.parse()`
groups lines by base name and exposes the parenthetical suffix as a model
variant through the existing `OpenCodeModel.variants` mechanism — no new UI
component is needed, `ModelAndRuntimePickerSheet` already renders variants.

`AntigravityRuntime` runs `agy models` once per successful sign-in (mirrors
the existing `version()` caching: `@Volatile cachedModels`, invalidated by
`invalidateVersion()`/on logout) and `AntigravityTarget.listProviders()`
returns the parsed catalog instead of the single hardcoded `"default"` model.
On failure (not signed in, network error, empty output) it falls back to
today's single-model catalog rather than an empty picker.

**Known open risk, to verify during implementation, not to hide:** passing a
model's display name to `--model` in two manual tests against a real signed-in
`agy` did not visibly change which model answered, and the CLI's own debug
log showed the flag was not populated (`model=""` in `printmode.go`) even
though the response's own "Propagating selected model override" line kept
reporting the default. Implementation still sends the selected value as
`--model` (same pass-through philosophy as `ClaudeModels.cliModel()` — an id
this build doesn't recognize is still forwarded, the CLI is the source of
truth). Whichever way it turns out, the device verification step for this
project must include a real one-turn chat with a non-default model selected
and report plainly whether the CLI actually switches.

## 3. Agent settings screen parity

`AntigravityCard` (new) replaces the `AntigravitySignInCard` composable
currently private to `AndroidSetupScreen.kt`, and is used both in the setup
wizard's sign-in step and in `AntigravityAgentSettingsScreen`, exactly how
`ClaudeCodeCard` is shared today. It renders, top to bottom:

1. Install progress / ready version (§1)
2. Sign-in state machine (existing `AntigravityAuthCoordinator.State`,
   unchanged)
3. Model picker (§2) — only shown once signed in, since the catalog needs a
   live token
4. Permission mode picker (new, below)
5. Update button (mirrors Claude's, calls into the installer)

Permission mode: `agy --help` documents `--mode accept-edits|plan` and
`--dangerously-skip-permissions`, a direct match for Claude's three-tier
model. New `AntigravityPermissionMode` enum:

| Value | agy argument |
|---|---|
| `PLAN` | `--mode plan` |
| `ACCEPT_EDITS` (default) | `--mode accept-edits` |
| `FULL_ACCESS` | `--dangerously-skip-permissions` |

`AntigravityRuntime.send()` reads the current mode (stored the same way
`ClaudeCodeRuntime` stores `defaultPermissionMode`) and appends the matching
argument when building the `agy --print` command.

`AntigravityAgentSettingsScreen` embeds `AntigravityCard` plus an MCP
settings row navigating to the new route, matching
`ClaudeCodeAgentSettingsScreen`'s layout exactly.

## 4. MCP settings

`agy --help` lists no `mcp` subcommand — servers are configured purely
through a file, confirmed against both the CLI's own bundled doc
(`builtin/skills/agy-customizations/docs/mcp_servers.md`) and a real
`~/.gemini/config/mcp_config.json` from a working local install:

```json
{
  "mcpServers": {
    "chrome-devtools-mcp": {
      "command": "/path/to/chrome-devtools-mcp",
      "args": ["--browser-url=http://127.0.0.1:9222"]
    }
  }
}
```

Stdio servers carry `command` (+ optional `args`, `env`); remote servers
carry `serverUrl` instead. Global config lives at
`~/.gemini/config/mcp_config.json` inside the guest rootfs (there is also a
per-plugin variant, out of scope here).

New `AntigravityMcp` object, same style as the already-shipped
`AntigravityGuestSettings`:

- `read(rootfs): List<McpServer>` — parses the JSON, tolerates a missing or
  malformed file by returning an empty list (never crashes the settings
  screen, same posture as the settings.json repair logic)
- `write(rootfs, servers)` — atomic write-temp-then-rename, matching how
  `AntigravityGuestSettings.write()` already touches this rootfs

`AntigravityTarget` implements:

- `mcpServers()` → `AntigravityMcp.read(...)`
- `addMcpServer(body)` → merge one entry, `AntigravityMcp.write(...)`
- `disconnectMcpServer(name)` → remove the entry and rewrite (delete
  semantics, not a live connect/disconnect toggle — same as Claude Code,
  so `McpUiState.supportsConnectToggle = agent !in setOf(CLAUDE_CODE,
  ANTIGRAVITY)`)

No `agy` process is spawned for any of this — it is pure file I/O against the
already-mounted rootfs. `McpScreen`/`McpViewModel` need no changes; only a
new `ROUTE_SETTINGS_MCP_ANTIGRAVITY` route and the settings-screen row that
navigates to it.

## Testing

- `AntigravityModelsTest` — parses the real captured `agy models` output
  above into the expected grouped catalog
- `AntigravityMcpTest` — round-trips the real captured `mcp_config.json`
  above, plus malformed/missing-file fallback
- `AntigravityInstallStatusTest` / controller test — progress callback maps
  to the right `Installing(progress, step)` sequence
- `compileDebugKotlin`, `testDebugUnitTest`, `lintDebug`, `spotlessKotlinCheck`
- Debug APK on `emulator-5554`: install shows real percentage + step text,
  model picker lists the real models, adding an MCP server through the UI
  makes it show up in a fresh `agy` session, permission mode changes the
  `agy` argument list (verified via the sandbox launcher command, not by
  trusting the CLI blindly given the open risk in §2)

## Error handling

- MCP file writes: atomic rename, matching `AntigravityGuestSettings`
- Malformed `mcp_config.json` on disk: treated as empty, not fatal
- `agy models` failure (signed out, network): fall back to today's single
  `"default"` model rather than an empty picker
- Install progress: if `onProgress` is never called before a failure (e.g.
  disk space check fails first), the card still shows `Failed(message)`
  exactly as it does today
