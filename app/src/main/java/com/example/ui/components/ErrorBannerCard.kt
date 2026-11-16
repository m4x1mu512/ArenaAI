package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.AppError
import com.example.ui.common.localizedMessage

/**
 * Material 3 error card displaying a clear localized message and an expandable
 * technical details block (always redacted of secrets).
 */
@Composable
fun ErrorBannerCard(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("error_banner_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = stringResource(R.string.error_title),
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.error_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error.localizedMessage(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("error_banner_message"),
                    )
                }
            }

            if (error.technicalReason.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("error_toggle_technical_button"),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.error_hide_technical)
                        } else {
                            stringResource(R.string.error_show_technical)
                        },
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("error_technical_details"),
                    ) {
                        Text(
                            text = error.technicalReason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            if (onRetry != null || onOpenSettings != null || onDismiss != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (onDismiss != null) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("error_dismiss_button"),
                        ) {
                            Text(stringResource(R.string.chat_dismiss))
                        }
                    }
                    if (onOpenSettings != null) {
                        TextButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("error_settings_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.chat_open_settings))
                        }
                    }
                    if (onRetry != null) {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.testTag("error_retry_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.chat_retry))
                        }
                    }
                }
            }
        }
    }
}
