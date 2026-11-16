package com.example.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ApiWireFormat
import com.example.domain.model.RequestDiagnostic

/**
 * Compatibility controls (`/chat/completions` vs `/responses`, SSE streaming toggle)
 * and non-secret diagnostics for the latest inference request.
 */
@Composable
fun ApiCompatibilityAndDiagnosticsCard(
    useStreaming: Boolean,
    wireFormat: ApiWireFormat,
    lastDiagnostic: RequestDiagnostic?,
    onToggleStreaming: (Boolean) -> Unit,
    onSelectWireFormat: (ApiWireFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_streaming_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_streaming_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useStreaming,
                    onCheckedChange = onToggleStreaming,
                    modifier = Modifier.testTag("settings_streaming_switch"),
                )
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_wire_format_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_wire_format_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val formats = listOf(
                    ApiWireFormat.CHAT_COMPLETIONS to "/chat/completions",
                    ApiWireFormat.RESPONSES to "/responses",
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_wire_format_selector"),
                ) {
                    formats.forEachIndexed { index, (format, label) ->
                        SegmentedButton(
                            selected = wireFormat == format,
                            onClick = { onSelectWireFormat(format) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = formats.size,
                            ),
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }

            if (lastDiagnostic != null) {
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.settings_last_request_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                DiagnosticRow(
                    label = stringResource(R.string.settings_diag_endpoint),
                    value = lastDiagnostic.endpointUrl,
                )
                DiagnosticRow(
                    label = stringResource(R.string.settings_diag_sent_model),
                    value = lastDiagnostic.sentModelId,
                )
                DiagnosticRow(
                    label = stringResource(R.string.settings_diag_returned_model),
                    value = lastDiagnostic.responseModelId
                        ?: lastDiagnostic.headerModelId
                        ?: "—",
                )
                DiagnosticRow(
                    label = stringResource(R.string.settings_diag_http_status),
                    value = listOfNotNull(
                        lastDiagnostic.httpStatusCode?.toString(),
                        "stream=${lastDiagnostic.stream}",
                    ).joinToString(" • "),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
