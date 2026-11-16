package com.example.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.AppError
import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import com.example.ui.components.ErrorBannerCard
import kotlinx.coroutines.launch

/**
 * Main chat route connected to [ChatViewModel].
 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val systemPrompt = stringResource(R.string.chat_default_system_prompt)

    ChatScreenContent(
        uiState = uiState,
        onInputTextChange = viewModel::onInputTextChange,
        onSendMessage = { override -> viewModel.sendMessage(systemPrompt, override) },
        onStopGeneration = viewModel::stopGeneration,
        onSelectModel = viewModel::selectModel,
        onRefreshModels = { viewModel.loadModels() },
        onNewChat = viewModel::startNewChat,
        onOpenSettings = onOpenSettings,
        onRetryLastMessage = { viewModel.retryLastMessage(systemPrompt) },
        onDismissError = viewModel::dismissError,
    )
}

/**
 * Stateless presentation of the Chat screen with adaptive column sizing,
 * streaming bubbles, model picker sheet, error banners, and IME-aware composer.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreenContent(
    uiState: ChatUiState,
    onInputTextChange: (String) -> Unit,
    onSendMessage: (String?) -> Unit,
    onStopGeneration: () -> Unit,
    onSelectModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryLastMessage: () -> Unit,
    onDismissError: () -> Unit,
) {
    var showModelSheet by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val copiedTextMsg = stringResource(R.string.chat_copied_snackbar)
    val selectModelFirstMsg = stringResource(R.string.chat_select_model_first)

    // Scroll smoothly to latest message/streaming chunk
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content?.length) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { showModelSheet = true }
                            .testTag("model_selector_button"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = stringResource(R.string.chat_model_selector_cd),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val label = when {
                                uiState.isLoadingModels && uiState.models.isEmpty() ->
                                    stringResource(R.string.chat_loading_models)
                                uiState.selectedModel != null -> uiState.selectedModel!!.displayName
                                uiState.selectedModelId != null -> uiState.selectedModelId
                                else -> stringResource(R.string.chat_no_model_selected)
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNewChat,
                        modifier = Modifier.testTag("new_chat_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddComment,
                            contentDescription = stringResource(R.string.chat_new_chat_cd),
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.chat_settings_cd),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 820.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.inputText,
                            onValueChange = onInputTextChange,
                            placeholder = {
                                Text(stringResource(R.string.chat_input_placeholder))
                            },
                            maxLines = 5,
                            shape = MaterialTheme.shapes.large,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_message_input"),
                        )

                        if (uiState.isSending) {
                            FilledIconButton(
                                onClick = onStopGeneration,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("stop_generation_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = stringResource(R.string.chat_stop_cd),
                                )
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    if (uiState.selectedModelId.isNullOrBlank()) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(selectModelFirstMsg)
                                        }
                                        showModelSheet = true
                                    } else {
                                        onSendMessage(null)
                                    }
                                },
                                enabled = uiState.inputText.isNotBlank(),
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("send_message_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.chat_send_cd),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val isWide = maxWidth >= 700.dp
            val contentWidth = if (isWide) 820.dp else maxWidth

            Column(
                modifier = Modifier
                    .widthIn(max = contentWidth)
                    .fillMaxSize(),
            ) {
                // Model loading error if list failed to fetch
                if (uiState.modelsError != null && uiState.models.isEmpty()) {
                    ErrorBannerCard(
                        error = uiState.modelsError,
                        onRetry = onRefreshModels,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // Active chat error banner
                if (uiState.chatError != null) {
                    val showSettingsAction = uiState.chatError is AppError.Unauthorized ||
                        uiState.chatError is AppError.Forbidden ||
                        uiState.chatError is AppError.MissingApiKey
                    ErrorBannerCard(
                        error = uiState.chatError,
                        onRetry = if (uiState.messages.any { it.role == MessageRole.USER }) {
                            onRetryLastMessage
                        } else {
                            null
                        },
                        onOpenSettings = if (showSettingsAction) onOpenSettings else null,
                        onDismiss = onDismissError,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (uiState.messages.isEmpty()) {
                    EmptyChatState(
                        selectedModelName = uiState.selectedModel?.displayName ?: uiState.selectedModelId,
                        onSelectPrompt = { prompt ->
                            if (uiState.selectedModelId.isNullOrBlank()) {
                                scope.launch { snackbarHostState.showSnackbar(selectModelFirstMsg) }
                                showModelSheet = true
                            } else {
                                onSendMessage(prompt)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("chat_message_list"),
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            ChatMessageBubble(
                                message = message,
                                onCopy = { text ->
                                    clipboardManager.setText(AnnotatedString(text))
                                    scope.launch {
                                        snackbarHostState.showSnackbar(copiedTextMsg)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        ModelPickerSheet(
            models = uiState.models,
            selectedModelId = uiState.selectedModelId,
            isLoading = uiState.isLoadingModels,
            onSelectModel = onSelectModel,
            onRefreshModels = onRefreshModels,
            onDismiss = { showModelSheet = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChatState(
    selectedModelName: String?,
    onSelectPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prompt1 = stringResource(R.string.chat_prompt_chip_1)
    val prompt2 = stringResource(R.string.chat_prompt_chip_2)
    val prompt3 = stringResource(R.string.chat_prompt_chip_3)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_coarena_hero),
                        contentDescription = null,
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
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                ),
                            ),
                    )
                }

                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!selectedModelName.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = selectedModelName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.chat_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(R.string.chat_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        listOf(prompt1, prompt2, prompt3).forEachIndexed { idx, prompt ->
                            AssistChip(
                                onClick = { onSelectPrompt(prompt) },
                                label = {
                                    Text(
                                        text = prompt,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier.testTag("starter_prompt_chip_$idx"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    onCopy: (String) -> Unit,
) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (isUser) "chat_bubble_user" else "chat_bubble_assistant"),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val bubbleShape = if (isUser) {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        } else {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
        }

        val containerColor = if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

        val contentColor = if (isUser) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(bubbleShape)
                .background(containerColor)
                .then(
                    if (!isUser) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape = bubbleShape,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (isUser) {
                        stringResource(R.string.chat_role_you)
                    } else {
                        message.modelId ?: stringResource(R.string.chat_role_assistant)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (message.isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(12.dp)
                            .testTag("streaming_indicator"),
                        strokeWidth = 2.dp,
                    )
                }
                if (!isUser && message.content.isNotBlank() && !message.isStreaming) {
                    IconButton(
                        onClick = { onCopy(message.content) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.chat_copy_cd),
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            SelectionContainer {
                Text(
                    text = if (message.content.isEmpty() && message.isStreaming) {
                        stringResource(R.string.chat_streaming_indicator)
                    } else {
                        message.content
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                )
            }

            if (message.totalTokens != null && !message.isStreaming) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_request_tokens, message.totalTokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f),
                )
            }
        }
    }
}
