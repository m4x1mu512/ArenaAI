package com.example.domain.repository

import com.example.domain.model.AiModel
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.ConnectionTestResult
import com.example.domain.model.CreditsInfo
import com.example.domain.model.LimitsInfo
import com.example.domain.model.RequestDiagnostic
import com.example.domain.model.RequestHistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for communicating with Coarena OpenAI-compatible and account endpoints.
 */
interface CoarenaRepository {
    /** Emits non-secret diagnostic details of the most recent chat completion request. */
    val lastRequestDiagnostic: StateFlow<RequestDiagnostic?>

    /** Fetches available models using the currently saved credentials. */
    suspend fun fetchModels(): Result<List<AiModel>>

    /** Tests connectivity with explicit [apiKey] and [baseUrl] without persisting them first. */
    suspend fun testConnection(apiKey: String, baseUrl: String): ConnectionTestResult

    /**
     * Sends chat completion messages to [modelId], streaming SSE deltas when available and
     * automatically falling back to non-streaming JSON if SSE fails or returns JSON directly.
     */
    fun sendChatMessage(
        modelId: String,
        history: List<ChatMessage>,
        systemPrompt: String,
    ): Flow<ChatStreamEvent>

    /** Fetches current account credit balance from `GET /credits`. */
    suspend fun fetchCredits(): Result<CreditsInfo>

    /** Fetches current account limits from `GET /limits`. */
    suspend fun fetchLimits(): Result<LimitsInfo>

    /** Fetches recent request logs from `GET /requests`. */
    suspend fun fetchRequests(): Result<List<RequestHistoryItem>>
}
