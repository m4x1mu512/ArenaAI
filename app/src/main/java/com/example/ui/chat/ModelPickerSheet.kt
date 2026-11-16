package com.example.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.AiModel

/**
 * Searchable Material 3 ModalBottomSheet for picking a model from `/api/v1/models`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<AiModel>,
    selectedModelId: String?,
    isLoading: Boolean,
    onSelectModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    val filteredModels = remember(models, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            models
        } else {
            models.filter {
                it.id.contains(q, ignoreCase = true) ||
                    it.displayName.contains(q, ignoreCase = true) ||
                    (it.ownedBy?.contains(q, ignoreCase = true) == true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("model_picker_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.models_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(
                    onClick = onRefreshModels,
                    enabled = !isLoading,
                    modifier = Modifier.testTag("models_refresh_button"),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.models_refresh_cd),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.models_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("models_search_input"),
            )

            if (filteredModels.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.models_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredModels, key = { it.id }) { model ->
                        val isSelected = model.id == selectedModelId
                        Surface(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    onSelectModel(model.id)
                                    onDismiss()
                                }
                                .testTag("model_item_${model.id}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Memory,
                                    contentDescription = null,
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (model.displayName != model.id || model.ownedBy != null || model.contextLength != null) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val details = buildList {
                                            if (model.displayName != model.id) add(model.id)
                                            model.ownedBy?.let { add(it) }
                                            model.contextLength?.let {
                                                add(stringResource(R.string.models_context_label, it.toString()))
                                            }
                                        }.joinToString(" • ")
                                        Text(
                                            text = details,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
