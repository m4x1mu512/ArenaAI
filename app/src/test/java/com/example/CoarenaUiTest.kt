package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ImageAttachmentStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.domain.model.AiModel
import com.example.domain.model.ApiWireFormat
import com.example.domain.model.AppError
import com.example.domain.model.AppSettings
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatSession
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.ConnectionTestResult
import com.example.domain.model.CreditsInfo
import com.example.domain.model.LimitsInfo
import com.example.domain.model.MessageRole
import com.example.domain.model.RequestDiagnostic
import com.example.domain.model.RequestHistoryItem
import com.example.domain.model.ThemeMode
import com.example.domain.repository.ChatHistoryRepository
import com.example.domain.repository.CoarenaRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.setup.SetupScreen
import com.example.ui.setup.SetupViewModel
import com.example.ui.theme.CoarenaTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CoarenaUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstLaunchSetup_validatesInput_andSavesCredentials() {
        val fakeSettings = FakeSettingsRepository()
        val fakeRepo = FakeCoarenaRepository()
        val viewModel = SetupViewModel(fakeSettings, fakeRepo)
        var setupCompleted = false

        composeRule.setContent {
            CoarenaTheme {
                SetupScreen(
                    onSetupComplete = { setupCompleted = true },
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        // Default Base URL is pre-filled
        composeRule.onNodeWithTag("setup_base_url_input")
            .assertTextContains(AppSettings.DEFAULT_BASE_URL)

        // Clicking save without key shows validation error
        composeRule.onNodeWithTag("setup_save_continue_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup_validation_error").performScrollTo().assertIsDisplayed()

        // Enter API key and test connection
        composeRule.onNodeWithTag("setup_api_key_input").performScrollTo().performTextInput("sk-valid-test-key-12345")
        composeRule.onNodeWithTag("setup_test_connection_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup_connection_success_banner").performScrollTo().assertIsDisplayed()

        // Save and continue
        composeRule.onNodeWithTag("setup_save_continue_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(setupCompleted)
        assertEquals("sk-valid-test-key-12345", fakeSettings.storedKey)
    }

    @Test
    fun chatScreen_sendsMessage_andSavesToHistory() {
        val fakeSettings = FakeSettingsRepository(initialKey = "sk-valid-123456")
        val fakeRepo = FakeCoarenaRepository(
            models = listOf(AiModel(id = "coarena-pro", displayName = "Coarena Pro")),
            replyChunks = listOf("Hello ", "from Coarena!"),
        )
        val fakeHistory = FakeChatHistoryRepository()
        val viewModel = ChatViewModel(fakeRepo, fakeSettings, fakeHistory, ImageAttachmentStore(ApplicationProvider.getApplicationContext<Context>()))

        composeRule.setContent {
            CoarenaTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("model_selector_button").assertTextContains("Coarena Pro")
        composeRule.onNodeWithTag("chat_message_input").performTextInput("Hi AI")
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Hi AI").assertIsDisplayed()
        composeRule.onNodeWithText("Hello from Coarena!").assertIsDisplayed()

        // Verify persisted into local history and visible in History sheet
        composeRule.onNodeWithTag("chat_history_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_history_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("history_session_list").assertIsDisplayed()
    }

    @Test
    fun chatScreen_restoresSavedSession_switchesAndDeletes() {
        val fakeSettings = FakeSettingsRepository(initialKey = "sk-valid-123456")
        val fakeRepo = FakeCoarenaRepository(
            models = listOf(AiModel(id = "coarena-pro", displayName = "Coarena Pro")),
        )
        val fakeHistory = FakeChatHistoryRepository().apply {
            seedSession(
                sessionId = "session_old",
                title = "Old Topic",
                modelId = "coarena-pro",
                updatedAt = 1000L,
                messages = listOf(
                    ChatMessage(id = "u1", role = MessageRole.USER, content = "Old question", timestampMillis = 1000L),
                    ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "Old answer", timestampMillis = 1001L),
                ),
            )
            seedSession(
                sessionId = "session_new",
                title = "Recent Topic",
                modelId = "coarena-pro",
                updatedAt = 2000L,
                messages = listOf(
                    ChatMessage(id = "u2", role = MessageRole.USER, content = "Latest question", timestampMillis = 2000L),
                    ChatMessage(id = "a2", role = MessageRole.ASSISTANT, content = "Latest answer", timestampMillis = 2001L),
                ),
            )
        }
        val viewModel = ChatViewModel(fakeRepo, fakeSettings, fakeHistory, ImageAttachmentStore(ApplicationProvider.getApplicationContext<Context>()))

        composeRule.setContent {
            CoarenaTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        // Automatically restores the latest conversation
        composeRule.onNodeWithText("Latest question").assertIsDisplayed()
        composeRule.onNodeWithText("Latest answer").assertIsDisplayed()

        // Open history sheet and switch to the older conversation
        composeRule.onNodeWithTag("chat_history_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_session_session_old").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Old question").assertIsDisplayed()
        composeRule.onNodeWithText("Old answer").assertIsDisplayed()

        // Open history sheet again and delete session_old
        composeRule.onNodeWithTag("chat_history_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_session_list")
            .performScrollToNode(hasTestTag("history_delete_session_session_old"))
        composeRule.onNodeWithTag("history_delete_session_session_old").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            !fakeHistory.containsSession("session_old")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_session_session_old").assertDoesNotExist()
    }

    @Test
    fun chatScreen_showsUnauthorizedError_withoutExposingApiKey() {
        val secretKey = "sk-invalid-secret-key-99999"
        val fakeSettings = FakeSettingsRepository(initialKey = secretKey)
        val fakeRepo = FakeCoarenaRepository(
            models = listOf(AiModel(id = "coarena-pro", displayName = "Coarena Pro")),
            chatError = AppError.Unauthorized("HTTP 401: Invalid key [REDACTED]"),
        )
        val fakeHistory = FakeChatHistoryRepository()
        val viewModel = ChatViewModel(fakeRepo, fakeSettings, fakeHistory, ImageAttachmentStore(ApplicationProvider.getApplicationContext<Context>()))

        composeRule.setContent {
            CoarenaTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chat_message_input").performTextInput("Test auth")
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("error_banner_card").assertIsDisplayed()
        composeRule.onNodeWithTag("error_toggle_technical_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("error_technical_details").assertIsDisplayed()
        composeRule.onNodeWithText(secretKey, substring = true).assertDoesNotExist()
    }

    @Test
    fun settingsScreen_displaysAccountData_andDeletesSavedKeyAfterConfirmation() {
        val fakeSettings = FakeSettingsRepository(initialKey = "sk-saved-12345")
        val fakeRepo = FakeCoarenaRepository(
            models = listOf(AiModel(id = "coarena-pro", displayName = "Coarena Pro")),
        )
        val fakeHistory = FakeChatHistoryRepository()
        val viewModel = SettingsViewModel(fakeSettings, fakeRepo, fakeHistory)
        var navigatedToSetup = false

        composeRule.setContent {
            CoarenaTheme {
                SettingsScreen(
                    onBack = {},
                    onNavigateToSetup = { navigatedToSetup = true },
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_credits_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("25.00 USD").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_delete_key_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_delete_key_button").performClick()
        composeRule.waitForIdle()

        assertTrue(navigatedToSetup)
        assertEquals(null, fakeSettings.storedKey)
    }

    @Test
    fun chatScreen_syncsDefaultModelChange_andSendsSelectedModel() {
        val fakeSettings = FakeSettingsRepository(
            initialKey = "sk-valid-123456",
            initialDefaultModelId = "deepseek-v4-1-flash-1017",
        )
        val fakeRepo = FakeCoarenaRepository(
            models = listOf(
                AiModel(id = "gpt-6-luna", displayName = "GPT-6 Luna"),
                AiModel(id = "deepseek-v4-1-flash-1017", displayName = "DeepSeek v4.1 flash"),
            ),
            replyChunks = listOf("Answer"),
            responseModelId = "deepseek-v4-1-flash-1017",
        )
        val fakeHistory = FakeChatHistoryRepository().apply {
            seedSession(
                sessionId = "old_session",
                title = "Old GPT chat",
                modelId = "gpt-6-luna",
                updatedAt = 1000L,
                messages = listOf(
                    ChatMessage(id = "u1", role = MessageRole.USER, content = "Hi", timestampMillis = 1000L),
                    ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "Hello", timestampMillis = 1001L, modelId = "gpt-6-luna"),
                ),
            )
        }
        val viewModel = ChatViewModel(fakeRepo, fakeSettings, fakeHistory, ImageAttachmentStore(ApplicationProvider.getApplicationContext<Context>()))

        composeRule.setContent {
            CoarenaTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        // Default model from settings must win over older session's modelId on startup
        composeRule.onNodeWithTag("model_selector_button").assertTextContains("DeepSeek v4.1 flash")

        // Send message and verify exact requested modelId
        composeRule.onNodeWithTag("chat_message_input").performTextInput("Test DeepSeek")
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.waitForIdle()
        assertEquals("deepseek-v4-1-flash-1017", fakeRepo.lastSentModelId)

        // Changing default model in Settings updates ChatScreen selection even after a model is active
        kotlinx.coroutines.runBlocking {
            fakeSettings.saveDefaultModel("gpt-6-luna")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("model_selector_button").assertTextContains("GPT-6 Luna")
    }

    @Test
    fun chatScreen_showsModelMismatchArrow_whenServerEchoesDifferentModel() {
        val fakeSettings = FakeSettingsRepository(initialKey = "sk-valid-123456")
        val fakeRepo = FakeCoarenaRepository(
            models = listOf(
                AiModel(id = "gpt-6-luna", displayName = "GPT-6 Luna"),
                AiModel(id = "deepseek-v4-1-flash-1017", displayName = "DeepSeek v4.1 flash"),
            ),
            replyChunks = listOf("Routed reply"),
            responseModelId = "gpt-6-luna",
        )
        val fakeHistory = FakeChatHistoryRepository()
        val viewModel = ChatViewModel(fakeRepo, fakeSettings, fakeHistory, ImageAttachmentStore(ApplicationProvider.getApplicationContext<Context>()))

        composeRule.setContent {
            CoarenaTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        // Pick DeepSeek in model picker sheet
        composeRule.onNodeWithTag("model_selector_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("model_item_deepseek-v4-1-flash-1017").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chat_message_input").performTextInput("Ping")
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.waitForIdle()

        assertEquals("deepseek-v4-1-flash-1017", fakeRepo.lastSentModelId)
        composeRule.onNodeWithText("deepseek-v4-1-flash-1017 → gpt-6-luna").assertIsDisplayed()
    }

    private class FakeSettingsRepository(
        initialKey: String? = null,
        initialDefaultModelId: String? = null,
    ) : SettingsRepository {
        var storedKey: String? = initialKey
        private val state = MutableStateFlow(
            AppSettings(
                baseUrl = AppSettings.DEFAULT_BASE_URL,
                defaultModelId = initialDefaultModelId,
                themeMode = ThemeMode.SYSTEM,
                hasApiKey = !initialKey.isNullOrBlank(),
            ),
        )
        override val settingsFlow: Flow<AppSettings> = state
        override val isInitialized: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun getApiKey(): String? = storedKey

        override suspend fun saveCredentials(apiKey: String, baseUrl: String) {
            storedKey = apiKey
            state.value = state.value.copy(baseUrl = baseUrl, hasApiKey = true)
        }

        override suspend fun saveBaseUrl(baseUrl: String) {
            state.value = state.value.copy(baseUrl = baseUrl)
        }

        override suspend fun saveDefaultModel(modelId: String?) {
            state.value = state.value.copy(defaultModelId = modelId)
        }

        override suspend fun saveThemeMode(themeMode: ThemeMode) {
            state.value = state.value.copy(themeMode = themeMode)
        }

        override suspend fun saveUseStreaming(enabled: Boolean) {
            state.value = state.value.copy(useStreaming = enabled)
        }

        override suspend fun saveWireFormat(wireFormat: ApiWireFormat) {
            state.value = state.value.copy(wireFormat = wireFormat)
        }

        override suspend fun deleteApiKey() {
            storedKey = null
            state.value = state.value.copy(hasApiKey = false)
        }

        override suspend fun clearAllLocalData() {
            storedKey = null
            state.value = AppSettings()
        }
    }

    private class FakeChatHistoryRepository : ChatHistoryRepository {
        private val sessionsMap = LinkedHashMap<String, ChatSession>()
        private val messagesMap = LinkedHashMap<String, MutableList<ChatMessage>>()
        private val sessionsFlow = MutableStateFlow<List<ChatSession>>(emptyList())

        fun containsSession(sessionId: String): Boolean = sessionsMap.containsKey(sessionId)

        fun seedSession(
            sessionId: String,
            title: String,
            modelId: String?,
            updatedAt: Long,
            messages: List<ChatMessage>,
        ) {
            messagesMap[sessionId] = messages.toMutableList()
            sessionsMap[sessionId] = ChatSession(
                id = sessionId,
                title = title,
                modelId = modelId,
                createdAtMillis = updatedAt,
                updatedAtMillis = updatedAt,
                messageCount = messages.size,
                lastMessagePreview = messages.lastOrNull()?.content.orEmpty(),
            )
            emitSessions()
        }

        private fun emitSessions() {
            sessionsFlow.value = sessionsMap.values.sortedByDescending { it.updatedAtMillis }
        }

        override fun observeSessions(): Flow<List<ChatSession>> = sessionsFlow

        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> {
            return sessionsFlow.map { messagesMap[sessionId].orEmpty() }
        }

        override suspend fun getLatestSession(): ChatSession? {
            return sessionsMap.values.maxByOrNull { it.updatedAtMillis }
        }

        override suspend fun getMessages(sessionId: String): List<ChatMessage> {
            return messagesMap[sessionId].orEmpty().sortedBy { it.timestampMillis }
        }

        override suspend fun saveMessage(
            sessionId: String,
            sessionTitle: String,
            modelId: String?,
            message: ChatMessage,
        ) {
            val list = messagesMap.getOrPut(sessionId) { mutableListOf() }
            list.removeAll { it.id == message.id }
            list.add(message)
            val existing = sessionsMap[sessionId]
            sessionsMap[sessionId] = ChatSession(
                id = sessionId,
                title = existing?.title ?: sessionTitle,
                modelId = modelId ?: existing?.modelId,
                createdAtMillis = existing?.createdAtMillis ?: message.timestampMillis,
                updatedAtMillis = message.timestampMillis,
                messageCount = list.size,
                lastMessagePreview = message.content,
            )
            emitSessions()
        }

        override suspend fun deleteMessage(messageId: String) {
            messagesMap.values.forEach { list -> list.removeAll { it.id == messageId } }
            emitSessions()
        }

        override suspend fun deleteSession(sessionId: String) {
            sessionsMap.remove(sessionId)
            messagesMap.remove(sessionId)
            emitSessions()
        }

        override suspend fun clearAllHistory() {
            sessionsMap.clear()
            messagesMap.clear()
            emitSessions()
        }
    }

    private class FakeCoarenaRepository(
        private val models: List<AiModel> = listOf(AiModel(id = "coarena-pro", displayName = "Coarena Pro")),
        private val replyChunks: List<String> = listOf("Hello!"),
        private val chatError: AppError? = null,
        private val responseModelId: String? = null,
    ) : CoarenaRepository {

        var lastSentModelId: String? = null
        override val lastRequestDiagnostic: StateFlow<RequestDiagnostic?> = MutableStateFlow(null)

        override suspend fun fetchModels(): Result<List<AiModel>> = Result.success(models)

        override suspend fun testConnection(apiKey: String, baseUrl: String): ConnectionTestResult {
            return ConnectionTestResult(isSuccess = true, modelCount = models.size)
        }

        override fun sendChatMessage(
            modelId: String,
            history: List<ChatMessage>,
            systemPrompt: String,
        ): Flow<ChatStreamEvent> = flow {
            lastSentModelId = modelId
            if (chatError != null) {
                throw com.example.data.remote.CoarenaApiException(chatError)
            }
            replyChunks.forEach { emit(ChatStreamEvent.Delta(it, responseModelId = responseModelId)) }
            emit(ChatStreamEvent.Completed(totalTokens = 42, responseModelId = responseModelId))
        }

        override suspend fun fetchCredits(): Result<CreditsInfo> = Result.success(
            CreditsInfo(balance = "25.00", currency = "USD", totalUsed = "5.00"),
        )

        override suspend fun fetchLimits(): Result<LimitsInfo> = Result.success(
            LimitsInfo(requestsPerMinute = "60", tokensPerMinute = "100000"),
        )

        override suspend fun fetchRequests(): Result<List<RequestHistoryItem>> = Result.success(
            listOf(
                RequestHistoryItem(
                    id = "req_1",
                    createdAt = "2026-11-16 12:00",
                    model = "coarena-pro",
                    status = "200",
                    totalTokens = 128,
                ),
            ),
        )
    }
}
