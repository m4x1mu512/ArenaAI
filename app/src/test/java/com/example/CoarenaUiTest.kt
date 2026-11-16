package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.domain.model.AiModel
import com.example.domain.model.AppError
import com.example.domain.model.AppSettings
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.ConnectionTestResult
import com.example.domain.model.CreditsInfo
import com.example.domain.model.LimitsInfo
import com.example.domain.model.RequestHistoryItem
import com.example.domain.model.ThemeMode
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
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
    fun chatScreen_sendsMessage_andDisplaysAssistantReply() {
        val fakeSettings = FakeSettingsRepository(initialKey = "sk-valid-123456")
        val fakeRepo = FakeCoarenaRepository(
            models = listOf(AiModel(id = "coarena-pro", displayName = "Coarena Pro")),
            replyChunks = listOf("Hello ", "from Coarena!"),
        )
        val viewModel = ChatViewModel(fakeRepo, fakeSettings)

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
    }

    @Test
    fun chatScreen_showsUnauthorizedError_withoutExposingApiKey() {
        val secretKey = "sk-invalid-secret-key-99999"
        val fakeSettings = FakeSettingsRepository(initialKey = secretKey)
        val fakeRepo = FakeCoarenaRepository(
            models = listOf(AiModel(id = "coarena-pro", displayName = "Coarena Pro")),
            chatError = AppError.Unauthorized("HTTP 401: Invalid key [REDACTED]"),
        )
        val viewModel = ChatViewModel(fakeRepo, fakeSettings)

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
        val viewModel = SettingsViewModel(fakeSettings, fakeRepo)
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

    private class FakeSettingsRepository(
        initialKey: String? = null,
    ) : SettingsRepository {
        var storedKey: String? = initialKey
        private val state = MutableStateFlow(
            AppSettings(
                baseUrl = AppSettings.DEFAULT_BASE_URL,
                defaultModelId = null,
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

        override suspend fun deleteApiKey() {
            storedKey = null
            state.value = state.value.copy(hasApiKey = false)
        }

        override suspend fun clearAllLocalData() {
            storedKey = null
            state.value = AppSettings()
        }
    }

    private class FakeCoarenaRepository(
        private val models: List<AiModel> = listOf(AiModel(id = "coarena-pro", displayName = "Coarena Pro")),
        private val replyChunks: List<String> = listOf("Hello!"),
        private val chatError: AppError? = null,
    ) : CoarenaRepository {

        override suspend fun fetchModels(): Result<List<AiModel>> = Result.success(models)

        override suspend fun testConnection(apiKey: String, baseUrl: String): ConnectionTestResult {
            return ConnectionTestResult(isSuccess = true, modelCount = models.size)
        }

        override fun sendChatMessage(
            modelId: String,
            history: List<ChatMessage>,
            systemPrompt: String,
        ): Flow<ChatStreamEvent> = flow {
            if (chatError != null) {
                throw com.example.data.remote.CoarenaApiException(chatError)
            }
            replyChunks.forEach { emit(ChatStreamEvent.Delta(it)) }
            emit(ChatStreamEvent.Completed(totalTokens = 42))
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
