package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AiModel
import com.example.domain.model.AppError
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.MessageRole
import com.example.domain.repository.CoarenaRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.common.toAppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * UI state for the main Coarena Chat screen.
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val models: List<AiModel> = emptyList(),
    val selectedModelId: String? = null,
    val isLoadingModels: Boolean = false,
    val modelsError: AppError? = null,
    val isSending: Boolean = false,
    val chatError: AppError? = null,
) {
    val selectedModel: AiModel?
        get() = models.firstOrNull { it.id == selectedModelId }
}

/**
 * ViewModel holding active conversation messages, model selection, and SSE streaming state.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val coarenaRepository: CoarenaRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var lastSubmittedPrompt: String? = null

    init {
        observeSettingsAndLoadModels()
    }

    private fun observeSettingsAndLoadModels() {
        viewModelScope.launch {
            settingsRepository.settingsFlow
                .map { Triple(it.hasApiKey, it.baseUrl, it.defaultModelId) }
                .distinctUntilChanged()
                .collect { (hasKey, _, defaultModelId) ->
                    if (defaultModelId != null && _uiState.value.selectedModelId == null) {
                        _uiState.update { it.copy(selectedModelId = defaultModelId) }
                    }
                    if (hasKey) {
                        loadModels(preferredModelId = defaultModelId)
                    }
                }
        }
    }

    /** Loads or refreshes available models from `GET /models`. */
    fun loadModels(preferredModelId: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true, modelsError = null) }
            coarenaRepository.fetchModels().fold(
                onSuccess = { models ->
                    _uiState.update { state ->
                        val candidate = preferredModelId ?: state.selectedModelId
                        val resolvedSelection = when {
                            candidate != null && models.any { it.id == candidate } -> candidate
                            models.isNotEmpty() -> models.first().id
                            else -> candidate
                        }
                        state.copy(
                            models = models,
                            selectedModelId = resolvedSelection,
                            isLoadingModels = false,
                            modelsError = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoadingModels = false,
                            modelsError = throwable.toAppError(),
                        )
                    }
                },
            )
        }
    }

    /** Updates the text inside the message composer. */
    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /** Selects an AI model for subsequent messages and remembers it as default. */
    fun selectModel(modelId: String) {
        _uiState.update { it.copy(selectedModelId = modelId, chatError = null) }
        viewModelScope.launch {
            settingsRepository.saveDefaultModel(modelId)
        }
    }

    /** Clears current messages and cancels any active streaming generation. */
    fun startNewChat() {
        streamJob?.cancel()
        streamJob = null
        lastSubmittedPrompt = null
        _uiState.update {
            it.copy(
                messages = emptyList(),
                isSending = false,
                chatError = null,
            )
        }
    }

    /** Stops the currently streaming assistant response while keeping received tokens. */
    fun stopGeneration() {
        streamJob?.cancel()
        streamJob = null
        _uiState.update { state ->
            state.copy(
                isSending = false,
                messages = state.messages.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                },
            )
        }
    }

    /** Dismisses the currently displayed chat error banner. */
    fun dismissError() {
        _uiState.update { it.copy(chatError = null) }
    }

    /** Sends [overrideText] or the current [ChatUiState.inputText] to the selected model. */
    fun sendMessage(
        systemPrompt: String,
        overrideText: String? = null,
    ) {
        val state = _uiState.value
        if (state.isSending) return

        val prompt = (overrideText ?: state.inputText).trim()
        if (prompt.isEmpty()) return

        val modelId = state.selectedModelId
        if (modelId.isNullOrBlank()) {
            _uiState.update {
                it.copy(chatError = AppError.Unknown("No model selected"))
            }
            return
        }

        lastSubmittedPrompt = prompt
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = prompt,
        )
        val assistantPlaceholderId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantPlaceholderId,
            role = MessageRole.ASSISTANT,
            content = "",
            modelId = modelId,
            isStreaming = true,
        )

        val requestHistory = state.messages.filter { !it.isError } + userMessage

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage + assistantPlaceholder,
                inputText = if (overrideText == null) "" else it.inputText,
                isSending = true,
                chatError = null,
            )
        }

        streamJob = viewModelScope.launch {
            try {
                coarenaRepository.sendChatMessage(
                    modelId = modelId,
                    history = requestHistory,
                    systemPrompt = systemPrompt,
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            _uiState.update { current ->
                                current.copy(
                                    messages = current.messages.map { msg ->
                                        if (msg.id == assistantPlaceholderId) {
                                            msg.copy(content = msg.content + event.textChunk)
                                        } else {
                                            msg
                                        }
                                    },
                                )
                            }
                        }
                        is ChatStreamEvent.Completed -> {
                            _uiState.update { current ->
                                current.copy(
                                    isSending = false,
                                    messages = current.messages.map { msg ->
                                        if (msg.id == assistantPlaceholderId) {
                                            msg.copy(
                                                content = event.fullText ?: msg.content,
                                                isStreaming = false,
                                                totalTokens = event.totalTokens ?: msg.totalTokens,
                                            )
                                        } else {
                                            msg
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                // Ensure streaming flag is cleared when flow completes cleanly
                _uiState.update { current ->
                    current.copy(
                        isSending = false,
                        messages = current.messages.map { msg ->
                            if (msg.id == assistantPlaceholderId) {
                                msg.copy(isStreaming = false)
                            } else {
                                msg
                            }
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val appError = e.toAppError()
                _uiState.update { current ->
                    // Remove empty placeholder if no tokens arrived
                    val cleaned = current.messages.filterNot {
                        it.id == assistantPlaceholderId && it.content.isBlank()
                    }.map { msg ->
                        if (msg.id == assistantPlaceholderId) {
                            msg.copy(isStreaming = false, isError = true)
                        } else {
                            msg
                        }
                    }
                    current.copy(
                        messages = cleaned,
                        isSending = false,
                        chatError = appError,
                    )
                }
            }
        }
    }

    /** Retries the last user prompt if the previous request failed. */
    fun retryLastMessage(systemPrompt: String) {
        val state = _uiState.value
        val modelId = state.selectedModelId ?: return
        val lastUserMsg = state.messages.lastOrNull { it.role == MessageRole.USER } ?: return
        val historyWithoutTrailingError = state.messages.dropLastWhile { it.isError || it.isStreaming }
        val assistantPlaceholderId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantPlaceholderId,
            role = MessageRole.ASSISTANT,
            content = "",
            modelId = modelId,
            isStreaming = true,
        )

        _uiState.update {
            it.copy(
                messages = historyWithoutTrailingError + assistantPlaceholder,
                isSending = true,
                chatError = null,
            )
        }

        val requestHistory = historyWithoutTrailingError.filter { !it.isError }
        if (requestHistory.none { it.id == lastUserMsg.id }) return

        streamJob = viewModelScope.launch {
            try {
                coarenaRepository.sendChatMessage(
                    modelId = modelId,
                    history = requestHistory,
                    systemPrompt = systemPrompt,
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            _uiState.update { current ->
                                current.copy(
                                    messages = current.messages.map { msg ->
                                        if (msg.id == assistantPlaceholderId) {
                                            msg.copy(content = msg.content + event.textChunk)
                                        } else {
                                            msg
                                        }
                                    },
                                )
                            }
                        }
                        is ChatStreamEvent.Completed -> {
                            _uiState.update { current ->
                                current.copy(
                                    isSending = false,
                                    messages = current.messages.map { msg ->
                                        if (msg.id == assistantPlaceholderId) {
                                            msg.copy(
                                                content = event.fullText ?: msg.content,
                                                isStreaming = false,
                                                totalTokens = event.totalTokens ?: msg.totalTokens,
                                            )
                                        } else {
                                            msg
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                _uiState.update { current ->
                    current.copy(
                        isSending = false,
                        messages = current.messages.map { msg ->
                            if (msg.id == assistantPlaceholderId) msg.copy(isStreaming = false) else msg
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val appError = e.toAppError()
                _uiState.update { current ->
                    val cleaned = current.messages.filterNot {
                        it.id == assistantPlaceholderId && it.content.isBlank()
                    }.map { msg ->
                        if (msg.id == assistantPlaceholderId) {
                            msg.copy(isStreaming = false, isError = true)
                        } else {
                            msg
                        }
                    }
                    current.copy(
                        messages = cleaned,
                        isSending = false,
                        chatError = appError,
                    )
                }
            }
        }
    }
}
