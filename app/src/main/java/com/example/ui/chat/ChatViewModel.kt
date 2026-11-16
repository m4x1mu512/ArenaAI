package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AiModel
import com.example.domain.model.AppError
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatSession
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.MessageRole
import com.example.domain.repository.ChatHistoryRepository
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
 * UI state for the main Coarena Chat screen including persisted conversation history.
 */
data class ChatUiState(
    val currentSessionId: String = UUID.randomUUID().toString(),
    val messages: List<ChatMessage> = emptyList(),
    val sessions: List<ChatSession> = emptyList(),
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
 * ViewModel holding active conversation messages, local Room persistence, model selection,
 * and SSE streaming state.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val coarenaRepository: CoarenaRepository,
    private val settingsRepository: SettingsRepository,
    private val chatHistoryRepository: ChatHistoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null

    init {
        observeHistorySessions()
        restoreLatestSessionIfEmpty()
        observeSettingsAndLoadModels()
    }

    private fun observeHistorySessions() {
        viewModelScope.launch {
            chatHistoryRepository.observeSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
    }

    private fun restoreLatestSessionIfEmpty() {
        viewModelScope.launch {
            if (_uiState.value.messages.isNotEmpty()) return@launch
            val latest = chatHistoryRepository.getLatestSession() ?: return@launch
            val savedMessages = chatHistoryRepository.getMessages(latest.id)
            if (savedMessages.isNotEmpty() && _uiState.value.messages.isEmpty()) {
                _uiState.update { state ->
                    state.copy(
                        currentSessionId = latest.id,
                        messages = savedMessages,
                        selectedModelId = latest.modelId ?: state.selectedModelId,
                    )
                }
            }
        }
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
                        val candidate = state.selectedModelId ?: preferredModelId
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

    /** Starts a fresh conversation thread without deleting previously saved sessions. */
    fun startNewChat() {
        streamJob?.cancel()
        streamJob = null
        _uiState.update {
            it.copy(
                currentSessionId = UUID.randomUUID().toString(),
                messages = emptyList(),
                isSending = false,
                chatError = null,
            )
        }
    }

    /** Switches the active chat thread to a previously saved [sessionId]. */
    fun selectSession(sessionId: String) {
        streamJob?.cancel()
        streamJob = null
        viewModelScope.launch {
            val savedMessages = chatHistoryRepository.getMessages(sessionId)
            val session = _uiState.value.sessions.firstOrNull { it.id == sessionId }
            _uiState.update { state ->
                val sessionModel = session?.modelId
                val updatedModel = if (sessionModel != null &&
                    (state.models.isEmpty() || state.models.any { it.id == sessionModel })
                ) {
                    sessionModel
                } else {
                    state.selectedModelId
                }
                state.copy(
                    currentSessionId = sessionId,
                    messages = savedMessages,
                    selectedModelId = updatedModel,
                    isSending = false,
                    chatError = null,
                )
            }
        }
    }

    /** Deletes a saved conversation by [sessionId]. If active, starts a fresh chat. */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatHistoryRepository.deleteSession(sessionId)
            if (_uiState.value.currentSessionId == sessionId) {
                startNewChat()
            }
        }
    }

    /** Clears all locally saved conversations and starts a fresh empty chat. */
    fun clearAllHistory() {
        viewModelScope.launch {
            chatHistoryRepository.clearAllHistory()
            startNewChat()
        }
    }

    /** Stops the currently streaming assistant response and persists received tokens. */
    fun stopGeneration() {
        streamJob?.cancel()
        streamJob = null
        val snapshot = _uiState.value
        val sessionTitle = deriveSessionTitle(snapshot.messages)
        val streamingMessages = snapshot.messages.filter { it.isStreaming }

        _uiState.update { state ->
            state.copy(
                isSending = false,
                messages = state.messages.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                },
            )
        }

        viewModelScope.launch {
            streamingMessages.forEach { msg ->
                if (msg.content.isNotBlank()) {
                    chatHistoryRepository.saveMessage(
                        sessionId = snapshot.currentSessionId,
                        sessionTitle = sessionTitle,
                        modelId = snapshot.selectedModelId,
                        message = msg.copy(isStreaming = false),
                    )
                }
            }
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

        val sessionId = state.currentSessionId
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = prompt,
            timestampMillis = now,
        )
        val assistantPlaceholderId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantPlaceholderId,
            role = MessageRole.ASSISTANT,
            content = "",
            timestampMillis = now + 1,
            modelId = modelId,
            isStreaming = true,
        )

        val requestHistory = state.messages.filter { !it.isError } + userMessage
        val sessionTitle = deriveSessionTitle(requestHistory)

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage + assistantPlaceholder,
                inputText = if (overrideText == null) "" else it.inputText,
                isSending = true,
                chatError = null,
            )
        }

        viewModelScope.launch {
            chatHistoryRepository.saveMessage(
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                modelId = modelId,
                message = userMessage,
            )
        }

        executeCompletionStream(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            modelId = modelId,
            requestHistory = requestHistory,
            assistantPlaceholderId = assistantPlaceholderId,
            systemPrompt = systemPrompt,
        )
    }

    /** Retries the last user prompt if the previous request failed. */
    fun retryLastMessage(systemPrompt: String) {
        val state = _uiState.value
        val modelId = state.selectedModelId ?: return
        val lastUserMsg = state.messages.lastOrNull { it.role == MessageRole.USER } ?: return
        val historyWithoutTrailingError = state.messages.dropLastWhile { it.isError || it.isStreaming }
        val requestHistory = historyWithoutTrailingError.filter { !it.isError }
        if (requestHistory.none { it.id == lastUserMsg.id }) return

        val assistantPlaceholderId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantPlaceholderId,
            role = MessageRole.ASSISTANT,
            content = "",
            timestampMillis = System.currentTimeMillis(),
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

        executeCompletionStream(
            sessionId = state.currentSessionId,
            sessionTitle = deriveSessionTitle(requestHistory),
            modelId = modelId,
            requestHistory = requestHistory,
            assistantPlaceholderId = assistantPlaceholderId,
            systemPrompt = systemPrompt,
        )
    }

    private fun executeCompletionStream(
        sessionId: String,
        sessionTitle: String,
        modelId: String,
        requestHistory: List<ChatMessage>,
        assistantPlaceholderId: String,
        systemPrompt: String,
    ) {
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
                            var completedMessage: ChatMessage? = null
                            _uiState.update { current ->
                                val updated = current.messages.map { msg ->
                                    if (msg.id == assistantPlaceholderId) {
                                        val finalized = msg.copy(
                                            content = event.fullText ?: msg.content,
                                            isStreaming = false,
                                            totalTokens = event.totalTokens ?: msg.totalTokens,
                                        )
                                        completedMessage = finalized
                                        finalized
                                    } else {
                                        msg
                                    }
                                }
                                current.copy(isSending = false, messages = updated)
                            }
                            completedMessage?.takeIf { it.content.isNotBlank() }?.let { finalMsg ->
                                chatHistoryRepository.saveMessage(
                                    sessionId = sessionId,
                                    sessionTitle = sessionTitle,
                                    modelId = modelId,
                                    message = finalMsg,
                                )
                            }
                        }
                    }
                }

                // Ensure any trailing non-completed stream state is finalized and persisted
                var finalizedMessage: ChatMessage? = null
                _uiState.update { current ->
                    val updated = current.messages.map { msg ->
                        if (msg.id == assistantPlaceholderId && msg.isStreaming) {
                            val finalized = msg.copy(isStreaming = false)
                            finalizedMessage = finalized
                            finalized
                        } else {
                            msg
                        }
                    }
                    current.copy(isSending = false, messages = updated)
                }
                finalizedMessage?.takeIf { it.content.isNotBlank() }?.let { finalMsg ->
                    chatHistoryRepository.saveMessage(
                        sessionId = sessionId,
                        sessionTitle = sessionTitle,
                        modelId = modelId,
                        message = finalMsg,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val appError = e.toAppError()
                var partialMessage: ChatMessage? = null
                _uiState.update { current ->
                    val cleaned = current.messages.filterNot {
                        it.id == assistantPlaceholderId && it.content.isBlank()
                    }.map { msg ->
                        if (msg.id == assistantPlaceholderId) {
                            val updated = msg.copy(isStreaming = false, isError = true)
                            partialMessage = updated
                            updated
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
                partialMessage?.takeIf { it.content.isNotBlank() }?.let { partial ->
                    chatHistoryRepository.saveMessage(
                        sessionId = sessionId,
                        sessionTitle = sessionTitle,
                        modelId = modelId,
                        message = partial,
                    )
                }
            }
        }
    }

    private fun deriveSessionTitle(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == MessageRole.USER }?.content
        return firstUser?.trim()?.replace('\n', ' ')?.take(64)?.ifBlank { "Chat" } ?: "Chat"
    }
}
