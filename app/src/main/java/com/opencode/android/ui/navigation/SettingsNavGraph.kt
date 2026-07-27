package com.opencode.android.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.opencode.android.data.settings.AppPreferences
import com.opencode.android.data.settings.AppPreferencesRepository
import com.opencode.android.feature.settings.AgentSettingsScreen
import com.opencode.android.feature.settings.AntigravityAgentSettingsScreen
import com.opencode.android.feature.settings.ClaudeCodeAgentSettingsScreen
import com.opencode.android.feature.settings.GitHubSettingsScreen
import com.opencode.android.feature.settings.OpenCodeAgentSettingsScreen
import com.opencode.android.feature.settings.ProviderSettingsScreen
import com.opencode.android.feature.settings.SettingsScreenV2
import com.opencode.android.feature.settings.SettingsViewModel
import com.opencode.android.feature.settings.VoiceSettingsScreen
import com.opencode.android.feature.support.GitHubSupportSheetHost
import com.opencode.android.runtime.RuntimeRegistry

fun NavGraphBuilder.settingsNavGraph(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
    // Getters throughout: NavHost remembers these lambdas, so anything passed as a value is frozen
    // at the composition that built the graph and never updates again.
    notificationsEnabled: () -> Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    appVersion: String,
    onOpenDrawer: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onShowDiagnostics: () -> Unit,
    preferences: () -> AppPreferences,
    appPreferences: AppPreferencesRepository,
    runtimeRegistry: RuntimeRegistry,
    context: Context,
    hasMicrophonePermission: () -> Boolean,
    claude: () -> com.opencode.android.runtime.local.ClaudeCodeUiState,
    claudeActions: ClaudeSettingsActions,
    antigravity: () -> com.opencode.android.runtime.local.AntigravityControllerState,
    antigravityActions: AntigravitySettingsActions,
    onRequestWakeWordPermission: () -> Unit,
) {
    composable(ROUTE_SETTINGS) {
        val settingsState by settingsViewModel.state.collectAsState()
        var showSupportSheet by remember { mutableStateOf(false) }

        SettingsScreenV2(
            assistantConfigured = settingsState.assistantRuntimeId != null,
            notificationsEnabled = notificationsEnabled(),
            onToggleNotifications = onToggleNotifications,
            appVersion = appVersion,
            onOpenDrawer = onOpenDrawer,
            onOpenAssistantSettings = onOpenAssistantSettings,
            onOpenVoiceSettings = { navController.navigate(ROUTE_SETTINGS_VOICE) },
            onOpenProviderSettings = { navController.navigate(ROUTE_SETTINGS_PROVIDERS) },
            onOpenAgentSettings = { navController.navigate(ROUTE_SETTINGS_AGENTS) },
            onOpenGitHubSettings = { navController.navigate(ROUTE_SETTINGS_GITHUB) },
            onOpenLocalRuntime = { navController.navigate(LOCAL_RUNTIME_MANAGEMENT_ROUTE) },
            onOpenRemoteConnection = { navController.navigate(ROUTE_REMOTE_CONNECTION) },
            onOpenWorkspaces = { navController.navigate(ROUTE_WORKSPACES) },
            onOpenDiagnostics = onShowDiagnostics,
            onOpenSupport = { showSupportSheet = true },
            onOpenMcp = { navController.navigate(ROUTE_SETTINGS_MCP) },
            onOpenServerInfo = { navController.navigate(ROUTE_SETTINGS_SERVER_INFO) },
            currentTheme = preferences().theme,
            onThemeChange = { appPreferences.setTheme(it) },
            uiFontSize = preferences().uiFontSize,
            onUiFontSizeChange = { appPreferences.setUiFontSize(it) },
            codeFontSize = preferences().codeFontSize,
            onCodeFontSizeChange = { appPreferences.setCodeFontSize(it) },
            syntaxTheme = preferences().syntaxTheme,
            onSyntaxThemeChange = { appPreferences.setSyntaxTheme(it) },
            toolCallDetailLevel = preferences().toolCallDetailLevel,
            onToolCallDetailLevelChange = { appPreferences.setToolCallDetailLevel(it) },
            autoExpandReasoning = preferences().autoExpandReasoning,
            onAutoExpandReasoningChange = { appPreferences.setAutoExpandReasoning(it) },
            sendBehavior = preferences().sendBehavior,
            onSendBehaviorChange = { appPreferences.setSendBehavior(it) },
        )

        if (showSupportSheet) {
            GitHubSupportSheetHost(
                appVersion = appVersion,
                onDismiss = { showSupportSheet = false },
            )
        }
    }

    composable(ROUTE_SETTINGS_VOICE) {
        val settingsState by settingsViewModel.state.collectAsState()
        VoiceSettingsScreen(
            ttsEnabled = settingsState.ttsEnabled,
            continuousConversation = settingsState.continuousConversation,
            wakeWordEnabled = settingsState.wakeWordEnabled,
            onTtsChange = settingsViewModel::setTtsEnabled,
            onContinuousChange = settingsViewModel::setContinuousConversation,
            onWakeWordChange = { enabled ->
                settingsViewModel.setWakeWordEnabled(enabled)
                if (enabled) {
                    if (hasMicrophonePermission()) {
                        com.opencode.android.feature.wakeword.WakeWordService.start(context)
                    } else {
                        onRequestWakeWordPermission()
                    }
                } else {
                    com.opencode.android.feature.wakeword.WakeWordService.stop(context)
                }
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_AGENTS) {
        AgentSettingsScreen(
            onOpenOpenCode = { navController.navigate(ROUTE_SETTINGS_AGENT_OPENCODE) },
            onOpenClaudeCode = { navController.navigate(ROUTE_SETTINGS_AGENT_CLAUDE) },
            onOpenAntigravity = { navController.navigate(ROUTE_SETTINGS_AGENT_ANTIGRAVITY) },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_AGENT_OPENCODE) {
        OpenCodeAgentSettingsScreen(
            onOpenProviderSettings = { navController.navigate(ROUTE_SETTINGS_PROVIDERS) },
            onOpenMcp = { navController.navigate(ROUTE_SETTINGS_MCP) },
            onOpenLocalRuntime = { navController.navigate(LOCAL_RUNTIME_MANAGEMENT_ROUTE) },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_AGENT_CLAUDE) {
        ClaudeCodeAgentSettingsScreen(
            claude = claude(),
            onInstall = claudeActions.onInstall,
            onUpdate = claudeActions.onUpdate,
            onSelectPermissionMode = claudeActions.onSelectPermissionMode,
            onSignIn = claudeActions.onSignIn,
            onSubmitCode = claudeActions.onSubmitCode,
            onCancelSignIn = claudeActions.onCancelSignIn,
            onSignOut = claudeActions.onSignOut,
            onOpenMcp = { navController.navigate(ROUTE_SETTINGS_MCP_CLAUDE) },
            onOpenUrl = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_AGENT_ANTIGRAVITY) {
        AntigravityAgentSettingsScreen(
            antigravity = antigravity(),
            onInstall = antigravityActions.onInstall,
            onSelectPermissionMode = antigravityActions.onSelectPermissionMode,
            onSignIn = antigravityActions.onSignIn,
            onSubmitCode = antigravityActions.onSubmitCode,
            onCancelSignIn = antigravityActions.onCancelSignIn,
            onSignOut = antigravityActions.onSignOut,
            onOpenMcp = { navController.navigate(ROUTE_SETTINGS_MCP_ANTIGRAVITY) },
            onOpenUrl = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            },
            onOpenLocalRuntime = { navController.navigate(ROUTE_ANDROID_SETUP) },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_PROVIDERS) {
        val settingsState by settingsViewModel.state.collectAsState()
        // Re-read on open: the runtime that owns providers may have started since the last look.
        androidx.compose.runtime.LaunchedEffect(Unit) { settingsViewModel.refreshProviderAuth() }
        ProviderSettingsScreen(
            state = settingsState,
            onOpenProviderAuth = settingsViewModel::openProviderAuth,
            onSelectProviderAuthMethod = settingsViewModel::selectProviderAuthMethod,
            onProviderAuthInput = settingsViewModel::updateProviderAuthInput,
            onProviderApiKey = settingsViewModel::updateProviderApiKey,
            onSubmitProviderAuth = settingsViewModel::submitProviderAuth,
            onCompleteProviderOAuth = settingsViewModel::completeProviderOAuth,
            onDisconnectProvider = settingsViewModel::disconnectProvider,
            onLaunchOAuthBrowser = { url ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                    )
                }.onFailure { error ->
                    settingsViewModel.reportOAuthError(error.message.orEmpty())
                }
            },
            onDismissProviderAuth = settingsViewModel::dismissProviderAuth,
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_GITHUB) {
        val settingsState by settingsViewModel.state.collectAsState()
        GitHubSettingsScreen(
            state = settingsState,
            onConnect = settingsViewModel::beginGitHubDeviceFlow,
            onDisconnect = settingsViewModel::disconnectGitHub,
            onOpenVerification = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    .onFailure { error -> settingsViewModel.reportOAuthError(error.message.orEmpty()) }
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_MCP) {
        com.opencode.android.feature.settings.McpScreen(
            registry = runtimeRegistry,
            agent = com.opencode.android.runtime.LocalAgent.OPEN_CODE,
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_MCP_CLAUDE) {
        com.opencode.android.feature.settings.McpScreen(
            registry = runtimeRegistry,
            agent = com.opencode.android.runtime.LocalAgent.CLAUDE_CODE,
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_MCP_ANTIGRAVITY) {
        com.opencode.android.feature.settings.McpScreen(
            registry = runtimeRegistry,
            agent = com.opencode.android.runtime.LocalAgent.ANTIGRAVITY,
            onBack = { navController.popBackStack() },
        )
    }

    composable(ROUTE_SETTINGS_SERVER_INFO) {
        com.opencode.android.feature.settings.ServerInfoScreen(
            registry = runtimeRegistry,
            onBack = { navController.popBackStack() },
        )
    }
}

/** Claude Code actions the settings graph forwards to its agent screen. */
data class ClaudeSettingsActions(
    val onInstall: () -> Unit,
    val onUpdate: () -> Unit,
    val onSelectPermissionMode: (com.opencode.android.runtime.local.ClaudePermissionMode) -> Unit,
    val onSignIn: () -> Unit,
    val onSubmitCode: (String) -> Unit,
    val onCancelSignIn: () -> Unit,
    val onSignOut: () -> Unit,
)

/** Antigravity actions the settings graph forwards to its agent screen. */
data class AntigravitySettingsActions(
    val onInstall: () -> Unit,
    val onSelectPermissionMode: (com.opencode.android.runtime.local.AntigravityPermissionMode) -> Unit,
    val onSignIn: () -> Unit,
    val onSubmitCode: (String) -> Unit,
    val onCancelSignIn: () -> Unit,
    val onSignOut: () -> Unit,
)
