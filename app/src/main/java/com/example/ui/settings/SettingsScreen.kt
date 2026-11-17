package com.example.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.ApiWireFormat
import com.example.domain.model.ThemeMode
import com.example.ui.components.ErrorBannerCard

/**
 * Settings, account credits/limits, request history, and key deletion route.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSetup: () -> Unit,
    onProviderSaved: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreenContent(
        uiState = uiState,
        onBack = onBack,
        onApiKeyChange = viewModel::onApiKeyChange,
        onToggleKeyVisibility = viewModel::toggleKeyVisibility,
        onBaseUrlChange = viewModel::onBaseUrlChange,
        onResetBaseUrl = viewModel::resetBaseUrl,
        onSelectDefaultModel = viewModel::selectDefaultModel,
        onSelectThemeMode = viewModel::selectThemeMode,
        onToggleStreaming = viewModel::setUseStreaming,
        onSelectWireFormat = viewModel::setWireFormat,
        onTestConnection = viewModel::testConnection,
        onSaveChanges = viewModel::saveChanges,
        onRefreshAccountData = viewModel::refreshAccountData,
        onDeleteKeyConfirmed = { viewModel.deleteSavedKey(onNavigateToSetup) },
        onClearAllConfirmed = { viewModel.clearAllLocalData(onNavigateToSetup) },
        onConsumeSavedConfirmation = viewModel::consumeSavedConfirmation,
        onProviderSaved = onProviderSaved,
    )
}

/**
 * Stateless Settings UI displaying connection settings, appearance, credits, limits,
 * request history list, and destructive actions with confirmation dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onResetBaseUrl: () -> Unit,
    onSelectDefaultModel: (String) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onToggleStreaming: (Boolean) -> Unit,
    onSelectWireFormat: (ApiWireFormat) -> Unit,
    onTestConnection: () -> Unit,
    onSaveChanges: () -> Unit,
    onRefreshAccountData: () -> Unit,
    onDeleteKeyConfirmed: () -> Unit,
    onClearAllConfirmed: () -> Unit,
    onConsumeSavedConfirmation: () -> Unit,
    onProviderSaved: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMsg = stringResource(R.string.settings_saved_snackbar)

    var showDeleteKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showClearAllDialog by rememberSaveable { mutableStateOf(false) }
    var showModelMenu by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.showSavedConfirmation) {
        if (uiState.showSavedConfirmation) {
            snackbarHostState.showSnackbar(savedMsg)
            onConsumeSavedConfirmation()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_cd),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefreshAccountData,
                        modifier = Modifier.testTag("settings_refresh_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.settings_refresh_account_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Section 1: Credentials & Connection
                SettingsSectionHeader(stringResource(R.string.settings_section_connection))
                ProviderConnectionsSection(onConnectionSaved = onProviderSaved)

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.apiKeyInput,
                            onValueChange = onApiKeyChange,
                            label = { Text(stringResource(R.string.setup_api_key_label)) },
                            placeholder = { Text(stringResource(R.string.setup_api_key_placeholder)) },
                            supportingText = {
                                Text(
                                    text = if (uiState.hasSavedApiKey) {
                                        stringResource(R.string.settings_api_key_saved_hint)
                                    } else {
                                        stringResource(R.string.settings_api_key_missing_hint)
                                    },
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                            trailingIcon = {
                                IconButton(
                                    onClick = onToggleKeyVisibility,
                                    modifier = Modifier.testTag("settings_toggle_key_visibility"),
                                ) {
                                    Icon(
                                        imageVector = if (uiState.isKeyVisible) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = if (uiState.isKeyVisible) {
                                            stringResource(R.string.setup_api_key_hide_cd)
                                        } else {
                                            stringResource(R.string.setup_api_key_show_cd)
                                        },
                                    )
                                }
                            },
                            visualTransformation = if (uiState.isKeyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_api_key_input"),
                        )

                        OutlinedTextField(
                            value = uiState.baseUrlInput,
                            onValueChange = onBaseUrlChange,
                            label = { Text(stringResource(R.string.setup_base_url_label)) },
                            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                            trailingIcon = {
                                TextButton(onClick = onResetBaseUrl) {
                                    Text(stringResource(R.string.setup_reset_url))
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_base_url_input"),
                        )

                        // Default model dropdown
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showModelMenu = true },
                                enabled = uiState.models.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("settings_default_model_button"),
                            ) {
                                Text(
                                    text = "${stringResource(R.string.settings_default_model_label)}: " +
                                        (uiState.defaultModelId ?: stringResource(R.string.settings_default_model_none)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(Icons.Filled.ExpandMore, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false },
                            ) {
                                uiState.models.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = model.displayName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                                if (model.displayName != model.id) {
                                                    Text(
                                                        text = model.id,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            onSelectDefaultModel(model.id)
                                            showModelMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        if (uiState.validationErrorResId != null) {
                            Text(
                                text = stringResource(uiState.validationErrorResId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        if (uiState.hasTestSuccess) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        text = uiState.testSuccessModelCount?.let {
                                            stringResource(R.string.setup_connection_success, it)
                                        } ?: stringResource(R.string.setup_connection_success_generic),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }

                        if (uiState.connectionError != null) {
                            ErrorBannerCard(
                                error = uiState.connectionError,
                                onRetry = onTestConnection,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = onTestConnection,
                                enabled = !uiState.isTestingConnection && !uiState.isSaving,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("settings_test_connection_button"),
                            ) {
                                if (uiState.isTestingConnection) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.NetworkCheck,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.setup_test_connection),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Button(
                                onClick = onSaveChanges,
                                enabled = !uiState.isTestingConnection && !uiState.isSaving,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("settings_save_button"),
                            ) {
                                if (uiState.isSaving) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Save,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.settings_save_credentials),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // API compatibility & last request diagnostics
                ApiCompatibilityAndDiagnosticsCard(
                    useStreaming = uiState.useStreaming,
                    wireFormat = uiState.wireFormat,
                    lastDiagnostic = uiState.lastDiagnostic,
                    onToggleStreaming = onToggleStreaming,
                    onSelectWireFormat = onSelectWireFormat,
                )

                // Section 2: Appearance
                SettingsSectionHeader(stringResource(R.string.settings_section_appearance))

                val themeOptions = listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                    ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_theme_selector"),
                ) {
                    themeOptions.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = uiState.themeMode == mode,
                            onClick = { onSelectThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = themeOptions.size,
                            ),
                        ) {
                            Text(label)
                        }
                    }
                }

                // Section 3: Credits & Limits
                SettingsSectionHeader(stringResource(R.string.settings_section_account))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_credits_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AccountBalanceWallet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.settings_credits_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        when {
                            uiState.isLoadingCredits -> CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                            uiState.creditsError != null -> ErrorBannerCard(
                                error = uiState.creditsError,
                                onRetry = onRefreshAccountData,
                            )
                            uiState.credits != null -> {
                                val credits = uiState.credits
                                val balanceDisplay = listOfNotNull(credits.balance, credits.currency)
                                    .joinToString(" ")
                                Text(
                                    text = balanceDisplay,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    credits.totalUsed?.let {
                                        Text(
                                            text = stringResource(R.string.settings_credits_used, it),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    credits.totalGranted?.let {
                                        Text(
                                            text = stringResource(R.string.settings_credits_granted, it),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            else -> Text(
                                text = stringResource(R.string.settings_credits_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_limits_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Text(
                                text = stringResource(R.string.settings_limits_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        when {
                            uiState.isLoadingLimits -> CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                            uiState.limitsError != null -> ErrorBannerCard(
                                error = uiState.limitsError,
                                onRetry = onRefreshAccountData,
                            )
                            uiState.limits != null -> {
                                val limits = uiState.limits
                                val rows = buildList {
                                    limits.requestsPerMinute?.let {
                                        add(stringResource(R.string.settings_limits_rpm) to it)
                                    }
                                    limits.tokensPerMinute?.let {
                                        add(stringResource(R.string.settings_limits_tpm) to it)
                                    }
                                    limits.dailyLimit?.let {
                                        add(stringResource(R.string.settings_limits_daily) to it)
                                    }
                                    limits.remainingRequests?.let {
                                        add(stringResource(R.string.settings_limits_remaining) to it)
                                    }
                                }.ifEmpty {
                                    limits.items.take(6).map { it.key to it.value }
                                }

                                if (rows.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.settings_limits_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    rows.forEach { (label, value) ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = value,
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        }
                                    }
                                }
                            }
                            else -> Text(
                                text = stringResource(R.string.settings_limits_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Section 4: Request History
                SettingsSectionHeader(stringResource(R.string.settings_section_requests))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_requests_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when {
                            uiState.isLoadingRequests -> CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                            uiState.requestsError != null -> ErrorBannerCard(
                                error = uiState.requestsError,
                                onRetry = onRefreshAccountData,
                            )
                            uiState.requests.isEmpty() -> Text(
                                text = stringResource(R.string.settings_requests_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> {
                                uiState.requests.take(25).forEachIndexed { idx, item ->
                                    if (idx > 0) HorizontalDivider()
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = item.model,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = MaterialTheme.shapes.small,
                                            ) {
                                                Text(
                                                    text = item.status,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = item.createdAt,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            item.totalTokens?.let { tokens ->
                                                Text(
                                                    text = stringResource(R.string.settings_request_tokens, tokens),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 5: Danger Zone
                SettingsSectionHeader(stringResource(R.string.settings_section_danger))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { showDeleteKeyDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("settings_delete_key_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_delete_key))
                    }

                    TextButton(
                        onClick = { showClearAllDialog = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_clear_all_button"),
                    ) {
                        Text(stringResource(R.string.settings_clear_all_data))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showDeleteKeyDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteKeyDialog = false },
            title = { Text(stringResource(R.string.settings_delete_key_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_key_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteKeyDialog = false
                        onDeleteKeyConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("confirm_delete_key_button"),
                ) {
                    Text(stringResource(R.string.action_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteKeyDialog = false },
                    modifier = Modifier.testTag("cancel_delete_key_button"),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(stringResource(R.string.settings_clear_all_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_all_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllDialog = false
                        onClearAllConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("confirm_clear_all_button"),
                ) {
                    Text(stringResource(R.string.action_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
