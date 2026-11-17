package com.example.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AiModel
import com.example.domain.model.AppError
import com.example.domain.model.ChatAttachment
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatSession
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.MessageRole
import com.example.data.local.ImageAttachmentStore
import com.example.domain.repository.ChatHistoryRepository
import com.example.domain.repository.CoarenaRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.common.toAppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val pendingAttachments: List<ChatAttachment> = emptyList(),
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
    private var loadModelsJob: Job? = null
    private var hasUserExplicitlySelectedModel = false
    private var modelRestoredFromSession = false
    private var modelAutoDefaultedFromFirst = false
    private var latestDefaultModelId: String? = null

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
                    val shouldUseSessionModel = !hasUserExplicitlySelectedModel &&
                        state.selectedModelId == null &&
                        latestDefaultModelId == null &&
                        latest.modelId != null &&
                        (state.models.isEmpty() || state.models.any { it.id == latest.modelId })
                    if (shouldUseSessionModel) {
                        modelRestoredFromSession = true
                    }
                    state.copy(
                        currentSessionId = latest.id,
                        messages = savedMessages,
                        selectedModelId = if (shouldUseSessionModel) latest.modelId else state.selectedModelId,
                    )
                }
            }
        }
    }

    private fun observeSettingsAndLoadModels() {
        viewModelScope.launch {
            var previousHasKey: Boolean? = null
            var previousBaseUrl: String? = null
            var previousDefaultModelId: String? = null
            var hasObservedInitialSettings = false

            settingsRepository.settingsFlow.collect { settings ->
                latestDefaultModelId = settings.defaultModelId

                if (!hasObservedInitialSettings) {
                    hasObservedInitialSettings = true
                    previousDefaultModelId = settings.defaultModelId
                    if (settings.defaultModelId != null &&
                        !hasUserExplicitlySelectedModel &&
                        (_uiState.value.selectedModelId == null || modelRestoredFromSession || modelAutoDefaultedFromFirst)
                    ) {
                        modelRestoredFromSession = false
                        modelAutoDefaultedFromFirst = false
                        _uiState.update { it.copy(selectedModelId = settings.defaultModelId) }
                    }
                } else if (settings.defaultModelId != previousDefaultModelId) {
                    previousDefaultModelId = settings.defaultModelId
                    if (settings.defaultModelId != null) {
                        modelRestoredFromSession = false
                        modelAutoDefaultedFromFirst = false
                        _uiState.update { it.copy(selectedModelId = settings.defaultModelId) }
                    }
                }

                val credentialsChanged = settings.hasApiKey != previousHasKey ||
                    settings.baseUrl != previousBaseUrl
                previousHasKey = settings.hasApiKey
                previousBaseUrl = settings.baseUrl

                if (credentialsChanged && settings.hasApiKey) {
                    loadModels(preferredModelId = settings.defaultModelId)
                }
            }
        }
    }

    /** Loads or refreshes available models from `GET /models`. */
    fun loadModels(preferredModelId: String? = null) {
        loadModelsJob?.cancel()
        loadModelsJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true, modelsError = null) }
            coarenaRepository.fetchModels().fold(
                onSuccess = { models ->
                    _uiState.update { state ->
                        val candidate = state.selectedModelId ?: preferredModelId ?: latestDefaultModelId
                        val resolvedSelection = when {
                            candidate != null && models.any { it.id == candidate } -> {
                                modelAutoDefaultedFromFirst = false
                                candidate
                            }
                            models.isNotEmpty() -> {
                                modelAutoDefaultedFromFirst = candidate == null
                                models.first().id
                            }
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

    /** Imports up to eight selected images into private storage for the pending message. */
    fun addImages(uris: List<Uri>) {
        viewModelScope.launch {
            val current = _uiState.value.pendingAttachments
            val remaining = (ImageAttachmentStore.MAX_IMAGES - current.size).coerceAtLeast(0)
            if (remaining == 0) {
                _uiState.update { it.copy(chatError = AppError.Unknown("You can attach up to 8 images")) }
                return@launch
            }
            val imported = mutableListOf<ChatAttachment>()
            try {
                uris.take(remaining).forEach { uri ->
                    imported += imageAttachmentStore.import(uri)
                }
                _uiState.update {
                    it.copy(pendingAttachments = it.pendingAttachments + imported, chatError = null)
                }
            } catch (error: Throwable) {
                imported.forEach(imageAttachmentStore::delete)
                _uiState.update { it.copy(chatError = AppError.Unknown(error.message ?: "Unable to add image")) }
            }
        }
    }

    fun removePendingImage(attachmentId: String) {
        val attachment = _uiState.value.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        imageAttachmentStore.delete(attachment)
        _uiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == attachmentId })
        }
    }

    /** Selects an AI model for subsequent messages and remembers it as default. */
    fun selectModel(modelId: String) {
        hasUserExplicitlySelectedModel = true
        modelRestoredFromSession = false
        modelAutoDefaultedFromFirst = false
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
        hasUserExplicitlySelectedModel = true
        modelRestoredFromSession = false
        modelAutoDefaultedFromFirst = false
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
        if (prompt.isEmpty() && state.pendingAttachments.isEmpty()) return

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
                inputText = if (overrideText == null) "" else it.inputText,\n                pendingAttachments = emptyList(),
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
            var latestResponseModelId: String? = null
            try {
                coarenaRepository.sendChatMessage(
                    modelId = modelId,
                    history = requestHistory,
                    systemPrompt = systemPrompt,
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            val responseModel = event.responseModelId?.takeIf { it.isNotBlank() }
                            if (responseModel != null) {
                                latestResponseModelId = responseModel
                            }
                            val label = formatBubbleModelLabel(modelId, latestResponseModelId)
                            _uiState.update { current ->
                                current.copy(
                                    messages = current.messages.map { msg ->
                                        if (msg.id == assistantPlaceholderId) {
                                            msg.copy(
                                                content = msg.content + event.textChunk,
                                                modelId = label,
                                            )
                                        } else {
                                            msg
                                        }
                                    },
                                )
                            }
                        }
                        is ChatStreamEvent.Completed -> {
                            val actualResponseModel = event.responseModelId?.takeIf { it.isNotBlank() }
                                ?: latestResponseModelId
                            if (actualResponseModel != null) {
                                latestResponseModelId = actualResponseModel
                            }
                            val label = formatBubbleModelLabel(modelId, actualResponseModel)
                            var completedMessage: ChatMessage? = null
                            _uiState.update { current ->
                                val updated = current.messages.map { msg ->
                                    if (msg.id == assistantPlaceholderId) {
                                        val finalized = msg.copy(
                                            content = event.fullText ?: msg.content,
                                            isStreaming = false,
                                            totalTokens = event.totalTokens ?: msg.totalTokens,
                                            modelId = label,
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
                val label = formatBubbleModelLabel(modelId, latestResponseModelId)
                var finalizedMessage: ChatMessage? = null
                _uiState.update { current ->
                    val updated = current.messages.map { msg ->
                        if (msg.id == assistantPlaceholderId && msg.isStreaming) {
                            val finalized = msg.copy(
                                isStreaming = false,
                                modelId = label,
                            )
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
                val label = formatBubbleModelLabel(modelId, latestResponseModelId)
                var partialMessage: ChatMessage? = null
                _uiState.update { current ->
                    val cleaned = current.messages.filterNot {
                        it.id == assistantPlaceholderId && it.content.isBlank()
                    }.map { msg ->
                        if (msg.id == assistantPlaceholderId) {
                            val updated = msg.copy(
                                isStreaming = false,
                                isError = true,
                                modelId = label,
                            )
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

    private fun formatBubbleModelLabel(
        requestedModelId: String,
        responseModelId: String?,
    ): String {
        val actual = responseModelId?.trim()?.takeIf { it.isNotEmpty() } ?: return requestedModelId
        return if (actual != requestedModelId) {
            "$requestedModelId → $actual"
        } else {
            actual
        }
    }

    private fun deriveSessionTitle(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == MessageRole.USER }?.content
        return firstUser?.trim()?.replace('\n', ' ')?.take(64)?.ifBlank { "Chat" } ?: "Chat"
    }
}
