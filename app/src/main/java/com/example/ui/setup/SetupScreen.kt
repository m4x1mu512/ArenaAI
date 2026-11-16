package com.example.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.ErrorBannerCard

/**
 * First-launch onboarding route connecting to [SetupViewModel].
 */
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SetupScreenContent(
        uiState = uiState,
        onApiKeyChange = viewModel::onApiKeyChange,
        onBaseUrlChange = viewModel::onBaseUrlChange,
        onToggleKeyVisibility = viewModel::toggleKeyVisibility,
        onResetBaseUrl = viewModel::resetBaseUrl,
        onTestConnection = viewModel::testConnection,
        onSaveAndContinue = { viewModel.saveAndContinue(onSetupComplete) },
    )
}

/**
 * Stateless presentation of the first-launch Setup screen, adaptive for phones and foldables.
 */
@Composable
fun SetupScreenContent(
    uiState: SetupUiState,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onResetBaseUrl: () -> Unit,
    onTestConnection: () -> Unit,
    onSaveAndContinue: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    val keyHelpUrl = stringResource(R.string.setup_key_hint_url)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
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
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Hero banner card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp)
                        .clip(MaterialTheme.shapes.large),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_coarena_hero),
                        contentDescription = stringResource(R.string.setup_hero_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    ),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setup_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.setup_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // API Key input
                OutlinedTextField(
                    value = uiState.apiKeyInput,
                    onValueChange = onApiKeyChange,
                    label = { Text(stringResource(R.string.setup_api_key_label)) },
                    placeholder = { Text(stringResource(R.string.setup_api_key_placeholder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (uiState.apiKeyInput.isNotEmpty()) {
                                IconButton(
                                    onClick = { onApiKeyChange("") },
                                    modifier = Modifier.testTag("setup_clear_key_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.setup_api_key_clear_cd),
                                    )
                                }
                            }
                            IconButton(
                                onClick = onToggleKeyVisibility,
                                modifier = Modifier.testTag("setup_toggle_key_visibility_button"),
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
                        .testTag("setup_api_key_input"),
                )

                // Base URL input
                OutlinedTextField(
                    value = uiState.baseUrlInput,
                    onValueChange = onBaseUrlChange,
                    label = { Text(stringResource(R.string.setup_base_url_label)) },
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.setup_base_url_helper),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = onResetBaseUrl,
                                modifier = Modifier.testTag("setup_reset_url_button"),
                            ) {
                                Text(stringResource(R.string.setup_reset_url))
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Link,
                            contentDescription = null,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setup_base_url_input"),
                )

                // Key help & privacy card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setup_key_hint_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.setup_key_hint_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    runCatching { uriHandler.openUri(keyHelpUrl) }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                                .testTag("setup_help_url_link"),
                        ) {
                            Text(
                                text = keyHelpUrl,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    textDecoration = TextDecoration.Underline,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.setup_key_privacy_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Inline validation message
                if (uiState.validationErrorResId != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_validation_error"),
                    ) {
                        Text(
                            text = stringResource(uiState.validationErrorResId),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                // Success badge for connection test
                if (uiState.hasSuccessfulTest) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_connection_success_banner"),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            val count = uiState.connectionSuccessModelCount
                            Text(
                                text = if (count != null) {
                                    stringResource(R.string.setup_connection_success, count)
                                } else {
                                    stringResource(R.string.setup_connection_success_generic)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }

                // Network/API error card with expandable technical details
                if (uiState.requestError != null) {
                    ErrorBannerCard(
                        error = uiState.requestError,
                        onRetry = onTestConnection,
                    )
                }

                // Action buttons
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            onTestConnection()
                        },
                        enabled = !uiState.isTesting && !uiState.isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("setup_test_connection_button"),
                    ) {
                        if (uiState.isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.setup_testing_connection))
                        } else {
                            Icon(
                                imageVector = Icons.Filled.NetworkCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.setup_test_connection))
                        }
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onSaveAndContinue()
                        },
                        enabled = !uiState.isTesting && !uiState.isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("setup_save_continue_button"),
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.setup_saving))
                        } else {
                            Text(stringResource(R.string.setup_save_continue))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
