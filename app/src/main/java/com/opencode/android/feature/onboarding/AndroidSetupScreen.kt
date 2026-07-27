package com.opencode.android.feature.onboarding

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.ProviderAuthMethod
import com.opencode.android.feature.settings.ProviderAuthDialog
import com.opencode.android.feature.settings.SettingsUiState
import com.opencode.android.feature.workspace.ClaudeCodeCard
import com.opencode.android.runtime.LocalAgent
import com.opencode.android.runtime.LocalRuntimeStatus
import com.opencode.android.runtime.local.AntigravityAuthCoordinator
import com.opencode.android.runtime.local.AntigravityControllerState
import com.opencode.android.runtime.local.ClaudeCodeUiState
import com.opencode.android.runtime.local.ClaudeInstallStatus
import com.opencode.android.runtime.local.ClaudePermissionMode
import com.opencode.android.ui.theme.OpenCodeAndroidTheme
import kotlinx.coroutines.delay

private const val TOTAL_STEPS = 4

/**
 * Guided setup: choose agents, install them, sign in, then connect GitHub.
 *
 * The agent choice comes first because it decides what is downloaded — installing Claude Code alone
 * skips the OpenCode binary entirely — and which sign-in the third step has to offer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidSetupScreen(
    runtimeStatus: LocalRuntimeStatus,
    claude: ClaudeCodeUiState,
    antigravity: AntigravityControllerState = AntigravityControllerState(),
    onStartSetup: (Set<LocalAgent>) -> Unit,
    onSelectClaudePermissionMode: (ClaudePermissionMode) -> Unit,
    onBeginClaudeSignIn: () -> Unit,
    onSubmitClaudeSignInCode: (String) -> Unit,
    onCancelClaudeSignIn: () -> Unit,
    onSignOutClaude: () -> Unit,
    onBeginAntigravitySignIn: () -> Unit = {},
    onSubmitAntigravitySignInCode: (String) -> Unit = {},
    onCancelAntigravitySignIn: () -> Unit = {},
    onSignOutAntigravity: () -> Unit = {},
    onOpenUrl: (String) -> Unit,
    settingsState: SettingsUiState,
    onOpenProviderAuth: (String) -> Unit,
    onSelectProviderAuthMethod: (Int) -> Unit,
    onProviderAuthInput: (String, String) -> Unit,
    onProviderApiKey: (String) -> Unit,
    onSubmitProviderAuth: () -> Unit,
    onCompleteProviderOAuth: (String) -> Unit,
    onDisconnectProvider: (String) -> Unit,
    onDismissProviderAuth: () -> Unit,
    onRefreshProviderAuth: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onConnectGitHub: () -> Unit = {},
    onOpenGitHubVerification: (String) -> Unit = {},
    onDisconnectGitHub: () -> Unit = {},
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    var selectedAgents by rememberSaveable(
        stateSaver =
            listSaver<Set<LocalAgent>, String>(
                save = { agents -> agents.map(LocalAgent::id) },
                restore = { ids -> ids.mapNotNull(LocalAgent::fromId).toSet() },
            ),
    ) { mutableStateOf(setOf(LocalAgent.CLAUDE_CODE)) }

    val openCodeSelected = LocalAgent.OPEN_CODE in selectedAgents
    val claudeSelected = LocalAgent.CLAUDE_CODE in selectedAgents
    val antigravitySelected = LocalAgent.ANTIGRAVITY in selectedAgents
    val openCodeReady = runtimeStatus is LocalRuntimeStatus.Ready || runtimeStatus is LocalRuntimeStatus.Stopped
    val antigravityReady = antigravitySelected && antigravity.installed && !antigravity.busy
    val installComplete = (!openCodeSelected || openCodeReady) && (!claudeSelected || claude.installed) && (!antigravitySelected || antigravityReady)

    var currentStep by rememberSaveable { mutableIntStateOf(1) }

    // With both agents selected the sandbox is built once for OpenCode and Claude Code is added to
    // it, so the second install only starts after the first has produced a usable runtime.
    LaunchedEffect(openCodeReady, claudeSelected, claude.installed, claude.install) {
        if (!claudeSelected || !openCodeSelected) return@LaunchedEffect
        if (openCodeReady && !claude.installed && claude.install is ClaudeInstallStatus.Idle) {
            onStartSetup(setOf(LocalAgent.CLAUDE_CODE))
        }
    }

    LaunchedEffect(openCodeReady, claude.installed, antigravitySelected, antigravity.installed, antigravity.busy, antigravity.error) {
        if (!antigravitySelected || antigravity.installed || antigravity.busy || antigravity.error != null) return@LaunchedEffect
        // The shared rootfs must be activated before the official agy asset is copied into it.
        // When OpenCode was selected, its service owns the first install; add Antigravity after it
        // reports a usable environment. With Antigravity alone, start its installer immediately.
        if (openCodeSelected && !openCodeReady) return@LaunchedEffect
        if (!openCodeSelected && claudeSelected && !claude.installed) return@LaunchedEffect
        onStartSetup(setOf(LocalAgent.ANTIGRAVITY))
    }

    LaunchedEffect(installComplete) {
        if (installComplete && currentStep == 2) currentStep = 3
    }

    LaunchedEffect(openCodeReady, openCodeSelected, settingsState.availableProviders, settingsState.providerAuthMethods) {
        if (!openCodeSelected || !openCodeReady) return@LaunchedEffect
        if (settingsState.availableProviders.isNotEmpty() && settingsState.providerAuthMethods.isNotEmpty()) return@LaunchedEffect
        delay(2000)
        onRefreshCatalog()
        onRefreshProviderAuth()
    }

    val primaryAction: SetupPrimaryAction? =
        when (currentStep) {
            1 ->
                SetupPrimaryAction(
                    label = stringResource(R.string.setup_next_action),
                    enabled = selectedAgents.isNotEmpty(),
                    onClick = {
                        currentStep = 2
                        if (!installComplete) onStartSetup(selectedAgents)
                    },
                )
            2 ->
                if (installComplete) {
                    SetupPrimaryAction(stringResource(R.string.setup_next_action), true) { currentStep = 3 }
                } else if (runtimeStatus is LocalRuntimeStatus.Broken || claude.install is ClaudeInstallStatus.Failed || antigravity.error != null) {
                    SetupPrimaryAction(stringResource(R.string.claude_retry_install_button), true) {
                        onStartSetup(if (antigravity.error != null) setOf(LocalAgent.ANTIGRAVITY) else selectedAgents)
                    }
                } else {
                    null
                }
            3 -> SetupPrimaryAction(stringResource(R.string.setup_next_action), true) { currentStep = 4 }
            else -> SetupPrimaryAction(stringResource(R.string.setup_complete_button), true, onFinish)
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.android_setup_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentStep > 1) currentStep -= 1 else onBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        bottomBar = {
            SetupBottomBar(
                currentStep = currentStep,
                primaryAction = primaryAction,
                onSkip = if (currentStep >= 3) onFinish else null,
                onBackStep = { currentStep -= 1 },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SetupProgress(currentStep = currentStep)
            when (currentStep) {
                1 ->
                    AgentSelectionStep(
                        selectedAgents = selectedAgents,
                        onToggle = { agent ->
                            selectedAgents =
                                if (agent in selectedAgents) selectedAgents - agent else selectedAgents + agent
                        },
                    )
                2 ->
                    RuntimeDownloadStep(
                        runtimeStatus = runtimeStatus,
                        claude = claude,
                        antigravity = antigravity,
                        openCodeSelected = openCodeSelected,
                        claudeSelected = claudeSelected,
                        antigravitySelected = antigravitySelected,
                    )
                3 ->
                    SignInStep(
                        claudeSelected = claudeSelected,
                        openCodeSelected = openCodeSelected,
                        antigravitySelected = antigravitySelected,
                        claude = claude,
                        antigravity = antigravity,
                        onBeginClaudeSignIn = onBeginClaudeSignIn,
                        onSubmitClaudeSignInCode = onSubmitClaudeSignInCode,
                        onCancelClaudeSignIn = onCancelClaudeSignIn,
                        onSignOutClaude = onSignOutClaude,
                        onBeginAntigravitySignIn = onBeginAntigravitySignIn,
                        onSubmitAntigravitySignInCode = onSubmitAntigravitySignInCode,
                        onCancelAntigravitySignIn = onCancelAntigravitySignIn,
                        onSignOutAntigravity = onSignOutAntigravity,
                        onOpenUrl = onOpenUrl,
                        onSelectClaudePermissionMode = onSelectClaudePermissionMode,
                        settingsState = settingsState,
                        onOpenProviderAuth = onOpenProviderAuth,
                        onDisconnectProvider = onDisconnectProvider,
                    )
                else ->
                    GitHubConnectionStep(
                        settingsState = settingsState,
                        onConnect = onConnectGitHub,
                        onDisconnect = onDisconnectGitHub,
                        onOpenVerification = onOpenGitHubVerification,
                    )
            }
        }
    }

    settingsState.providerAuthDialog?.let { dialog ->
        ProviderAuthDialog(
            state = dialog,
            onSelectMethod = onSelectProviderAuthMethod,
            onInputChange = onProviderAuthInput,
            onApiKeyChange = onProviderApiKey,
            onSubmit = onSubmitProviderAuth,
            onCompleteCode = onCompleteProviderOAuth,
            onLaunchBrowser = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            },
            onDismiss = onDismissProviderAuth,
        )
    }
}

@Composable
private fun GitHubConnectionStep(
    settingsState: SettingsUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenVerification: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeader(
            title = stringResource(R.string.github_git_operations),
            description = stringResource(R.string.setup_github_optional_description),
        )
        Text(settingsState.githubLogin ?: stringResource(R.string.github_not_connected))
        settingsState.githubUserCode?.let { code ->
            Text(stringResource(R.string.github_verification_code, code), fontWeight = FontWeight.SemiBold)
        }
        settingsState.githubMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = if (settingsState.githubLogin == null) onConnect else onDisconnect,
            enabled = settingsState.githubConfigured && !settingsState.githubPolling,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (settingsState.githubPolling) {
                    stringResource(R.string.github_waiting_for_authorization)
                } else if (settingsState.githubLogin == null) {
                    stringResource(R.string.github_connect)
                } else {
                    stringResource(R.string.github_disconnect)
                },
            )
        }
        settingsState.githubVerificationUrl?.let { url ->
            OutlinedButton(onClick = { onOpenVerification(url) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.github_open_verification))
            }
        }
    }
}

private data class SetupPrimaryAction(
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun SetupProgress(currentStep: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.setup_step_counter, currentStep, TOTAL_STEPS),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (1..TOTAL_STEPS).forEach { step ->
                val completed = step < currentStep
                val active = step == currentStep
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color =
                        when {
                            active -> MaterialTheme.colorScheme.primary
                            completed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    contentColor =
                        when {
                            active -> MaterialTheme.colorScheme.onPrimary
                            completed -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (completed) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_step_completed),
                                modifier = Modifier.size(17.dp),
                            )
                        } else {
                            Text(step.toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (step < TOTAL_STEPS) {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                                .height(1.dp),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color =
                                if (step < currentStep) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                },
                        ) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentSelectionStep(
    selectedAgents: Set<LocalAgent>,
    onToggle: (LocalAgent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_agents),
            description = stringResource(R.string.setup_agents_description),
        )
        AgentOption(
            title = stringResource(R.string.agent_claude_code_name),
            description = stringResource(R.string.setup_agent_claude_code_desc),
            selected = LocalAgent.CLAUDE_CODE in selectedAgents,
            onToggle = { onToggle(LocalAgent.CLAUDE_CODE) },
        )
        AgentOption(
            title = stringResource(R.string.agent_opencode_name),
            description = stringResource(R.string.setup_agent_opencode_desc),
            selected = LocalAgent.OPEN_CODE in selectedAgents,
            onToggle = { onToggle(LocalAgent.OPEN_CODE) },
        )
        AgentOption(
            title = stringResource(R.string.agent_antigravity_name),
            description = stringResource(R.string.setup_agent_antigravity_desc),
            selected = LocalAgent.ANTIGRAVITY in selectedAgents,
            onToggle = { onToggle(LocalAgent.ANTIGRAVITY) },
        )
        if (selectedAgents.size >= 2) {
            Text(
                text = stringResource(R.string.setup_runtime_shared_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectedAgents.isEmpty()) {
            Text(
                text = stringResource(R.string.setup_agents_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AgentOption(
    title: String,
    description: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border =
            BorderStroke(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RuntimeDownloadStep(
    runtimeStatus: LocalRuntimeStatus,
    claude: ClaudeCodeUiState,
    antigravity: AntigravityControllerState,
    openCodeSelected: Boolean,
    claudeSelected: Boolean,
    antigravitySelected: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_download),
            description = stringResource(R.string.setup_download_agents_description),
        )
        if (openCodeSelected) {
            SetupPanel {
                Text(stringResource(R.string.agent_opencode_name), fontWeight = FontWeight.SemiBold)
                OpenCodeRuntimeProgress(runtimeStatus)
            }
        }
        if (claudeSelected) {
            SetupPanel {
                Text(stringResource(R.string.agent_claude_code_name), fontWeight = FontWeight.SemiBold)
                ClaudeInstallProgress(claude)
            }
        }
        if (antigravitySelected) {
            SetupPanel {
                Text(stringResource(R.string.agent_antigravity_name), fontWeight = FontWeight.SemiBold)
                when {
                    antigravity.busy -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    antigravity.installed -> ReadyAgentRow(antigravity.version ?: "Antigravity 1.1.7")
                    antigravity.error != null -> Text(antigravity.error, color = MaterialTheme.colorScheme.error)
                    else -> Text(stringResource(R.string.setup_runtime_not_installed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SetupPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ClaudeInstallProgress(claude: ClaudeCodeUiState) {
    when (val install = claude.install) {
        is ClaudeInstallStatus.Installing -> {
            Text(stringResource(install.step), fontWeight = FontWeight.Medium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ClaudeInstallStatus.Ready -> ReadyAgentRow(stringResource(R.string.claude_installed_version, install.version))
        is ClaudeInstallStatus.Failed ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = stringResource(R.string.cd_error),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(install.message, color = MaterialTheme.colorScheme.error)
            }
        ClaudeInstallStatus.Idle ->
            if (claude.installed) {
                ReadyAgentRow(stringResource(R.string.claude_installed_version, claude.version.orEmpty()))
            } else {
                Text(
                    stringResource(R.string.claude_status_not_installed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
    }
}

@Composable
private fun OpenCodeRuntimeProgress(runtimeStatus: LocalRuntimeStatus) {
    when (runtimeStatus) {
        LocalRuntimeStatus.NotInstalled -> {
            Text(
                stringResource(R.string.setup_runtime_not_installed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is LocalRuntimeStatus.Installing -> {
            Text(runtimeStatus.step, fontWeight = FontWeight.Medium)
            if (runtimeStatus.progress != null) {
                LinearProgressIndicator(
                    progress = { runtimeStatus.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${(runtimeStatus.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is LocalRuntimeStatus.Starting -> {
            Text(stringResource(R.string.starting_opencode_version, runtimeStatus.version))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is LocalRuntimeStatus.Updating -> {
            Text(runtimeStatus.step)
            LinearProgressIndicator(
                progress = { runtimeStatus.progress?.coerceIn(0f, 1f) ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is LocalRuntimeStatus.Ready -> ReadyAgentRow("OpenCode ${runtimeStatus.version}")
        is LocalRuntimeStatus.Stopped -> ReadyAgentRow("OpenCode ${runtimeStatus.version}")
        is LocalRuntimeStatus.Broken -> {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = stringResource(R.string.cd_error),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(runtimeStatus.reason, color = MaterialTheme.colorScheme.error)
            }
        }
        is LocalRuntimeStatus.UnsupportedAbi -> {
            Text(
                stringResource(R.string.unsupported_abi, runtimeStatus.abi),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ReadyAgentRow(detail: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.cd_runtime_ready),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(stringResource(R.string.setup_runtime_ready), fontWeight = FontWeight.Medium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SignInStep(
    claudeSelected: Boolean,
    openCodeSelected: Boolean,
    antigravitySelected: Boolean,
    claude: ClaudeCodeUiState,
    antigravity: AntigravityControllerState,
    onBeginClaudeSignIn: () -> Unit,
    onSubmitClaudeSignInCode: (String) -> Unit,
    onCancelClaudeSignIn: () -> Unit,
    onSignOutClaude: () -> Unit,
    onBeginAntigravitySignIn: () -> Unit,
    onSubmitAntigravitySignInCode: (String) -> Unit,
    onCancelAntigravitySignIn: () -> Unit,
    onSignOutAntigravity: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSelectClaudePermissionMode: (ClaudePermissionMode) -> Unit,
    settingsState: SettingsUiState,
    onOpenProviderAuth: (String) -> Unit,
    onDisconnectProvider: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_sign_in),
            description = stringResource(R.string.setup_sign_in_description),
        )
        if (claudeSelected) {
            SetupPanel {
                Text(stringResource(R.string.agent_claude_code_name), fontWeight = FontWeight.SemiBold)
                ClaudeCodeCard(
                    claude = claude,
                    onInstall = {},
                    onUpdate = {},
                    onSelectPermissionMode = onSelectClaudePermissionMode,
                    onSignIn = onBeginClaudeSignIn,
                    onSubmitCode = onSubmitClaudeSignInCode,
                    onCancelSignIn = onCancelClaudeSignIn,
                    onSignOut = onSignOutClaude,
                    onOpenUrl = onOpenUrl,
                    showInstallActions = false,
                )
            }
        }
        if (openCodeSelected) {
            ProviderConnectionStep(
                settingsState = settingsState,
                onOpenProviderAuth = onOpenProviderAuth,
                onDisconnectProvider = onDisconnectProvider,
            )
        }
        if (antigravitySelected) {
            SetupPanel {
                Text(stringResource(R.string.agent_antigravity_name), fontWeight = FontWeight.SemiBold)
                AntigravitySignInCard(
                    auth = antigravity.auth,
                    onSignIn = onBeginAntigravitySignIn,
                    onSubmitCode = onSubmitAntigravitySignInCode,
                    onCancel = onCancelAntigravitySignIn,
                    onSignOut = onSignOutAntigravity,
                    onOpenUrl = onOpenUrl,
                )
            }
        }
    }
}

@Composable
private fun AntigravitySignInCard(
    auth: AntigravityAuthCoordinator.State,
    onSignIn: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (auth) {
            AntigravityAuthCoordinator.State.Idle -> {
                Text(stringResource(R.string.antigravity_status_signed_out), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.antigravity_sign_in_button)) }
            }
            AntigravityAuthCoordinator.State.Starting -> {
                Text(stringResource(R.string.antigravity_auth_starting), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text(stringResource(R.string.claude_auth_cancel)) }
            }
            is AntigravityAuthCoordinator.State.AwaitingBrowser -> {
                Text(stringResource(R.string.antigravity_auth_instructions), style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { onOpenUrl(auth.url) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.claude_auth_open_browser)) }
                OutlinedTextField(value = code, onValueChange = {
                    code = it
                }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.claude_auth_code_hint)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSubmitCode(code)
                        },
                        enabled = code.isNotBlank(),
                        modifier =
                            Modifier.weight(
                                1f,
                            ),
                    ) { Text(stringResource(R.string.claude_auth_submit_code)) }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.claude_auth_cancel)) }
                }
            }
            AntigravityAuthCoordinator.State.Verifying -> {
                Text(stringResource(R.string.antigravity_auth_verifying), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is AntigravityAuthCoordinator.State.SignedIn -> {
                Text(stringResource(R.string.antigravity_signed_in), fontWeight = FontWeight.Medium)
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.claude_sign_out_button)) }
            }
            is AntigravityAuthCoordinator.State.Failed -> {
                Text(auth.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (auth.transcript.isNotBlank()) Text(auth.transcript, style = MaterialTheme.typography.labelSmall)
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.antigravity_sign_in_button)) }
            }
        }
    }
}

@Composable
private fun ProviderConnectionStep(
    settingsState: SettingsUiState,
    onOpenProviderAuth: (String) -> Unit,
    onDisconnectProvider: (String) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_provider),
            description = stringResource(R.string.setup_provider_optional_description),
        )

        if (settingsState.availableProviders.isEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.setup_provider_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.setup_provider_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
            )

            val filtered =
                settingsState.availableProviders
                    .sortedBy { it.name.lowercase() }
                    .filter { provider ->
                        searchQuery.isBlank() ||
                            provider.name.contains(searchQuery, ignoreCase = true) ||
                            provider.id.contains(searchQuery, ignoreCase = true)
                    }

            if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.setup_provider_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                filtered.forEach { provider ->
                    val methods = settingsState.providerAuthMethods[provider.id].orEmpty()
                    val connected = provider.id in settingsState.connectedProviderIds

                    ProviderConnectionRow(
                        providerName = provider.name,
                        methodSummary =
                            if (methods.isNotEmpty()) {
                                methods.joinToString(" · ") { it.label }
                            } else {
                                stringResource(R.string.setup_provider_api_key_only)
                            },
                        connected = connected,
                        onConnect = { onOpenProviderAuth(provider.id) },
                        onDisconnect = { onDisconnectProvider(provider.id) },
                    )
                }
            }
        }

        settingsState.providerAuthNotice?.let {
            Text(
                text = stringResource(R.string.provider_connected_success),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        settingsState.oauthMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ProviderConnectionRow(
    providerName: String,
    methodSummary: String,
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (connected) {
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = stringResource(R.string.provider_connected),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Text(
                text = methodSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onConnect, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.provider_change_connection))
                    }
                    TextButton(onClick = onDisconnect) {
                        Text(stringResource(R.string.provider_disconnect))
                    }
                }
            } else {
                OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.provider_connect))
                }
            }
        }
    }
}

@Composable
private fun SetupBottomBar(
    currentStep: Int,
    primaryAction: SetupPrimaryAction?,
    onSkip: (() -> Unit)?,
    onBackStep: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (currentStep > 1) {
                OutlinedButton(
                    onClick = onBackStep,
                    modifier = Modifier.width(96.dp),
                ) {
                    Text(stringResource(R.string.setup_back_action))
                }
            }
            if (onSkip != null) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.setup_skip_action))
                }
            }
            if (primaryAction != null) {
                Button(
                    onClick = primaryAction.onClick,
                    enabled = primaryAction.enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(primaryAction.label, textAlign = TextAlign.Center)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AndroidSetupScreenPreview() {
    OpenCodeAndroidTheme {
        AndroidSetupScreen(
            runtimeStatus = LocalRuntimeStatus.Installing(0.68f, "Downloading runtime"),
            claude = ClaudeCodeUiState(),
            onStartSetup = {},
            onBeginClaudeSignIn = {},
            onSubmitClaudeSignInCode = {},
            onCancelClaudeSignIn = {},
            onSignOutClaude = {},
            onOpenUrl = {},
            onSelectClaudePermissionMode = {},
            settingsState = SettingsUiState(),
            onOpenProviderAuth = {},
            onSelectProviderAuthMethod = {},
            onProviderAuthInput = { _, _ -> },
            onProviderApiKey = {},
            onSubmitProviderAuth = {},
            onCompleteProviderOAuth = {},
            onDisconnectProvider = {},
            onDismissProviderAuth = {},
            onRefreshProviderAuth = {},
            onRefreshCatalog = {},
            onBack = {},
            onFinish = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AndroidSetupProviderStepPreview() {
    OpenCodeAndroidTheme {
        AndroidSetupScreen(
            runtimeStatus = LocalRuntimeStatus.Ready("1.0.0", 4097),
            claude = ClaudeCodeUiState(installed = true, version = "2.1.212"),
            onStartSetup = {},
            onBeginClaudeSignIn = {},
            onSubmitClaudeSignInCode = {},
            onCancelClaudeSignIn = {},
            onSignOutClaude = {},
            onOpenUrl = {},
            onSelectClaudePermissionMode = {},
            settingsState =
                SettingsUiState(
                    availableProviders =
                        listOf(
                            OpenCodeProvider(id = "openai", name = "OpenAI"),
                            OpenCodeProvider(id = "anthropic", name = "Anthropic"),
                            OpenCodeProvider(id = "ollama", name = "Ollama"),
                        ),
                    providerAuthMethods =
                        mapOf(
                            "openai" to
                                listOf(
                                    ProviderAuthMethod(type = "oauth", label = "ChatGPT Plus/Pro"),
                                    ProviderAuthMethod(type = "api", label = "API key"),
                                ),
                            "anthropic" to
                                listOf(
                                    ProviderAuthMethod(type = "api", label = "API key"),
                                ),
                            "ollama" to
                                listOf(
                                    ProviderAuthMethod(type = "api", label = "No key needed"),
                                ),
                        ),
                    connectedProviderIds = setOf("ollama"),
                ),
            onOpenProviderAuth = {},
            onSelectProviderAuthMethod = {},
            onProviderAuthInput = { _, _ -> },
            onProviderApiKey = {},
            onSubmitProviderAuth = {},
            onCompleteProviderOAuth = {},
            onDisconnectProvider = {},
            onDismissProviderAuth = {},
            onRefreshProviderAuth = {},
            onRefreshCatalog = {},
            onBack = {},
            onFinish = {},
        )
    }
}
