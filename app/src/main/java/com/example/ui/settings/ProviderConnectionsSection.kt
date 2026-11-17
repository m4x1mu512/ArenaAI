package com.example.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.ProviderCatalog

@Composable
fun ProviderConnectionsSection(
    viewModel: ProviderConnectionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var presetMenuExpanded by remember { mutableStateOf(false) }
    val selectedPreset = ProviderCatalog.find(state.selectedPresetId)
        ?: ProviderCatalog.customPreset

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Провайдеры и API-ключи", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ключи хранятся отдельно в зашифрованном хранилище Android Keystore. В настройках сохраняются только адреса и параметры подключений.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.connections.forEach { connection ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(connection.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(connection.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.activeConnectionId == connection.id) {
                            Text("Активно", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.activeConnectionId != connection.id) {
                            OutlinedButton(onClick = { viewModel.setActive(connection.id) }) {
                                Text("Использовать")
                            }
                        }
                        TextButton(onClick = { viewModel.delete(connection.id) }) {
                            Text("Удалить", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Добавить подключение", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(
                    onClick = { presetMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(selectedPreset.displayName)
                }
                DropdownMenu(
                    expanded = presetMenuExpanded,
                    onDismissRequest = { presetMenuExpanded = false },
                ) {
                    ProviderCatalog.presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.displayName) },
                            onClick = {
                                presetMenuExpanded = false
                                viewModel.selectPreset(preset.id)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(ProviderCatalog.customPreset.displayName) },
                        onClick = {
                            presetMenuExpanded = false
                            viewModel.selectPreset(ProviderCatalog.customPreset.id)
                        },
                    )
                }

                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::setDisplayName,
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::setBaseUrl,
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::setApiKey,
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.testModelCount != null) {
                    Text(
                        "Подключение проверено. Найдено моделей: ${state.testModelCount}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        enabled = !state.isTesting && !state.isSaving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.isTesting) "Проверка…" else "Проверить")
                    }
                    Button(
                        onClick = viewModel::saveConnection,
                        enabled = !state.isSaving && !state.isTesting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.isSaving) "Сохранение…" else "Сохранить")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}
